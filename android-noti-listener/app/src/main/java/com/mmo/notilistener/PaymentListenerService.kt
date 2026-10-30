package com.mmo.notilistener

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class PaymentListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "PaymentListenerService"
        const val PREFS_NAME = "mmo_listener_prefs"
        const val KEY_WEBHOOK_URL = "webhook_url"
        const val KEY_SECRET_KEY = "secret_key"

        const val DEFAULT_WEBHOOK_URL = "http://10.0.2.2:3000/api/webhook/bank"
        const val DEFAULT_SECRET_KEY = "DUCTRI_SECRET_KEY_2026"

        // Target packages to monitor
        val TARGET_PACKAGES = setOf(
            "vn.com.vng.zalopay",
            "vn.timobank",
            "com.vietcapitalbank.mobilebanking"
        )

        // Event listener for MainActivity to observe live logs
        var onLogEvent: ((NotificationLog) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "PaymentListenerService đã khởi chạy và sẵn sàng lắng nghe thông báo.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: "unknown"
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val fullContent = if (bigText.isNotEmpty()) "$text - $bigText" else text

        // Filter: Check if package belongs to target list OR content contains key bank indicators
        val isTargetPackage = TARGET_PACKAGES.contains(packageName)
        val hasBankKeywords = fullContent.contains("nhận được", ignoreCase = true) ||
                fullContent.contains("+", ignoreCase = true) ||
                fullContent.contains("99ZP26263M77201963", ignoreCase = true) ||
                fullContent.contains("MMO", ignoreCase = true)

        if (!isTargetPackage && !hasBankKeywords) {
            // Not a relevant transaction notification, ignore
            return
        }

        Log.i(TAG, "Phát hiện thông báo biến động: [$packageName] $title: $fullContent")

        val timestamp = sbn.postTime
        val logId = UUID.randomUUID().toString()

        val logItem = NotificationLog(
            id = logId,
            packageName = packageName,
            title = title,
            content = fullContent,
            timestamp = timestamp,
            status = "RETRYING",
            httpCode = 0,
            serverResponse = "Đang gửi Webhook về Server..."
        )

        // Notify UI immediately
        onLogEvent?.invoke(logItem)

        // Dispatch background Webhook
        serviceScope.launch {
            val webhookUrl = prefs.getString(KEY_WEBHOOK_URL, DEFAULT_WEBHOOK_URL) ?: DEFAULT_WEBHOOK_URL
            val secretKey = prefs.getString(KEY_SECRET_KEY, DEFAULT_SECRET_KEY) ?: DEFAULT_SECRET_KEY

            val (success, httpCode, serverResponse) = WebhookSender.sendPaymentWebhook(
                webhookUrl = webhookUrl,
                secretKey = secretKey,
                packageName = packageName,
                title = title,
                content = fullContent,
                timestamp = timestamp,
                onRetry = { attempt, err ->
                    logItem.status = "RETRYING"
                    logItem.serverResponse = "Thử lại lần $attempt... ($err)"
                    onLogEvent?.invoke(logItem)
                }
            )

            logItem.httpCode = httpCode
            logItem.status = if (success) "SUCCESS" else "FAILED"
            logItem.serverResponse = serverResponse
            onLogEvent?.invoke(logItem)

            Log.i(TAG, "Kết quả gửi Webhook [$logId]: Thành công=$success, Code=$httpCode, Body=$serverResponse")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}

