package com.vpnauto.manager.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.vpnauto.manager.R
import com.vpnauto.manager.ui.MainActivity

class VpnWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (DirectVpnService.isRunning)
                    ctx.startService(DirectVpnService.buildDisconnectIntent(ctx))
                else {
                    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK; putExtra("action","connect") }
                    launch?.let { ctx.startActivity(it) }
                }
                refreshAll(ctx)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.vpnauto.manager.WIDGET_TOGGLE"

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, VpnWidget::class.java))
            ids.forEach { update(context, mgr, it) }
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_vpn)

            val (statusText, btnText) = when {
                DirectVpnService.isRunning    -> "✅ ${DirectVpnService.connectedServer.take(12)}" to "Откл."
                DirectVpnService.isConnecting -> "⏳ Подключение..." to "Откл."
                else                          -> "VPN отключён" to "Вкл."
            }

            rv.setTextViewText(R.id.tvWidgetStatus, statusText)
            rv.setTextViewText(R.id.btnWidgetToggle, btnText)

            val togglePi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, VpnWidget::class.java).apply { action = ACTION_TOGGLE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.btnWidgetToggle, togglePi)

            val openPi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.tvWidgetStatus, openPi)
            mgr.updateAppWidget(id, rv)
        }
    }
}
