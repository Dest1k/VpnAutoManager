package com.vpnauto.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.ActivityMainBinding
import com.vpnauto.manager.model.Subscription
import com.vpnauto.manager.service.DirectVpnService
import com.vpnauto.manager.service.LocalProxyServer
import com.vpnauto.manager.service.QrCodeGenerator
import com.vpnauto.manager.service.SpeedTest
import com.vpnauto.manager.util.PingTester
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainFragment : Fragment() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var subscriptionAdapter: SubscriptionAdapter
    private var pingAllJob: Job? = null
    private var speedTestAllJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = ActivityMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        serverAdapter = ServerAdapter(
            onConnect = { server -> viewModel.connectToServer(server) },
            onPing = { }
        )
        binding.rvServers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = serverAdapter
        }
        subscriptionAdapter = SubscriptionAdapter(
            onToggle    = { sub: Subscription, enabled: Boolean -> viewModel.toggleSubscription(sub, enabled) },
            onImport    = { sub: Subscription -> viewModel.importSubscriptionToV2Ray(sub) },
            onPing      = { sub: Subscription, holder: SubscriptionAdapter.ViewHolder -> pingSubscription(sub, holder) },
            onSpeedTest = { sub: Subscription, holder: SubscriptionAdapter.ViewHolder -> speedTestSubscription(sub, holder) }
        )
        binding.rvSubscriptions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = subscriptionAdapter
        }
    }

    private fun setupButtons() {
        binding.btnUpdateNow.setOnClickListener { viewModel.updateNow() }
        binding.btnCancelUpdate.setOnClickListener { viewModel.cancelUpdate() }
        binding.btnCancelConnect.setOnClickListener { viewModel.cancelConnect() }

        binding.btnConnectBest.setOnClickListener {
            when {
                DirectVpnService.isRunning    -> viewModel.disconnect()
                DirectVpnService.isConnecting -> { }
                else                          -> viewModel.connectToBest()
            }
        }

        binding.switchHotspot.setOnCheckedChangeListener { _, checked ->
            if (checked && !DirectVpnService.isRunning) {
                binding.switchHotspot.isChecked = false
                showSnackbar("Сначала подключите VPN", isError = true)
                return@setOnCheckedChangeListener
            }
            viewModel.toggleHotspotShare(checked)
        }

        binding.btnHotspotQr.setOnClickListener {
            val address = viewModel.hotspotAddress.value ?: return@setOnClickListener
            showHotspotQrDialog(address)
        }

        binding.btnOpenV2Ray.setOnClickListener {
            if (viewModel.isV2RayInstalled) viewModel.openV2Ray()
            else showInstallV2RayDialog()
        }

        binding.btnImportAllToV2Ray.setOnClickListener {
            if (!viewModel.isV2RayInstalled) { showInstallV2RayDialog(); return@setOnClickListener }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Импорт в v2rayNG")
                .setMessage("Все включённые подписки будут добавлены в v2rayNG. Продолжить?")
                .setPositiveButton("Добавить") { _, _ -> viewModel.importAllToV2Ray() }
                .setNegativeButton("Отмена", null).show()
        }

        binding.btnAddCustomSub.setOnClickListener { showAddCustomSubscriptionDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnPingAllSubs.setOnClickListener { pingAllSubscriptions() }
        binding.btnSpeedTestAllSubs.setOnClickListener { speedTestAllSubscriptions() }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is UiState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.tvStatus.text = state.message
                    binding.btnUpdateNow.isEnabled = false
                }
                is UiState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = state.message
                    binding.btnUpdateNow.isEnabled = true
                    showSnackbar(state.message)
                }
                is UiState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = state.message
                    binding.btnUpdateNow.isEnabled = true
                    showSnackbar(state.message, isError = true)
                }
                is UiState.Idle -> {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Готов к работе"
                    binding.btnUpdateNow.isEnabled = true
                }
            }
        }

        viewModel.isUpdating.observe(viewLifecycleOwner) { updating ->
            binding.btnCancelUpdate.visibility = if (updating) View.VISIBLE else View.GONE
            binding.btnUpdateNow.text = if (updating) "⏳ Обновление..." else "🔄 Обновить"
        }

        viewModel.vpnState.observe(viewLifecycleOwner) { state ->
            when (state) {
                VpnConnState.DISCONNECTED -> {
                    binding.btnConnectBest.text = "⚡ Подключить"
                    binding.btnConnectBest.isEnabled = true
                    binding.btnCancelConnect.visibility = View.GONE
                    binding.cardVpnStatus.setCardBackgroundColor(requireContext().getColor(R.color.vpn_disconnected))
                    binding.tvVpnStatus.text = "Отключено"
                    binding.switchHotspot.isChecked = false
                }
                VpnConnState.CONNECTING -> {
                    binding.btnConnectBest.text = "⏳ Подключение..."
                    binding.btnConnectBest.isEnabled = false
                    binding.btnCancelConnect.visibility = View.VISIBLE
                    binding.cardVpnStatus.setCardBackgroundColor(requireContext().getColor(R.color.vpn_connecting))
                    binding.tvVpnStatus.text = "Подключение..."
                }
                VpnConnState.CONNECTED -> {
                    binding.btnConnectBest.text = "🔴 Отключить"
                    binding.btnConnectBest.isEnabled = true
                    binding.btnCancelConnect.visibility = View.GONE
                    binding.cardVpnStatus.setCardBackgroundColor(requireContext().getColor(R.color.vpn_connected))
                    binding.tvVpnStatus.text = "✅ Подключено"
                }
            }
        }

        viewModel.vpnMessage.observe(viewLifecycleOwner) { msg ->
            if (msg.isNotEmpty()) binding.tvVpnServer.text = msg
        }

        viewModel.hotspotActive.observe(viewLifecycleOwner) { active ->
            binding.switchHotspot.setOnCheckedChangeListener(null)
            binding.switchHotspot.isChecked = active
            binding.switchHotspot.setOnCheckedChangeListener { _, checked ->
                if (checked && !DirectVpnService.isRunning) {
                    binding.switchHotspot.isChecked = false
                    showSnackbar("Сначала подключите VPN", isError = true)
                    return@setOnCheckedChangeListener
                }
                viewModel.toggleHotspotShare(checked)
            }
            binding.tvHotspotStatus.text = if (active) "✅ Раздача активна" else "Выключена"
            binding.tvHotspotAddress.text = if (active && viewModel.hotspotAddress.value?.isNotEmpty() == true)
                "SOCKS5: ${viewModel.hotspotAddress.value}" else ""
            binding.btnHotspotQr.visibility = if (active) View.VISIBLE else View.GONE
        }

        viewModel.hotspotAddress.observe(viewLifecycleOwner) { address ->
            binding.tvHotspotAddress.text = if (address.isNotEmpty()) "SOCKS5: $address" else ""
        }

        viewModel.servers.observe(viewLifecycleOwner) { servers ->
            serverAdapter.submitList(servers.take(100))
            binding.tvServerCount.text = "${servers.size} серверов"
            binding.tvReachableCount.text = "${servers.count { it.isReachable }} доступных"
        }

        viewModel.bestServer.observe(viewLifecycleOwner) { server ->
            if (server != null) {
                binding.cardBestServer.visibility = View.VISIBLE
                binding.tvBestServerName.text = server.name
                binding.tvBestServerPing.text = "${server.pingMs}ms"
                binding.tvBestServerHost.text = "${server.host}:${server.port}"
                binding.tvBestServerProtocol.text = server.protocol.uppercase()
            } else {
                binding.cardBestServer.visibility = View.GONE
            }
        }

        viewModel.subscriptions.observe(viewLifecycleOwner) { subscriptionAdapter.submitList(it) }
        viewModel.lastUpdateText.observe(viewLifecycleOwner) { binding.tvLastUpdate.text = "Обновлено: $it" }
    }

    private fun showHotspotQrDialog(address: String) {
        val host = address.substringBefore(':')
        val port = address.substringAfter(':').toIntOrNull() ?: LocalProxyServer.PROXY_PORT
        val proxyUrl = QrCodeGenerator.buildProxyUrl(host, port)
        val view = layoutInflater.inflate(R.layout.dialog_hotspot_qr, null)
        view.findViewById<android.widget.TextView>(R.id.tvProxyHost).text = host
        view.findViewById<android.widget.TextView>(R.id.tvProxyPort).text = port.toString()
        val ivQr = view.findViewById<android.widget.ImageView>(R.id.ivQrCode)
        val qr = QrCodeGenerator.generate(proxyUrl)
        if (qr != null) ivQr.setImageBitmap(qr) else ivQr.visibility = View.GONE
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("📶 Подключение клиентов").setView(view)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun showInstallV2RayDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("v2rayNG не установлен")
            .setMessage("Для импорта конфигов установите v2rayNG. Открыть страницу загрузки?")
            .setPositiveButton("Установить") { _, _ -> viewModel.installV2Ray() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showAddCustomSubscriptionDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val etUrl  = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSubUrl)
        val etName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSubName)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Добавить подписку").setView(view)
            .setPositiveButton("Добавить") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (url.startsWith("http")) viewModel.addCustomSubscription(url, etName.text.toString().trim())
                else showSnackbar("Введите корректный URL", isError = true)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val spinnerInterval   = view.findViewById<android.widget.Spinner>(R.id.spinnerInterval)
        val switchAutoConnect = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoConnect)
        val switchPing        = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchPingOnUpdate)
        val intervals = listOf("1 час", "2 часа", "3 часа", "6 часов", "12 часов", "24 часа")
        val intervalValues = listOf(1, 2, 3, 6, 12, 24)
        spinnerInterval.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, intervals)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerInterval.setSelection(intervalValues.indexOf(viewModel.updateIntervalHours).coerceAtLeast(0))
        switchAutoConnect.isChecked = viewModel.autoConnect
        switchPing.isChecked = viewModel.pingOnUpdate
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Настройки").setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                viewModel.updateIntervalHours = intervalValues[spinnerInterval.selectedItemPosition]
                viewModel.autoConnect = switchAutoConnect.isChecked
                viewModel.pingOnUpdate = switchPing.isChecked
                showSnackbar("Настройки сохранены")
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ─── Пинг-тест подписок ──────────────────────────────────────

    private fun pingSubscription(sub: Subscription, holder: SubscriptionAdapter.ViewHolder) {
        viewLifecycleOwner.lifecycleScope.launch {
            holder.tvPingResult.visibility = View.VISIBLE
            holder.tvPingResult.text = "⏳ загрузка..."
            holder.btnPing.isEnabled = false
            try {
                var servers = viewModel.getCachedServers(sub.id)
                if (servers.isEmpty()) {
                    holder.tvPingResult.text = "⏳ скачиваем серверы..."
                    servers = viewModel.fetchServersForSub(sub) ?: emptyList()
                }

                if (servers.isEmpty()) {
                    holder.tvPingResult.text = "⚠️ нет серверов"
                    return@launch
                }

                holder.tvPingResult.text = "⏳ пинг 0/${servers.size}..."
                val tested = PingTester.testServers(servers) { done, total ->
                    if (isActive) holder.tvPingResult.text = "⏳ пинг $done/$total..."
                }

                val reachable = tested.count { it.isReachable }
                val best = tested.firstOrNull { it.isReachable }
                val resultText = if (best != null)
                    "✅ $reachable/${tested.size} · лучший: ${best.pingMs}ms"
                else
                    "❌ нет доступных (${tested.size} проверено)"
                holder.tvPingResult.text = resultText
                subscriptionAdapter.setPingResult(sub.id, resultText)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // пробрасываем отмену
            } catch (e: Exception) {
                val errText = "❌ ошибка: ${e.message?.take(40)}"
                holder.tvPingResult.text = errText
                subscriptionAdapter.setPingResult(sub.id, errText)
            } finally {
                holder.btnPing.isEnabled = true
            }
        }
    }

    private fun pingAllSubscriptions() {
        if (pingAllJob?.isActive == true) {
            pingAllJob?.cancel()
            pingAllJob = null
            binding.btnPingAllSubs.text = "📡 Пинг"
            return
        }
        val subs = viewModel.subscriptions.value?.filter { it.isEnabled } ?: return
        if (subs.isEmpty()) { showSnackbar("Нет включённых подписок"); return }

        binding.btnPingAllSubs.text = "✕ Стоп"
        pingAllJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                for (sub in subs) {
                    if (!isActive) break
                    subscriptionAdapter.setPingResult(sub.id, "⏳ загрузка...")
                    var servers = viewModel.getCachedServers(sub.id)
                    if (servers.isEmpty()) {
                        subscriptionAdapter.setPingResult(sub.id, "⏳ скачиваем...")
                        servers = viewModel.fetchServersForSub(sub) ?: emptyList()
                    }
                    if (servers.isEmpty()) {
                        subscriptionAdapter.setPingResult(sub.id, "⚠️ нет серверов")
                        continue
                    }
                    subscriptionAdapter.setPingResult(sub.id, "⏳ пинг 0/${servers.size}...")
                    val tested = PingTester.testServers(servers) { done, total ->
                        if (isActive) subscriptionAdapter.setPingResult(sub.id, "⏳ пинг $done/$total...")
                    }
                    val reachable = tested.count { it.isReachable }
                    val best = tested.firstOrNull { it.isReachable }
                    val resultText = if (best != null)
                        "✅ $reachable/${tested.size} · лучший: ${best.pingMs}ms"
                    else
                        "❌ нет доступных (${tested.size} проверено)"
                    subscriptionAdapter.setPingResult(sub.id, resultText)
                }
            } finally {
                binding.btnPingAllSubs.text = "📡 Пинг"
                pingAllJob = null
            }
        }
    }

    // ─── Тест скорости одной подписки ───────────────────────────

    private fun speedTestSubscription(sub: Subscription, holder: SubscriptionAdapter.ViewHolder) {
        viewLifecycleOwner.lifecycleScope.launch {
            holder.tvPingResult.visibility = View.VISIBLE
            holder.tvPingResult.text = "⏳ подготовка..."
            holder.btnPing.isEnabled = false
            holder.btnSpeedTest?.isEnabled = false
            try {
                val result = runSpeedTestForSub(sub) { msg ->
                    if (isActive) holder.tvPingResult.text = msg
                }
                holder.tvPingResult.text = result
                subscriptionAdapter.setPingResult(sub.id, result)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val errText = "❌ ошибка: ${e.message?.take(40)}"
                holder.tvPingResult.text = errText
                subscriptionAdapter.setPingResult(sub.id, errText)
            } finally {
                holder.btnPing.isEnabled = true
                holder.btnSpeedTest?.isEnabled = true
            }
        }
    }

    private fun speedTestAllSubscriptions() {
        if (speedTestAllJob?.isActive == true) {
            speedTestAllJob?.cancel()
            speedTestAllJob = null
            binding.btnSpeedTestAllSubs.text = "📊 Скорость"
            return
        }
        val subs = viewModel.subscriptions.value?.filter { it.isEnabled } ?: return
        if (subs.isEmpty()) { showSnackbar("Нет включённых подписок"); return }

        binding.btnSpeedTestAllSubs.text = "✕ Стоп"
        speedTestAllJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                for (sub in subs) {
                    if (!isActive) break
                    subscriptionAdapter.setPingResult(sub.id, "⏳ подготовка...")
                    val result = runSpeedTestForSub(sub) { msg ->
                        subscriptionAdapter.setPingResult(sub.id, msg)
                    }
                    subscriptionAdapter.setPingResult(sub.id, result)
                }
                showSnackbar("Тест скорости завершён")
            } finally {
                binding.btnSpeedTestAllSubs.text = "📊 Скорость"
                speedTestAllJob = null
            }
        }
    }

    /**
     * Общая логика теста скорости для одной подписки:
     * 1. Загружает серверы (из кэша или по сети)
     * 2. Пингует все серверы
     * 3. Подключается к лучшему серверу
     * 4. Запускает SpeedTest через SOCKS5 xray
     * 5. Отключается (если до этого не было подключения)
     * Возвращает строку с результатом для отображения.
     */
    private suspend fun runSpeedTestForSub(
        sub: Subscription,
        onStatus: (String) -> Unit
    ): String {
        // 1. Серверы
        onStatus("⏳ скачиваем серверы...")
        var servers = viewModel.getCachedServers(sub.id)
        if (servers.isEmpty()) {
            servers = viewModel.fetchServersForSub(sub) ?: emptyList()
        }
        if (servers.isEmpty()) return "⚠️ нет серверов"

        // 2. Пинг
        onStatus("⏳ пинг 0/${servers.size}...")
        val tested = PingTester.testServers(servers) { done, total ->
            onStatus("⏳ пинг $done/$total...")
        }
        val best = tested.firstOrNull { it.isReachable } ?: return "❌ нет доступных серверов"

        // 3. Подключение
        val wasRunning = DirectVpnService.isRunning
        onStatus("⏳ подключение к ${best.name}...")
        viewModel.connectToServer(best)

        // Небольшая пауза — даём сервису начать подключение
        delay(400)
        if (!DirectVpnService.isConnecting && !DirectVpnService.isRunning) {
            return "⚠️ требуется разрешение VPN — предоставьте его и повторите"
        }

        // 4. Ждём подключения (до 35с)
        val connected = withTimeoutOrNull(35_000L) {
            while (!DirectVpnService.isRunning) {
                if (!DirectVpnService.isConnecting) return@withTimeoutOrNull false
                delay(400)
            }
            true
        } ?: false

        if (!connected) {
            return "❌ не удалось подключиться к ${best.name}"
        }

        // 5. Тест скорости
        val speedResult = SpeedTest.run { msg -> onStatus("⏳ $msg") }

        val pingText = "${best.pingMs}мс"
        val result = if (speedResult != null && speedResult.downloadMbps > 0)
            "✅ 📡 $pingText · ⬇ ${String.format("%.1f", speedResult.downloadMbps)} · ⬆ ${String.format("%.1f", speedResult.uploadMbps)} Мбит/с"
        else
            "✅ 📡 $pingText (скорость н/д)"

        // 6. Отключаемся, только если подключались специально для теста
        if (!wasRunning) {
            viewModel.disconnect()
            delay(1500)
        }

        return result
    }

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val s = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (isError) s.setBackgroundTint(requireContext().getColor(R.color.error_color))
        s.show()
    }

    override fun onDestroyView() {
        pingAllJob?.cancel()
        speedTestAllJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
