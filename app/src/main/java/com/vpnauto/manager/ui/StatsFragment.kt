package com.vpnauto.manager.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vpnauto.manager.R
import com.vpnauto.manager.databinding.FragmentStatsBinding
import com.vpnauto.manager.service.ConnectionHistory
import com.vpnauto.manager.service.ConnectionRecord
import com.vpnauto.manager.service.DirectVpnService
import com.vpnauto.manager.service.SpeedTest
import com.vpnauto.manager.service.TrafficMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    private var _b: FragmentStatsBinding? = null
    private val b get() = _b!!
    private val vm: MainViewModel by activityViewModels()
    private lateinit var historyAdapter: HistoryAdapter
    private var speedTestJob: Job? = null
    private var sessionStartTime = 0L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentStatsBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        historyAdapter = HistoryAdapter()
        b.rvHistory.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
        b.btnClearHistory.setOnClickListener {
            ConnectionHistory.clear()
            historyAdapter.submitList(emptyList())
        }
        b.btnSpeedTest.setOnClickListener { runSpeedTest() }
        b.btnStopSpeedTest.setOnClickListener { stopSpeedTest() }

        // Трафик
        vm.trafficStats.observe(viewLifecycleOwner) { snap ->
            if (snap == null) return@observe
            b.tvDownSpeed.text = TrafficMonitor.formatSpeed(snap.rxSpeedBps)
            b.tvUpSpeed.text   = TrafficMonitor.formatSpeed(snap.txSpeedBps)
            b.tvTotalRx.text   = TrafficMonitor.formatBytes(snap.rxBytes)
            b.tvTotalTx.text   = TrafficMonitor.formatBytes(snap.txBytes)
        }
        vm.watchdogPing.observe(viewLifecycleOwner) { ping ->
            b.tvPingValue.text = if (ping >= 0) "$ping" else "—"
        }
        vm.vpnState.observe(viewLifecycleOwner) { state ->
            if (state == VpnConnState.CONNECTED && sessionStartTime == 0L) {
                sessionStartTime = System.currentTimeMillis()
            } else if (state == VpnConnState.DISCONNECTED) {
                sessionStartTime = 0L
                b.tvSessionTime.text = "—"
            }
        }
        loadHistory()
        startSessionTimer()
    }

    private fun startSessionTimer() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                if (sessionStartTime > 0) {
                    val sec = (System.currentTimeMillis() - sessionStartTime) / 1000
                    b.tvSessionTime.text = when {
                        sec < 60   -> "${sec}с"
                        sec < 3600 -> "${sec/60}м ${sec%60}с"
                        else       -> "${sec/3600}ч ${(sec%3600)/60}м"
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun runSpeedTest() {
        if (!DirectVpnService.isRunning) {
            b.tvSpeedTestResult.text = "⚠️ Сначала подключитесь к VPN"
            return
        }
        speedTestJob?.cancel()
        b.btnSpeedTest.isEnabled = false
        b.btnStopSpeedTest.visibility = View.VISIBLE
        b.tvSpeedTestResult.text = "⏳ Запускаем тест..."
        speedTestJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = SpeedTest.run { msg ->
                    b.tvSpeedTestResult.text = "⏳ $msg"
                }
                if (result == null) {
                    b.tvSpeedTestResult.text = "⚠️ Подключитесь к VPN перед тестом"
                    return@launch
                }
                b.tvSpeedTestResult.text = buildString {
                    appendLine("📥 Загрузка: ${String.format("%.1f", result.downloadMbps)} Мбит/с")
                    appendLine("📤 Отдача:   ${String.format("%.1f", result.uploadMbps)} Мбит/с")
                    append("📡 Пинг:     ${result.pingMs} мс")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // обработано в stopSpeedTest()
            } finally {
                b.btnSpeedTest.isEnabled = true
                b.btnStopSpeedTest.visibility = View.GONE
            }
        }
    }

    private fun stopSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
        b.btnSpeedTest.isEnabled = true
        b.btnStopSpeedTest.visibility = View.GONE
        b.tvSpeedTestResult.text = "Тест остановлен"
    }

    private fun loadHistory() {
        historyAdapter.submitList(ConnectionHistory.getAll())
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
        if (DirectVpnService.isRunning && sessionStartTime == 0L)
            sessionStartTime = System.currentTimeMillis()
    }

    override fun onDestroyView() {
        speedTestJob?.cancel()
        super.onDestroyView()
        _b = null
    }
}

class HistoryAdapter : ListAdapter<ConnectionRecord, HistoryAdapter.VH>(Diff) {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName:     TextView = v.findViewById(R.id.tvHistName)
        val tvTime:     TextView = v.findViewById(R.id.tvHistTime)
        val tvDuration: TextView = v.findViewById(R.id.tvHistDuration)
        val tvTraffic:  TextView = v.findViewById(R.id.tvHistTraffic)
        val tvStatus:   TextView = v.findViewById(R.id.tvHistStatus)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_history, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.tvName.text     = r.serverName.take(30)
        h.tvTime.text     = r.connectedAtFormatted
        h.tvDuration.text = r.durationFormatted
        h.tvTraffic.text  = if (r.rxBytes > 0)
            "⬇${TrafficMonitor.formatBytes(r.rxBytes)} ⬆${TrafficMonitor.formatBytes(r.txBytes)}"
        else ""
        h.tvStatus.text   = if (r.success) "✅" else "❌ ${r.failReason.take(20)}"
    }
    companion object Diff : DiffUtil.ItemCallback<ConnectionRecord>() {
        override fun areItemsTheSame(a: ConnectionRecord, b: ConnectionRecord) =
            a.connectedAt == b.connectedAt
        override fun areContentsTheSame(a: ConnectionRecord, b: ConnectionRecord) = a == b
    }
}
