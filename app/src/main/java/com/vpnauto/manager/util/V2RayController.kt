package com.vpnauto.manager.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.vpnauto.manager.model.ServerConfig

object V2RayController {

    private const val TAG = "V2RayController"
    const val V2RAY_PKG = "com.v2ray.ang"
    private const val V2RAY_MAIN = "com.v2ray.ang.ui.MainActivity"

    // Intent actions (из исходников v2rayNG)
    private const val ACTION_SERVICE_START = "com.v2ray.ang.action.service.start"
    private const val ACTION_SERVICE_STOP  = "com.v2ray.ang.action.service.stop"
    private const val ACTION_UPDATE_CONFIG = "com.v2ray.ang.action.update_config"
    private const val ACTION_IMPORT_CONFIG = "com.v2ray.ang.action.import_config"

    /**
     * Проверяет, установлен ли v2rayNG
     */
    fun isV2RayInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(V2RAY_PKG, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Подключиться к VPN (запустить сервис v2rayNG)
     */
    fun connect(context: Context) {
        try {
            // Метод 1: явный intent к сервису v2rayNG
            val intent = Intent(ACTION_SERVICE_START).apply {
                `package` = V2RAY_PKG
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent connect broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "Connect broadcast failed: ${e.message}")
            // Метод 2: открыть v2rayNG
            openV2Ray(context)
        }
    }

    /**
     * Отключиться от VPN
     */
    fun disconnect(context: Context) {
        try {
            val intent = Intent(ACTION_SERVICE_STOP).apply {
                `package` = V2RAY_PKG
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}")
        }
    }

    /**
     * Импортировать один конфиг в v2rayNG через буфер обмена.
     * v2rayNG умеет читать из clipboard при открытии.
     */
    fun importConfigViaClipboard(context: Context, server: ServerConfig) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("v2ray_config", server.raw)
            clipboard.setPrimaryClip(clip)

            // Открыть v2rayNG — он автоматически предложит импортировать
            val intent = Intent().apply {
                setClassName(V2RAY_PKG, V2RAY_MAIN)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("from_clipboard", true)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened v2rayNG with config in clipboard: ${server.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Import via clipboard failed: ${e.message}")
        }
    }

    /**
     * Импортировать подписку в v2rayNG по URL.
     * Самый надёжный метод — v2rayNG принимает ссылку на подписку через intent.
     */
    fun importSubscriptionUrl(context: Context, subscriptionUrl: String, subName: String = "") {
        try {
            // Метод 1: стандартный intent с URL подписки
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(subscriptionUrl)
                setPackage(V2RAY_PKG)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Import subscription URL failed: ${e.message}")
            // Метод 2: через буфер обмена
            copyToClipboard(context, subscriptionUrl)
        }
    }

    /**
     * Обновить все подписки в v2rayNG (broadcast)
     */
    fun updateSubscriptions(context: Context) {
        try {
            val intent = Intent(ACTION_UPDATE_CONFIG).apply {
                `package` = V2RAY_PKG
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent update subscriptions broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "Update subscriptions failed: ${e.message}")
        }
    }

    /**
     * Открыть v2rayNG
     */
    fun openV2Ray(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(V2RAY_PKG)
            intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent?.let { context.startActivity(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Open v2rayNG failed: ${e.message}")
        }
    }

    /**
     * Открыть страницу v2rayNG в Play/GitHub для установки
     */
    fun openInstallPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/2dust/v2rayNG/releases")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open install page failed: ${e.message}")
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("vpn_sub", text))
    }

    /**
     * Построить URI для импорта одиночного конфига в v2rayNG
     * (формат, который v2rayNG принимает через "Добавить")
     */
    fun buildShareUri(server: ServerConfig): String = server.raw
}
