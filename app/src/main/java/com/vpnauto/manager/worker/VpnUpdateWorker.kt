package com.vpnauto.manager.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.vpnauto.manager.R
import com.vpnauto.manager.model.ServerConfig
import com.vpnauto.manager.ui.MainActivity
import com.vpnauto.manager.util.PingTester
import com.vpnauto.manager.util.SubscriptionRepository
import com.vpnauto.manager.util.V2RayController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VpnUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "vpn_auto_update"
        const val CHANNEL_ID = "vpn_auto_channel"
        const val NOTIF_ID_PROGRESS = 1001
        const val NOTIF_ID_RESULT   = 1002

        fun schedule(context: Context, intervalHours: Int = 2) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<VpnUpdateWorker>(
                intervalHours.toLong(), TimeUnit.HOURS,
                15L, TimeUnit.MINUTES  // flex window
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<VpnUpdateWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_immediate",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    private val repo = SubscriptionRepository(context)
    private val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    override suspend fun doWork(): Result {
        createNotificationChannel()
        showProgressNotification("Обновление подписок...")

        return try {
            // 1. Скачать все включённые подписки
            val fetchResults = repo.fetchAllEnabledSubscriptions()
            val allServers = mutableListOf<ServerConfig>()
            var fetchErrors = 0

            fetchResults.forEach { (subId, result) ->
                result.fold(
                    onSuccess = { servers -> allServers.addAll(servers) },
                    onFailure = { fetchErrors++ }
                )
            }

            if (allServers.isEmpty()) {
                showResultNotification("❌ Ошибка обновления", "Не удалось загрузить конфиги")
                return Result.retry()
            }

            showProgressNotification("Тестирование ${allServers.size} серверов...")

            // 2. Пинг серверов
            var bestServer: ServerConfig? = null
            if (repo.pingOnUpdate) {
                val tested = PingTester.testServers(
                    servers = allServers,
                    onProgress = { done, total ->
                        showProgressNotification("Тестирование серверов ($done/$total)...")
                    }
                )
                bestServer = tested.firstOrNull { it.isReachable }
            } else {
                bestServer = allServers.firstOrNull()
            }

            repo.lastUpdateTime = System.currentTimeMillis()

            // 3. Подключиться к лучшему серверу
            if (repo.autoConnect && bestServer != null) {
                withContext(Dispatchers.Main) {
                    V2RayController.updateSubscriptions(context)
                }
                val pingText = if (bestServer.pingMs > 0) "${bestServer.pingMs}ms" else "—"
                showResultNotification(
                    "✅ VPN обновлён",
                    "Лучший сервер: ${bestServer.name} ($pingText)"
                )
            } else {
                val errText = if (fetchErrors > 0) " ($fetchErrors ошибок загрузки)" else ""
                showResultNotification(
                    "🔄 Подписки обновлены$errText",
                    "${allServers.size} конфигов загружено"
                )
            }

            Result.success()
        } catch (e: Exception) {
            showResultNotification("❌ Ошибка", e.message ?: "Неизвестная ошибка")
            Result.retry()
        } finally {
            notifManager.cancel(NOTIF_ID_PROGRESS)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Guard обновления",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомления об обновлении VPN-подписок"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun showProgressNotification(text: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("VPN Guard")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifManager.notify(NOTIF_ID_PROGRESS, notif)
    }

    private fun showResultNotification(title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifManager.notify(NOTIF_ID_RESULT, notif)
    }
}
