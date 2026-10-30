package com.mmo.notilistener

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebhookSender {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Call GET /api/webhook/ping to verify server connection and secret key
     */
    suspend fun testPing(serverBaseOrPingUrl: String, secretKey: String): Pair<Boolean, String> {
        val pingUrl = if (serverBaseOrPingUrl.endsWith("/api/webhook/bank")) {
            serverBaseOrPingUrl.replace("/api/webhook/bank", "/api/webhook/ping")
        } else if (serverBaseOrPingUrl.endsWith("/api/webhook/ping")) {
            serverBaseOrPingUrl
        } else {
            "${serverBaseOrPingUrl.trimEnd('/')}/api/webhook/ping"
        }

        val request = Request.Builder()
            .url(pingUrl)
            .get()
            .addHeader("x-api-key", secretKey)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Pair(true, "Kết nối thành công (200 OK): $bodyString")
                } else {
                    Pair(false, "Lỗi HTTP ${response.code}: $bodyString")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Không thể kết nối đến máy chủ: ${e.localizedMessage}")
        }
    }

    /**
     * Send Payment Webhook with up to 3 retries and exponential backoff
     */
    suspend fun sendPaymentWebhook(
        webhookUrl: String,
        secretKey: String,
        packageName: String,
        title: String,
        content: String,
        timestamp: Long,
        onRetry: ((attempt: Int, error: String) -> Unit)? = null
    ): Triple<Boolean, Int, String> {

        val payload = JsonObject().apply {
            addProperty("packageName", packageName)
            addProperty("title", title)
            addProperty("content", content)
            addProperty("timestamp", timestamp)
        }

        val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(webhookUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", secretKey)
            .build()

        val maxRetries = 3
        var currentAttempt = 0
        var lastExceptionMessage = ""
        var lastHttpCode = 0

        while (currentAttempt < maxRetries) {
            currentAttempt++
            try {
                client.newCall(request).execute().use { response ->
                    lastHttpCode = response.code
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        return Triple(true, response.code, responseBody)
                    } else {
                        lastExceptionMessage = "HTTP ${response.code}: $responseBody"
                        if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                            // Client errors (401 unauthorized, 404 user not found) usually do not benefit from retrying
                            return Triple(false, response.code, responseBody)
                        }
                    }
                }
            } catch (e: IOException) {
                lastExceptionMessage = "Lỗi kết nối IO: ${e.localizedMessage}"
            } catch (e: Exception) {
                lastExceptionMessage = "Lỗi ngoại lệ: ${e.localizedMessage}"
            }

            if (currentAttempt < maxRetries) {
                onRetry?.invoke(currentAttempt, lastExceptionMessage)
                // Exponential backoff: 2s, 4s
                delay(currentAttempt * 2000L)
            }
        }

        return Triple(false, lastHttpCode, "Thất bại sau $maxRetries lần thử: $lastExceptionMessage")
    }
}

