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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private lateinit var btnRequestMicPermission: Button
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
        btnRequestMicPermission = findViewById(R.id.btnRequestMicPermission)
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

        btnRequestMicPermission.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    101
                )
            } else {
                Toast.makeText(this, "Microphone permission is already granted!", Toast.LENGTH_SHORT).show()
            }
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
        val trimmedUrl = url.trim()
        val finalRewriteUrl = if (trimmedUrl.endsWith("/rewrite")) {
            trimmedUrl
        } else {
            "${trimmedUrl.trimEnd('/')}/rewrite"
        }

        // ALWAYS save immediately so the user never loses their URL
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, finalRewriteUrl)
            .apply()
        etBackendUrl.setText(finalRewriteUrl)

        tvBackendStatus.text = "Testing connection..."
        tvBackendStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnTestBackend.isEnabled = false

        lifecycleScope.launch {
            val rootUrl = if (finalRewriteUrl.contains("/rewrite")) {
                finalRewriteUrl.substringBefore("/rewrite")
            } else {
                finalRewriteUrl.trimEnd('/')
            }

            val testResult = testConnection(finalRewriteUrl, rootUrl)
            btnTestBackend.isEnabled = true

            if (testResult.isSuccess) {
                tvBackendStatus.text = "Connected! (Server Online ✓)"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                Toast.makeText(this@MainActivity, "Connected & saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                val errorMsg = testResult.exceptionOrNull()?.localizedMessage ?: "Unreachable"
                tvBackendStatus.text = "Saved! (Warning: $errorMsg)"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
                Toast.makeText(this@MainActivity, "URL saved! Note: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun testConnection(rewriteUrl: String, rootUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Attempt 1: Test GET on root URL (e.g. https://xxx.vercel.app/)
            val getRequest = Request.Builder().url(rootUrl).get().build()
            try {
                httpClient.newCall(getRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext Result.success(true)
                    }
                }
            } catch (_: Exception) {}

            // Attempt 2: Test GET on /api
            val apiRequest = Request.Builder().url("${rootUrl}/api").get().build()
            try {
                httpClient.newCall(apiRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext Result.success(true)
                    }
                }
            } catch (_: Exception) {}

            // Attempt 3: Test POST directly to the /rewrite endpoint with ping payload
            val testPayload = JSONObject().apply { put("text", "ping") }.toString()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val postRequest = Request.Builder()
                .url(rewriteUrl)
                .post(testPayload.toRequestBody(mediaType))
                .build()

            httpClient.newCall(postRequest).execute().use { response ->
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
        updateMicPermissionStatus()
    }

    private fun updateMicPermissionStatus() {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            btnRequestMicPermission.text = "Microphone Access Granted ✓"
            btnRequestMicPermission.isEnabled = false
            btnRequestMicPermission.alpha = 0.75f
        } else {
            btnRequestMicPermission.text = "Grant Microphone Permission"
            btnRequestMicPermission.isEnabled = true
            btnRequestMicPermission.alpha = 1.0f
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            updateMicPermissionStatus()
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
            }
        }
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
