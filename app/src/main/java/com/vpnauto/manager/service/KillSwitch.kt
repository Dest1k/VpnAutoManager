package com.vpnauto.manager.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

private const val TAG = "KillSwitch"

/**
 * Kill Switch — блокирует весь интернет-трафик если VPN упал.
 * Реализован через VpnService.Builder — при создании TUN-интерфейса
 * весь трафик заворачивается в него, и если сервис останавливается —
 * Android автоматически блокирует трафик пока allowBypass=false.
 *
 * Дополнительно: следим за состоянием сети через NetworkCallback.
 */
object KillSwitch {

    @Volatile var isEnabled = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun enable(context: Context, onNetworkLost: () -> Unit) {
        isEnabled = true
        ConnectionLog.ok("Kill Switch включён")
        registerNetworkCallback(context, onNetworkLost)
    }

    fun disable(context: Context) {
        isEnabled = false
        unregisterNetworkCallback(context)
        ConnectionLog.i("Kill Switch выключен")
    }

    private fun registerNetworkCallback(context: Context, onLost: () -> Unit) {
        unregisterNetworkCallback(context)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (DirectVpnService.isRunning) {
                    ConnectionLog.w("Kill Switch: сеть потеряна — блокируем трафик")
                    onLost()
                }
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback(context: Context) {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed: ${e.message}")
            }
        }
        networkCallback = null
    }
}
