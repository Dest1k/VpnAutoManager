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
import kotlinx.coroutines.delay
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

/**
 * Элемент единого списка серверов (для RecyclerView с группировкой).
 *
 * Структура отображения:
 *  - [SubHeader] — заголовок группы подписки (нажать = свернуть/развернуть)
 *  - [SubServer] — не пингованный сервер в группе подписки
 *  - [PingedHeader] — разделитель «Пропингованные серверы»
 *  - [PingedServer] — пропингованный сервер, отсортированный по latency
 */
sealed class ServerListItem {
    data class SubHeader(
        val sub: Subscription,
        val serverCount: Int,
        val isExpanded: Boolean
    ) : ServerListItem()

    data class SubServer(
        val server: ServerConfig,
        val subId: String
    ) : ServerListItem()

    object PingedHeader : ServerListItem()

    data class PingedServer(val server: ServerConfig) : ServerListItem()
}

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

    /** Сгруппированный список для RecyclerView (заголовки подписок + серверы + пропингованные). */
    private val _serverListItems = MutableLiveData<List<ServerListItem>>()
    val serverListItems: LiveData<List<ServerListItem>> = _serverListItems

    /** Состояние развёрнутости групп подписок (subId → expanded). */
    private val expandedGroups = mutableMapOf<String, Boolean>()

    private val _trafficStats = MutableLiveData<TrafficSnapshot?>()
    val trafficStats: LiveData<TrafficSnapshot?> = _trafficStats
    val watchdogPing  = MutableLiveData<Long>(-1L)

    private var updateJob: Job? = null
    private var pendingServer: ServerConfig? = null
    private var lastConnectedServer: ServerConfig? = null
    private val trafficMonitor = TrafficMonitor { snap -> _trafficStats.postValue(snap) }
    private val watchdog = Watchdog(
        onReconnect = {
            val current = lastConnectedServer
            val servers = _servers.value
            // Сначала ищем пропингованный другой сервер, иначе — любой другой
            val next = servers?.firstOrNull { it.isReachable && it.raw != current?.raw }
                ?: servers?.firstOrNull { it.raw != current?.raw }
                ?: current
            next?.let { reconnect(it) }
        },
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
            ConnectionHistory.updateLast {
                copy(
                    disconnectedAt = System.currentTimeMillis(),
                    rxBytes = trafficMonitor.totalRx,
                    txBytes = trafficMonitor.totalTx
                )
            }
            if (killSwitchEnabled && !message.contains("отмен", ignoreCase = true)) {
                KillSwitch.enable(ctx) { }
            }
            // Если сервер признан недоступным (прокси-проверка провалилась) —
            // автоматически переключаемся на следующий доступный сервер
            if (autoReconnect && DirectVpnService.serverFailed
                    && !message.contains("отмен", ignoreCase = true)) {
                DirectVpnService.serverFailed = false
                val failedServer = lastConnectedServer
                val servers = _servers.value
                val next = servers?.firstOrNull { it.isReachable && it.raw != failedServer?.raw }
                    ?: servers?.firstOrNull { it.raw != failedServer?.raw }
                if (next != null) {
                    ConnectionLog.i("Авто-переключение: ${failedServer?.name} → ${next.name}")
                    viewModelScope.launch {
                        delay(2000)
                        connectToServer(next)
                    }
                } else {
                    ConnectionLog.w("Авто-переключение: нет альтернативных серверов")
                    _uiState.postValue(UiState.Error("Нет доступных серверов для переподключения"))
                }
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
        val subs = repo.getSubscriptions()
        _subscriptions.value = subs
        val cached = repo.getAllCachedServers()
        _servers.value = cached
        _bestServer.value = cached.firstOrNull { it.isReachable }
        rebuildServerListItems(subs, cached)
        updateLastUpdateText()
        rescheduleWork()
    }

    /**
     * Перестраивает единый список элементов для RecyclerView:
     * 1. По каждой подписке — заголовок (expandable) + не пингованные серверы.
     * 2. Внизу — раздел «Пропингованные», отсортированный по latency.
     */
    fun rebuildServerListItems(
        subs: List<Subscription> = _subscriptions.value ?: emptyList(),
        servers: List<ServerConfig> = _servers.value ?: emptyList()
    ) {
        val items = mutableListOf<ServerListItem>()

        // Пропингованные серверы (isReachable = true)
        val pinged = servers.filter { it.isReachable }.sortedBy { it.pingMs }
        // Не пингованные — разбиваем по подпискам
        val unpinged = servers.filter { !it.isReachable }

        // Группы по подпискам (только включённые подписки)
        subs.filter { it.isEnabled }.forEach { sub ->
            val subUnpinged = unpinged.filter { it.subId == sub.id }
            // Серверы без subId (старый кэш) → тоже включаем в первую попавшуюся подписку
            val isExpanded = expandedGroups[sub.id] ?: false
            items.add(ServerListItem.SubHeader(sub, subUnpinged.size, isExpanded))
            if (isExpanded) {
                subUnpinged.forEach { items.add(ServerListItem.SubServer(it, sub.id)) }
            }
        }

        // Раздел пропингованных
        if (pinged.isNotEmpty()) {
            items.add(ServerListItem.PingedHeader)
            pinged.forEach { items.add(ServerListItem.PingedServer(it)) }
        }

        _serverListItems.value = items
    }

    /** Переключить развёрнутость группы подписки. */
    fun toggleGroup(subId: String) {
        expandedGroups[subId] = !(expandedGroups[subId] ?: false)
        rebuildServerListItems()
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

                // Сохраняем результаты пинга обратно по подпискам для persistence
                val bySubId = tested.groupBy { it.subId }
                bySubId.forEach { (subId, servers) ->
                    if (subId.isNotEmpty()) repo.saveServers(subId, servers)
                }
                // Серверы без subId (старый кэш) сохраняются as-is

                _servers.postValue(tested)
                val best = tested.firstOrNull { it.isReachable }
                _bestServer.postValue(best)
                repo.lastUpdateTime = System.currentTimeMillis()
                updateLastUpdateText()
                val subs = repo.getSubscriptions()
                _subscriptions.postValue(subs)
                rebuildServerListItems(subs, tested)

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
        val server = _bestServer.value ?: _servers.value?.firstOrNull { it.isReachable }
            ?: _servers.value?.firstOrNull() ?: run {
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
        repo.updateSubscription(sub.copy(isEnabled = enabled))
        val subs = repo.getSubscriptions()
        _subscriptions.value = subs
        rebuildServerListItems(subs)
    }

    fun addCustomSubscription(url: String, name: String) {
        repo.updateSubscription(Subscription(
            id = "custom_${System.currentTimeMillis()}", name = name.ifEmpty { "Своя подписка" },
            url = url, type = com.vpnauto.manager.model.SubscriptionType.CUSTOM, isEnabled = true))
        val subs = repo.getSubscriptions()
        _subscriptions.value = subs
        rebuildServerListItems(subs)
    }

    /** Удалить подписку (вместе с кэшем серверов). */
    fun removeSubscription(subId: String) {
        repo.removeSubscription(subId)
        expandedGroups.remove(subId)
        val subs = repo.getSubscriptions()
        val servers = _servers.value?.filter { it.subId != subId } ?: emptyList()
        _subscriptions.value = subs
        _servers.value = servers
        _bestServer.value = servers.firstOrNull { it.isReachable }
        rebuildServerListItems(subs, servers)
    }

    /** Очистить кэш серверов подписки (не удаляя саму подписку). */
    fun clearSubServers(subId: String) {
        repo.clearCachedServers(subId)
        val subs = repo.getSubscriptions()
        // Сбрасываем serverCount в метаданных подписки
        subs.find { it.id == subId }?.let { sub ->
            repo.updateSubscription(sub.copy(serverCount = 0, lastUpdated = 0L))
        }
        val updatedSubs = repo.getSubscriptions()
        val servers = _servers.value?.filter { it.subId != subId } ?: emptyList()
        _subscriptions.value = updatedSubs
        _servers.value = servers
        _bestServer.value = servers.firstOrNull { it.isReachable }
        rebuildServerListItems(updatedSubs, servers)
    }

    /** Очистить все кэши серверов (списки обнуляются, подписки остаются). */
    fun clearAllServers() {
        repo.clearAllCachedServers()
        _servers.value = emptyList()
        _bestServer.value = null
        // Сбрасываем счётчики серверов
        val subs = repo.getSubscriptions().map { it.copy(serverCount = 0, lastUpdated = 0L) }
        subs.forEach { repo.updateSubscription(it) }
        val updatedSubs = repo.getSubscriptions()
        _subscriptions.value = updatedSubs
        rebuildServerListItems(updatedSubs, emptyList())
    }

    /** Удалить все пользовательские (CUSTOM) подписки. */
    fun removeAllCustomSubscriptions() {
        repo.removeAllCustomSubscriptions()
        val subs = repo.getSubscriptions()
        val servers = _servers.value?.filter { it.subId.isNotEmpty() && subs.any { s -> s.id == it.subId } } ?: emptyList()
        _subscriptions.value = subs
        _servers.value = servers
        _bestServer.value = servers.firstOrNull { it.isReachable }
        rebuildServerListItems(subs, servers)
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
