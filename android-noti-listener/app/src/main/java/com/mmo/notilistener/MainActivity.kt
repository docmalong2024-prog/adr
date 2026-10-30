package com.mmo.notilistener

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mmo.notilistener.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var logAdapter: LogAdapter
    private val logList = mutableListOf<NotificationLog>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PaymentListenerService.PREFS_NAME, Context.MODE_PRIVATE)

        setupRecyclerView()
        loadSavedConfig()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        observeServiceEvents()
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter(logList)
        binding.rvLogs.layoutManager = LinearLayoutManager(this)
        binding.rvLogs.adapter = logAdapter
        updateEmptyLogVisibility()
    }

    private fun loadSavedConfig() {
        val savedUrl = prefs.getString(
            PaymentListenerService.KEY_WEBHOOK_URL,
            PaymentListenerService.DEFAULT_WEBHOOK_URL
        ) ?: PaymentListenerService.DEFAULT_WEBHOOK_URL

        val savedSecret = prefs.getString(
            PaymentListenerService.KEY_SECRET_KEY,
            PaymentListenerService.DEFAULT_SECRET_KEY
        ) ?: PaymentListenerService.DEFAULT_SECRET_KEY

        binding.etWebhookUrl.setText(savedUrl)
        binding.etSecretKey.setText(savedSecret)
    }

    private fun setupListeners() {
        // Save Config Button
        binding.btnSaveConfig.setOnClickListener {
            val url = binding.etWebhookUrl.text.toString().trim()
            val secret = binding.etSecretKey.text.toString().trim()

            if (url.isEmpty() || secret.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập đầy đủ Webhook URL và Secret Key!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(PaymentListenerService.KEY_WEBHOOK_URL, url)
                .putString(PaymentListenerService.KEY_SECRET_KEY, secret)
                .apply()

            Toast.makeText(this, "Đã lưu cấu hình kết nối thành công!", Toast.LENGTH_SHORT).show()
        }

        // Grant Notification Listener Permission
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // Test Ping Button
        binding.btnTestPing.setOnClickListener {
            val url = binding.etWebhookUrl.text.toString().trim()
            val secret = binding.etSecretKey.text.toString().trim()

            binding.tvPingResult.text = "Đang kiểm tra kết nối tới $url..."
            binding.tvPingResult.setTextColor(Color.parseColor("#FFD000"))

            lifecycleScope.launch(Dispatchers.IO) {
                val (success, message) = WebhookSender.testPing(url, secret)
                withContext(Dispatchers.Main) {
                    binding.tvPingResult.text = message
                    binding.tvPingResult.setTextColor(
                        if (success) Color.parseColor("#00FF9D") else Color.parseColor("#FF0055")
                    )
                }
            }
        }

        // Send Test Transaction 50,000 VND
        binding.btnTestSampleTx.setOnClickListener {
            sendSampleTransaction()
        }

        // Clear Live Log
        binding.btnClearLog.setOnClickListener {
            logAdapter.clearAll()
            updateEmptyLogVisibility()
            Toast.makeText(this, "Đã xóa toàn bộ nhật ký hiển thị.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendSampleTransaction() {
        val url = binding.etWebhookUrl.text.toString().trim()
        val secret = binding.etSecretKey.text.toString().trim()

        val samplePackage = "vn.com.vng.zalopay"
        val sampleTitle = "Thông báo nhận tiền (Test từ App)"
        val randomSuffix = (10000..99999).random() // Mỗi lần một mã chuyển khoản ngẫu nhiên khác nhau
        val sampleContent = "Bạn vừa nhận được 50,000đ vào TK 99ZP26263M77201963. Nội dung: MMO DUCTRI $randomSuffix"
        val sampleTimestamp = System.currentTimeMillis()
        val logId = UUID.randomUUID().toString()

        val logItem = NotificationLog(
            id = logId,
            packageName = samplePackage,
            title = sampleTitle,
            content = sampleContent,
            timestamp = sampleTimestamp,
            status = "RETRYING",
            httpCode = 0,
            serverResponse = "Đang bắn gói tin test lên Server..."
        )

        logAdapter.addLog(logItem)
        updateEmptyLogVisibility()

        lifecycleScope.launch(Dispatchers.IO) {
            val (success, httpCode, serverResponse) = WebhookSender.sendPaymentWebhook(
                webhookUrl = url,
                secretKey = secret,
                packageName = samplePackage,
                title = sampleTitle,
                content = sampleContent,
                timestamp = sampleTimestamp,
                onRetry = { attempt, err ->
                    runOnUiThread {
                        logItem.status = "RETRYING"
                        logItem.serverResponse = "Thử lại lần $attempt: $err"
                        logAdapter.updateLog(logItem)
                    }
                }
            )

            withContext(Dispatchers.Main) {
                logItem.httpCode = httpCode
                logItem.status = if (success) "SUCCESS" else "FAILED"
                logItem.serverResponse = serverResponse
                logAdapter.updateLog(logItem)

                if (success) {
                    Toast.makeText(this@MainActivity, "Bắn test thành công! Web đã cộng +50.000đ!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Bắn test thất bại: $serverResponse", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeServiceEvents() {
        PaymentListenerService.onLogEvent = { log ->
            runOnUiThread {
                val existing = logList.find { it.id == log.id }
                if (existing == null) {
                    logAdapter.addLog(log)
                } else {
                    logAdapter.updateLog(log)
                }
                updateEmptyLogVisibility()
            }
        }
    }

    private fun updatePermissionStatus() {
        val isGranted = isNotificationListenerGranted()
        if (isGranted) {
            binding.tvPermissionStatus.text = "ĐÃ CẤP QUYỀN (SẴN SÀNG)"
            binding.tvPermissionStatus.setTextColor(Color.parseColor("#00FF9D"))
            binding.tvPermissionStatus.setBackgroundColor(Color.parseColor("#2000FF9D"))
            binding.btnGrantPermission.text = "QUYỀN ĐỌC THÔNG BÁO: ĐÃ HOẠT ĐỘNG"
            binding.btnGrantPermission.setTextColor(Color.parseColor("#00FF9D"))
            binding.btnGrantPermission.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#00FF9D"))
        } else {
            binding.tvPermissionStatus.text = "CHƯA CẤP QUYỀN"
            binding.tvPermissionStatus.setTextColor(Color.parseColor("#FF0055"))
            binding.tvPermissionStatus.setBackgroundColor(Color.parseColor("#20FF0055"))
            binding.btnGrantPermission.text = "BẤM ĐỂ CẤP QUYỀN ĐỌC THÔNG BÁO"
            binding.btnGrantPermission.setTextColor(Color.parseColor("#FF0055"))
            binding.btnGrantPermission.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF0055"))
        }
    }

    private fun isNotificationListenerGranted(): Boolean {
        val pkgName = packageName
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(pkgName)
    }

    private fun updateEmptyLogVisibility() {
        binding.tvEmptyLog.visibility = if (logList.isEmpty()) View.VISIBLE else View.GONE
    }
}

