package com.vpnauto.manager.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.FragmentSettingsFullBinding
import com.vpnauto.manager.service.FileLogger
import com.vpnauto.manager.service.NetworkAction
import com.vpnauto.manager.service.NetworkRules
import com.vpnauto.manager.service.ProfileManager
import com.vpnauto.manager.service.SplitTunneling
import com.vpnauto.manager.service.VpnProfile

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsFullBinding? = null
    private val b get() = _b!!
    private val vm: MainViewModel by activityViewModels()
    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsFullBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)

        // Kill Switch
        b.switchKillSwitch.isChecked = vm.killSwitchEnabled
        b.switchKillSwitch.setOnCheckedChangeListener { _, v -> vm.killSwitchEnabled = v }

        // Auto-reconnect
        b.switchAutoReconnect.isChecked = vm.autoReconnect
        b.switchAutoReconnect.setOnCheckedChangeListener { _, v -> vm.autoReconnect = v }

        // Split tunneling
        b.switchSplitTunnel.isChecked = SplitTunneling.isEnabled()
        b.switchSplitTunnel.setOnCheckedChangeListener { _, v ->
            SplitTunneling.setEnabled(v)
            b.btnConfigureSplit.isEnabled = v
        }
        b.btnConfigureSplit.isEnabled = SplitTunneling.isEnabled()
        b.btnConfigureSplit.setOnClickListener { showSplitTunnelingDialog() }

        // Профили
        profileAdapter = ProfileAdapter(
            onActivate = { p ->
                ProfileManager.setActive(p.id)
                vm.applyProfile(p)
                Toast.makeText(requireContext(), "Профиль: ${p.name}", Toast.LENGTH_SHORT).show()
                profileAdapter.submitList(ProfileManager.getProfiles().toList())
            },
            onDelete = { p ->
                ProfileManager.deleteProfile(p.id)
                profileAdapter.submitList(ProfileManager.getProfiles().toList())
            },
            activeId = ProfileManager.getActive()?.id ?: ""
        )
        b.rvProfiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = profileAdapter
        }
        profileAdapter.submitList(ProfileManager.getProfiles())
        b.btnAddProfile.setOnClickListener { showAddProfileDialog() }

        // Правила сети
        b.switchNetworkRules.isChecked = NetworkRules.isEnabled()
        b.switchNetworkRules.setOnCheckedChangeListener { _, v ->
            NetworkRules.setEnabled(v)
            if (v) vm.registerNetworkRules() else vm.unregisterNetworkRules()
        }
        b.btnAutoOnMobile.setOnClickListener {
            addNetworkRule("mobile", "📱 Мобильная сеть", NetworkAction.CONNECT)
        }
        b.btnAutoOnWifi.setOnClickListener {
            addNetworkRule("wifi", "📶 WiFi", NetworkAction.CONNECT)
        }

        // Интервал обновления
        val intervals = listOf("1 час", "2 часа", "3 часа", "6 часов", "12 часов", "24 часа")
        val intervalVals = listOf(1, 2, 3, 6, 12, 24)
        b.spinnerInterval.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, intervals).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        b.spinnerInterval.setSelection(intervalVals.indexOf(vm.updateIntervalHours).coerceAtLeast(0))
        b.spinnerInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                vm.updateIntervalHours = intervalVals[pos]
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        b.switchPingOnUpdate.isChecked = vm.pingOnUpdate
        b.switchPingOnUpdate.setOnCheckedChangeListener { _, v -> vm.pingOnUpdate = v }

        // Диагностика: файловый лог
        b.switchFileLogging.isChecked = vm.fileLoggingEnabled
        b.switchFileLogging.setOnCheckedChangeListener { _, v -> vm.fileLoggingEnabled = v }

        // Авто-подключение при запуске
        b.switchAutoConnectOnLaunch.isChecked = vm.autoConnectOnLaunch
        b.switchAutoConnectOnLaunch.setOnCheckedChangeListener { _, v -> vm.autoConnectOnLaunch = v }

        // Поделиться логом
        b.btnShareLog.setOnClickListener { shareLogFile() }
    }

    private fun shareLogFile() {
        val file = FileLogger.getLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), "Файл лога не найден", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VPN Guard — лог")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Отправить лог"))
    }

    private fun showSplitTunnelingDialog() {
        val apps = SplitTunneling.getInstalledApps(requireContext())
            .filter { !it.isSystem }
        val names  = apps.map { it.appName }.toTypedArray()
        val bypass = apps.map { it.isBypass }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("✂️ Приложения мимо VPN")
            .setMultiChoiceItems(names, bypass) { _, which, checked ->
                SplitTunneling.setBypass(apps[which].packageName, checked)
            }
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun showAddProfileDialog() {
        val icons = listOf("🌐","💼","⚡","🔒","🏠","🎮","📺")
        val view = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val etName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSubUrl)
        etName.hint = "Название профиля"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Новый профиль")
            .setView(view)
            .setPositiveButton("Создать") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "Профиль" }
                val p = VpnProfile(
                    id = "custom_${System.currentTimeMillis()}",
                    name = name,
                    subscriptionIds = listOf("black_vless_mobile")
                )
                ProfileManager.addProfile(p)
                profileAdapter.submitList(ProfileManager.getProfiles().toList())
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun addNetworkRule(type: String, name: String, action: NetworkAction) {
        val rules = NetworkRules.getRules().toMutableList()
        val existing = rules.indexOfFirst { it.networkType == type && it.ssid.isEmpty() }
        val rule = com.vpnauto.manager.service.NetworkRule(
            id = "${type}_auto", name = name,
            ssid = "", networkType = type, action = action
        )
        if (existing >= 0) rules[existing] = rule else rules.add(rule)
        NetworkRules.saveRules(rules)
        Toast.makeText(requireContext(), "Правило добавлено: $name → VPN", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class ProfileAdapter(
    private val onActivate: (VpnProfile) -> Unit,
    private val onDelete: (VpnProfile) -> Unit,
    private var activeId: String
) : ListAdapter<VpnProfile, ProfileAdapter.VH>(Diff) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:   TextView = v.findViewById(R.id.tvSubName)
        val tvDesc:   TextView = v.findViewById(R.id.tvSubUrl)
        val btnImport = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSubImport)
        val toggle    = v.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSubEnabled)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_subscription, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val profile = getItem(pos)
        h.tvName.text   = "${profile.icon} ${profile.name}"
        h.tvDesc.text   = "${profile.subscriptionIds.size} подписок"
        h.toggle.setOnCheckedChangeListener(null)
        h.toggle.isChecked = profile.id == activeId
        h.toggle.setOnCheckedChangeListener { _, v -> if (v) { activeId = profile.id; onActivate(profile) } }
        h.btnImport.text = "🗑"
        h.btnImport.setOnClickListener { onDelete(profile) }
    }

    companion object Diff : DiffUtil.ItemCallback<VpnProfile>() {
        override fun areItemsTheSame(a: VpnProfile, b: VpnProfile) = a.id == b.id
        override fun areContentsTheSame(a: VpnProfile, b: VpnProfile) = a == b
    }
}
