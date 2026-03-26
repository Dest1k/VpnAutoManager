package com.vpnauto.manager.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vpnauto.manager.R

/**
 * Quick Settings Tile — плитка в шторке уведомлений.
 * Показывает статус VPN, нажатие — подключить/отключить.
 */
class VpnTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (DirectVpnService.isRunning) {
            startService(DirectVpnService.buildDisconnectIntent(this))
        } else {
            // Открыть приложение для подключения (нужно разрешение пользователя)
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("action", "connect")
            }
            intent?.let { startActivityAndCollapse(it) }
        }
        updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when {
            DirectVpnService.isRunning -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VPN: ${DirectVpnService.connectedServer.take(15)}"
                tile.contentDescription = "VPN подключён"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn)
            }
            DirectVpnService.isConnecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "VPN: подключение..."
                tile.contentDescription = "VPN подключается"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "VPN Guard"
                tile.contentDescription = "VPN отключён"
                tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn)
            }
        }
        tile.updateTile()
    }
}
