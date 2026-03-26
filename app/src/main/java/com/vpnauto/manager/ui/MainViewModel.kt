package com.vpnauto.manager.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.model.Subscription
import com.vpnauto.manager.service.*
import com.vpnauto.manager.util.PingTester
import com.vpnauto.manager.util.SubscriptionRepository
import com.vpnauto.manager.util.V2RayController
import com.vpnauto.manager.worker.VpnUpdateWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed class UiState {
    object Idle : UiState()
    data class Loading(val message: String) : UiState()
    data class Error(val message: String) : UiState()
    data class Success(val message: String) : UiState()
}
enum class VpnConnState { DISCONNECTED, CONNECTING, CONNECTED }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SubscriptionRepository(application)
    private val ctx  = application

    private val _uiState      = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState
    private val _subscriptions = MutableLiveData<List<Subscription>>()
    val subscriptions: LiveData<List<Subscription>> = _subscriptions
    private val _servers      = MutableLiveData<List<ServerConfig>>()
    val servers: LiveData<List<ServerConfig>> = _servers
    private val _bestServer   = MutableLiveData<ServerConfig?>()
    val bestServer: LiveData<ServerConfig?> = _bestServer
    private val _lastUpdateText = MutableLiveData<String>()
    val lastUpdateText: LiveData<String> = _lastUpdateText
    private val _vpnState     = MutableLiveData(VpnConnState.DISCONNECTED)
    val vpnState: LiveData<VpnConnState> = _vpnState
    private val _vpnMessage   = MutableLiveData<String>()
    val vpnMessage: LiveData<String> = _vpnMessage
    private val _vpnPermissionNeeded = MutableLiveData<Intent?>()
    val vpnPermissionNeeded: LiveData<Intent?> = _vpnPermissionNeeded
    private val _hotspotActive  = MutableLiveData(false)
    val hotspotActive: LiveData<Boolean> = _hotspotActive
    private val _hotspotAddress = MutableLiveData("")
    val hotspotAddress: LiveData<String> = _hotspotAddress
    private val _isUpdating   = MutableLiveData(false)
    val isUpdating: LiveData<Boolean> = _isUpdating

    // Новые LiveData
    private val _trafficStats = MutableLiveData<TrafficSnapshot?>()
    val trafficStats: LiveData<TrafficSnapshot?> = _trafficStats
    val watchdogPing  = MutableLiveData<Long>(-1L)

    private var updateJob: Job? = null
    private var pendingServer: ServerConfig? = null
    private var lastConnectedServer: ServerConfig? = null
    private val trafficMonitor = TrafficMonitor { snap -> _trafficStats.postValue(snap) }
    private val watchdog = Watchdog(
        onReconnect = { lastConnectedServer?.let { reconnect(it) } },
        onStatusUpdate = { ms -> watchdogPing.postValue(ms) }
    )

    val isV2RayInstalled: Boolean get() = V2RayController.isV2RayInstalled(ctx)

    var updateIntervalHours: Int
        get() = repo.updateIntervalHours
        set(v) { repo.updateIntervalHours = v; rescheduleWork() }
    var autoConnect:    Boolean get() = repo.autoConnect;    set(v) { repo.autoConnect = v }
    var pingOnUpdate:   Boolean get() = repo.pingOnUpdate;   set(v) { repo.pingOnUpdate = v }
    private val settingsPrefs get() = ctx.getSharedPreferences("settings", 0)

    var killSwitchEnabled: Boolean
        get() = settingsPrefs.getBoolean("kill_switch", true)
        set(v) { settingsPrefs.edit().putBoolean("kill_switch", v).apply() }
    var autoReconnect: Boolean
        get() = settingsPrefs.getBoolean("auto_reconnect", true)
        set(v) { settingsPrefs.edit().putBoolean("auto_reconnect", v).apply() }
    var fileLoggingEnabled: Boolean
        get() = settingsPrefs.getBoolean("file_logging", true)
        set(v) {
            settingsPrefs.edit().putBoolean("file_logging", v).apply()
            com.vpnauto.manager.service.FileLogger.fileWriteEnabled = v
        }
    var autoConnectOnLaunch: Boolean
        get() = settingsPrefs.getBoolean("auto_connect_on_launch", false)
        set(v) { settingsPrefs.edit().putBoolean("auto_connect_on_launch", v).apply() }

    private val vpnStatusListener: (Boolean, String) -> Unit = { connected, message ->
        _vpnMessage.postValue(message)
        if (connected) {
            _vpnState.postValue(VpnConnState.CONNECTED)
            trafficMonitor.start()
            if (autoReconnect) watchdog.start()
            ConnectionHistory.add(ConnectionRecord(
                serverName = DirectVpnService.connectedServer,
                host = lastConnectedServer?.host ?: "",
                protocol = lastConnectedServer?.protocol ?: "",
                connectedAt = System.currentTimeMillis()
            ))
        } else if (message.startsWith("⏳")) {
            _vpnState.postValue(VpnConnState.CONNECTING)
        } else {
            _vpnState.postValue(VpnConnState.DISCONNECTED)
            trafficMonitor.stop()
            watchdog.stop()
            _trafficStats.postValue(null)
            // Обновить историю
            ConnectionHistory.updateLast {
                copy(
                    disconnectedAt = System.currentTimeMillis(),
                    rxBytes = trafficMonitor.totalRx,
                    txBytes = trafficMonitor.totalTx
                )
            }
            if (killSwitchEnabled && !message.contains("отмен", ignoreCase = true)) {
                KillSwitch.enable(ctx) { /* network lost while connected */ }
            }
        }
    }
    private val hotspotStatusListener: (Boolean, String) -> Unit = { a, addr ->
        _hotspotActive.postValue(a); _hotspotAddress.postValue(addr)
    }

    init {
        loadData()
        VpnStatusBus.subscribe(vpnStatusListener)
        HotspotStatusBus.subscribe(hotspotStatusListener)
        if (DirectVpnService.isRunning) {
            _vpnState.value = VpnConnState.CONNECTED
            _vpnMessage.value = DirectVpnService.connectedServer
            trafficMonitor.start()
            if (autoReconnect) watchdog.start()
        } else if (DirectVpnService.isConnecting) {
            _vpnState.value = VpnConnState.CONNECTING
        }
        if (DirectVpnService.hotspotProxyActive) {
            _hotspotActive.value = true
            _hotspotAddress.value = DirectVpnService.hotspotProxyAddress
        }
        if (NetworkRules.isEnabled()) registerNetworkRules()

        // Авто-подключение при запуске
        if (autoConnectOnLaunch && !DirectVpnService.isRunning && !DirectVpnService.isConnecting) {
            val best = _bestServer.value
            if (best != null) connectToServer(best)
        }
    }

    override fun onCleared() {
        VpnStatusBus.unsubscribe(vpnStatusListener)
        HotspotStatusBus.unsubscribe(hotspotStatusListener)
        trafficMonitor.stop()
        watchdog.stop()
        NetworkRules.unregisterReceiver(ctx)
        super.onCleared()
    }

    fun loadData() {
        _subscriptions.value = repo.getSubscriptions()
        val cached = repo.getAllCachedServers()
        _servers.value = cached
        _bestServer.value = cached.firstOrNull { it.isReachable }
        updateLastUpdateText()
        rescheduleWork()
    }

    fun updateNow() {
        if (_isUpdating.value == true) return
        updateJob = viewModelScope.launch {
            _isUpdating.value = true
            _uiState.value = UiState.Loading("Скачивание подписок...")
            try {
                val results = repo.fetchAllEnabledSubscriptions()
                val allServers = mutableListOf<ServerConfig>()
                var errors = 0
                results.forEach { (_, r) ->
                    r.fold(onSuccess = { allServers.addAll(it) }, onFailure = { errors++ })
                }
                if (allServers.isEmpty()) {
                    _uiState.value = UiState.Error("Не удалось загрузить конфиги.")
                    return@launch
                }
                _uiState.value = UiState.Loading("Пингуем 0 / ${allServers.size}...")
                val tested = PingTester.testServers(allServers) { done, total ->
                    _uiState.postValue(UiState.Loading("Пинг: $done / $total"))
                }
                _servers.postValue(tested)
                val best = tested.firstOrNull { it.isReachable }
                _bestServer.postValue(best)
                repo.lastUpdateTime = System.currentTimeMillis()
                updateLastUpdateText()
                val reachable = tested.count { it.isReachable }
                val errText   = if (errors > 0) " ($errors ошибок)" else ""
                if (best != null)
                    _uiState.postValue(UiState.Success("✅ $reachable из ${tested.size}. Лучший: ${best.name} (${best.pingMs}ms)$errText"))
                else
                    _uiState.postValue(UiState.Error("Нет доступных серверов$errText"))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) _uiState.postValue(UiState.Idle)
                else _uiState.postValue(UiState.Error("Ошибка: ${e.message}"))
            } finally {
                _isUpdating.postValue(false)
            }
        }
    }

    fun cancelUpdate() {
        updateJob?.cancel(); updateJob = null
        _isUpdating.value = false; _uiState.value = UiState.Idle
    }

    fun connectToBest() {
        val server = _bestServer.value ?: _servers.value?.firstOrNull { it.isReachable } ?: run {
            _uiState.value = UiState.Error("Нет доступных серверов."); return
        }
        connectToServer(server)
    }

    fun connectToServer(server: ServerConfig) {
        val permIntent = VpnService.prepare(ctx)
        if (permIntent != null) { pendingServer = server; _vpnPermissionNeeded.value = permIntent; return }
        launchVpn(server)
    }

    fun onVpnPermissionGranted() {
        pendingServer?.let { launchVpn(it) }; pendingServer = null; _vpnPermissionNeeded.value = null
    }
    fun onVpnPermissionDenied() {
        pendingServer = null; _vpnPermissionNeeded.value = null
        _uiState.value = UiState.Error("Разрешение VPN не предоставлено")
    }

    private fun launchVpn(server: ServerConfig) {
        lastConnectedServer = server
        _vpnState.value = VpnConnState.CONNECTING
        _vpnMessage.value = "⏳ Подключение к ${server.name}..."
        // Передать настройки Kill Switch через extras
        val intent = DirectVpnService.buildConnectIntent(ctx, server).apply {
            putExtra("kill_switch", killSwitchEnabled)
        }
        ctx.startService(intent)
    }

    private fun reconnect(server: ServerConfig) {
        ConnectionLog.w("Watchdog: переподключаемся к ${server.name}")
        ctx.startService(DirectVpnService.buildDisconnectIntent(ctx))
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            launchVpn(server)
        }
    }

    fun cancelConnect() {
        ctx.startService(DirectVpnService.buildCancelIntent(ctx))
        _vpnState.value = VpnConnState.DISCONNECTED; _vpnMessage.value = "Подключение отменено"
    }

    fun disconnect() {
        KillSwitch.disable(ctx)
        ctx.startService(DirectVpnService.buildDisconnectIntent(ctx))
        _vpnState.value = VpnConnState.DISCONNECTED; _vpnMessage.value = "Отключено"
    }

    fun toggleHotspotShare(enable: Boolean) = ctx.startService(DirectVpnService.buildHotspotIntent(ctx, enable))

    fun applyProfile(profile: VpnProfile) {
        killSwitchEnabled = profile.killSwitch
        autoReconnect = profile.autoReconnect
        ConnectionLog.i("Профиль применён: ${profile.name}")
    }

    fun registerNetworkRules() {
        NetworkRules.registerReceiver(ctx) { rule ->
            rule ?: return@registerReceiver
            when (rule.action) {
                NetworkAction.CONNECT    -> if (!DirectVpnService.isRunning) connectToBest()
                NetworkAction.DISCONNECT -> if (DirectVpnService.isRunning) disconnect()
                NetworkAction.NOTHING    -> {}
            }
        }
    }

    fun unregisterNetworkRules() = NetworkRules.unregisterReceiver(ctx)

    fun getCachedServers(subId: String) = repo.getCachedServers(subId)

    suspend fun fetchServersForSub(sub: Subscription): List<ServerConfig>? =
        repo.fetchSubscription(sub).getOrNull()

    fun toggleSubscription(sub: Subscription, enabled: Boolean) {
        repo.updateSubscription(sub.copy(isEnabled = enabled)); _subscriptions.value = repo.getSubscriptions()
    }
    fun addCustomSubscription(url: String, name: String) {
        repo.updateSubscription(Subscription(
            id = "custom_${System.currentTimeMillis()}", name = name.ifEmpty { "Своя подписка" },
            url = url, type = com.vpnauto.manager.model.SubscriptionType.CUSTOM, isEnabled = true))
        _subscriptions.value = repo.getSubscriptions()
    }
    fun importSubscriptionToV2Ray(sub: Subscription) = V2RayController.importSubscriptionUrl(ctx, sub.url, sub.name)
    fun importAllToV2Ray() {
        repo.getSubscriptions().filter { it.isEnabled }
            .forEach { V2RayController.importSubscriptionUrl(ctx, it.url, it.name) }
        _uiState.value = UiState.Success("Подписки отправлены в v2rayNG")
    }
    fun openV2Ray()    = V2RayController.openV2Ray(ctx)
    fun installV2Ray() = V2RayController.openInstallPage(ctx)
    private fun rescheduleWork() = VpnUpdateWorker.schedule(ctx, repo.updateIntervalHours)
    fun cancelAutoUpdate() = VpnUpdateWorker.cancel(ctx)
    private fun updateLastUpdateText() {
        val time = repo.lastUpdateTime
        _lastUpdateText.value = if (time == 0L) "Никогда"
        else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(time))
    }
}
