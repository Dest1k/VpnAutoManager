package com.vpnauto.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.ActivityMainBinding
import com.vpnauto.manager.model.Subscription
import com.vpnauto.manager.service.DirectVpnService
import com.vpnauto.manager.service.LocalProxyServer
import com.vpnauto.manager.service.QrCodeGenerator

class MainFragment : Fragment() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var subscriptionAdapter: SubscriptionAdapter

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
            onToggle = { sub: Subscription, enabled: Boolean -> viewModel.toggleSubscription(sub, enabled) },
            onImport = { sub: Subscription -> viewModel.importSubscriptionToV2Ray(sub) }
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

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val s = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (isError) s.setBackgroundTint(requireContext().getColor(R.color.error_color))
        s.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
