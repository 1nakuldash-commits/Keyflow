package com.keyflow.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.keyflow.app.service.RewriteAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "keyflow_prefs"
        const val KEY_BACKEND_URL = "pref_backend_url"
        const val DEFAULT_BACKEND_URL = "http://10.127.158.198:8000/rewrite"
    }

    private lateinit var tvStatus: TextView
    private lateinit var viewStatusDot: View
    private lateinit var btnOpenAccessibility: Button
    private lateinit var etBackendUrl: EditText
    private lateinit var btnTestBackend: Button
    private lateinit var tvBackendStatus: TextView

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvServiceStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        etBackendUrl = findViewById(R.id.etBackendUrl)
        btnTestBackend = findViewById(R.id.btnTestBackend)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)

        // Load saved backend URL
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        etBackendUrl.setText(savedUrl)

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnTestBackend.setOnClickListener {
            val enteredUrl = etBackendUrl.text.toString().trim()
            if (enteredUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testAndSaveBackendUrl(enteredUrl)
        }
    }

    private fun testAndSaveBackendUrl(url: String) {
        tvBackendStatus.text = "Testing..."
        tvBackendStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnTestBackend.isEnabled = false

        lifecycleScope.launch {
            val trimmedUrl = url.trim()
            val rootUrl = if (trimmedUrl.contains("/rewrite")) {
                trimmedUrl.substringBefore("/rewrite")
            } else {
                trimmedUrl.trimEnd('/')
            }

            val finalRewriteUrl = if (trimmedUrl.endsWith("/rewrite")) {
                trimmedUrl
            } else {
                "${trimmedUrl.trimEnd('/')}/rewrite"
            }

            val testResult = testConnection(rootUrl)
            btnTestBackend.isEnabled = true

            if (testResult.isSuccess) {
                // Save URL to SharedPreferences
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_BACKEND_URL, finalRewriteUrl)
                    .apply()

                etBackendUrl.setText(finalRewriteUrl)
                tvBackendStatus.text = "Connected! (Server Online)"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                Toast.makeText(this@MainActivity, "Backend URL saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                tvBackendStatus.text = "Failed: ${testResult.exceptionOrNull()?.localizedMessage ?: "Unreachable"}"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
            }
        }
    }

    private suspend fun testConnection(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isServiceRunning = isAccessibilityServiceEnabled(this, RewriteAccessibilityService::class.java)

        if (isServiceRunning) {
            tvStatus.text = getString(R.string.service_status_active)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            viewStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_green)
            )
            btnOpenAccessibility.text = "Accessibility Service Enabled ✓"
            btnOpenAccessibility.alpha = 0.8f
        } else {
            tvStatus.text = getString(R.string.service_status_inactive)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            viewStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_gray)
            )
            btnOpenAccessibility.text = getString(R.string.btn_open_accessibility)
            btnOpenAccessibility.alpha = 1.0f
        }
    }

    private fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<out android.accessibilityservice.AccessibilityService>
    ): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expectedId = "${context.packageName}/${serviceClass.name}"

        return enabledServices.any { serviceInfo ->
            serviceInfo.id.equals(expectedId, ignoreCase = true)
        }
    }
}
