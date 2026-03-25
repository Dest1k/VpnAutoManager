package com.vpnauto.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vpnauto.manager.util.SubscriptionRepository
import com.vpnauto.manager.worker.VpnUpdateWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val repo = SubscriptionRepository(context)
            VpnUpdateWorker.schedule(context, repo.updateIntervalHours)
        }
    }
}
