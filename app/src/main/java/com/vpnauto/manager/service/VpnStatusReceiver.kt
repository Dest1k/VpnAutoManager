package com.vpnauto.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vpnauto.manager.service.VpnWidget

class VpnStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_VPN_STATUS -> {
                VpnWidget.refreshAll(context)
                VpnStatusBus.post(
                    connected = intent.getBooleanExtra("connected", false),
                    message   = intent.getStringExtra("message") ?: ""
                )
            }
            ACTION_HOTSPOT_STATUS -> {
                HotspotStatusBus.post(
                    active  = intent.getBooleanExtra("active", false),
                    address = intent.getStringExtra("address") ?: ""
                )
            }
        }
    }
}

object VpnStatusBus {
    private val listeners = mutableListOf<(Boolean, String) -> Unit>()
    fun subscribe(l: (Boolean, String) -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun unsubscribe(l: (Boolean, String) -> Unit) = synchronized(listeners) { listeners.remove(l) }
    fun post(connected: Boolean, message: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it(connected, message) }
}

object HotspotStatusBus {
    private val listeners = mutableListOf<(Boolean, String) -> Unit>()
    fun subscribe(l: (Boolean, String) -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun unsubscribe(l: (Boolean, String) -> Unit) = synchronized(listeners) { listeners.remove(l) }
    fun post(active: Boolean, address: String) =
        synchronized(listeners) { listeners.toList() }.forEach { it(active, address) }
}
