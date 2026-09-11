package com.keyflow.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
        const val VERCEL_BACKEND_URL = "https://keyflow-api.vercel.app/rewrite"
        private const val REQUEST_CODE_MIC_PERMISSION = 101
    }

    // Accessibility Service Views
    private lateinit var tvServiceStatusBadge: TextView
    private lateinit var tvServiceStatusDesc: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var layoutAccessibilityRow: View

    // Microphone Permission Views
    private lateinit var tvMicStatusBadge: TextView
    private lateinit var tvMicStatusDesc: TextView
    private lateinit var btnRequestMicPermission: Button
    private lateinit var layoutMicRow: View

    // Backend Connection Views
    private lateinit var etBackendUrl: EditText
    private lateinit var btnTestBackend: Button
    private lateinit var tvBackendStatus: TextView
    private lateinit var chipPresetLocal: TextView
    private lateinit var chipPresetVercel: TextView

    // Test Playground Views
    private lateinit var etTestInput: EditText
    private lateinit var btnClearTestInput: View
    private lateinit var chipSample1: TextView
    private lateinit var chipSample2: TextView
    private lateinit var chipSample3: TextView

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadSavedBackendUrl()
    }

    private fun initViews() {
        tvServiceStatusBadge = findViewById(R.id.tvServiceStatusBadge)
        tvServiceStatusDesc = findViewById(R.id.tvServiceStatusDesc)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        layoutAccessibilityRow = findViewById(R.id.layoutAccessibilityRow)

        tvMicStatusBadge = findViewById(R.id.tvMicStatusBadge)
        tvMicStatusDesc = findViewById(R.id.tvMicStatusDesc)
        btnRequestMicPermission = findViewById(R.id.btnRequestMicPermission)
        layoutMicRow = findViewById(R.id.layoutMicRow)

        etBackendUrl = findViewById(R.id.etBackendUrl)
        btnTestBackend = findViewById(R.id.btnTestBackend)
        tvBackendStatus = findViewById(R.id.tvBackendStatus)
        chipPresetLocal = findViewById(R.id.chipPresetLocal)
        chipPresetVercel = findViewById(R.id.chipPresetVercel)

        etTestInput = findViewById(R.id.etTestInput)
        btnClearTestInput = findViewById(R.id.btnClearTestInput)
        chipSample1 = findViewById(R.id.chipSample1)
        chipSample2 = findViewById(R.id.chipSample2)
        chipSample3 = findViewById(R.id.chipSample3)
    }

    private fun setupListeners() {
        val openAccessibilityAction = View.OnClickListener {
            val isRunning = isAccessibilityServiceEnabled(this, RewriteAccessibilityService::class.java)
            if (isRunning) {
                Toast.makeText(
                    this,
                    "Redirecting to Settings: You can manage or disable Keyflow here.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnOpenAccessibility.setOnClickListener(openAccessibilityAction)
        layoutAccessibilityRow.setOnClickListener(openAccessibilityAction)

        val micAction = View.OnClickListener {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMic) {
                // If already granted, allow managing via App Settings
                Toast.makeText(this, "Opening App Settings to manage permissions", Toast.LENGTH_SHORT).show()
                openAppSettings()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_MIC_PERMISSION
                )
            }
        }

        btnRequestMicPermission.setOnClickListener(micAction)
        layoutMicRow.setOnClickListener(micAction)

        btnTestBackend.setOnClickListener {
            val enteredUrl = etBackendUrl.text.toString().trim()
            if (enteredUrl.isEmpty()) {
                Toast.makeText(this, "Please enter a valid backend URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testAndSaveBackendUrl(enteredUrl)
        }

        chipPresetLocal.setOnClickListener {
            etBackendUrl.setText(DEFAULT_BACKEND_URL)
            testAndSaveBackendUrl(DEFAULT_BACKEND_URL)
        }

        chipPresetVercel.setOnClickListener {
            etBackendUrl.setText(VERCEL_BACKEND_URL)
            testAndSaveBackendUrl(VERCEL_BACKEND_URL)
        }

        btnClearTestInput.setOnClickListener {
            etTestInput.setText("")
        }

        chipSample1.setOnClickListener {
            insertSampleText("kal meeting kitne baje hai bro?")
        }

        chipSample2.setOnClickListener {
            insertSampleText("please send invoice by eod")
        }

        chipSample3.setOnClickListener {
            insertSampleText("running late will reach in 15m")
        }
    }

    private fun insertSampleText(sample: String) {
        etTestInput.setText(sample)
        etTestInput.setSelection(sample.length)
        etTestInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etTestInput, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(this, "Sample loaded! Tap ✨ on Keyflow pill to rewrite.", Toast.LENGTH_SHORT).show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open app settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSavedBackendUrl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        etBackendUrl.setText(savedUrl)
    }

    private fun getCleanBaseUrl(url: String): String {
        var clean = url.trim().trimEnd('/')
        val suffixes = listOf("/rewrite", "/transcribe", "/api/rewrite", "/api/transcribe", "/api")
        for (suffix in suffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trimEnd('/')
                break
            }
        }
        return clean
    }

    private fun testAndSaveBackendUrl(url: String) {
        val rootUrl = getCleanBaseUrl(url)
        val finalRewriteUrl = "$rootUrl/rewrite"

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, finalRewriteUrl)
            .apply()
        etBackendUrl.setText(finalRewriteUrl)

        tvBackendStatus.text = "Testing..."
        tvBackendStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        btnTestBackend.isEnabled = false

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val testResult = testConnection(finalRewriteUrl, rootUrl)
            val latencyMs = System.currentTimeMillis() - startTime
            btnTestBackend.isEnabled = true

            if (testResult.isSuccess) {
                tvBackendStatus.text = "Online ✓ (${latencyMs}ms)"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                Toast.makeText(this@MainActivity, "Connected & saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                val errorMsg = testResult.exceptionOrNull()?.localizedMessage ?: "Unreachable"
                tvBackendStatus.text = "Warning: $errorMsg"
                tvBackendStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_amber))
                Toast.makeText(this@MainActivity, "Saved! Note: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun testConnection(rewriteUrl: String, rootUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Check 1: GET root
            val getRequest = Request.Builder().url(rootUrl).get().build()
            try {
                httpClient.newCall(getRequest).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                }
            } catch (_: Exception) {}

            // Check 2: GET /api
            val apiRequest = Request.Builder().url("$rootUrl/api").get().build()
            try {
                httpClient.newCall(apiRequest).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                }
            } catch (_: Exception) {}

            // Check 3: POST ping payload
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
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            tvMicStatusBadge.text = "GRANTED ✓"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            tvMicStatusDesc.text = "Whisper voice dictation is ready to use"

            btnRequestMicPermission.text = "Settings"
            btnRequestMicPermission.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.button_bg)
            )
            btnRequestMicPermission.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            tvMicStatusBadge.text = "REQUIRED"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvMicStatusDesc.text = "Required for on-device voice recording & Whisper"

            btnRequestMicPermission.text = "Grant"
            btnRequestMicPermission.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_primary)
            )
            btnRequestMicPermission.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_MIC_PERMISSION) {
            updateMicPermissionStatus()
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateServiceStatus() {
        val isServiceRunning = isAccessibilityServiceEnabled(this, RewriteAccessibilityService::class.java)

        if (isServiceRunning) {
            tvServiceStatusBadge.text = "ACTIVE ✓"
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            tvServiceStatusDesc.text = "Keyflow is active & ready above Gboard"

            btnOpenAccessibility.text = "Settings"
            btnOpenAccessibility.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.button_bg)
            )
            btnOpenAccessibility.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            tvServiceStatusBadge.text = "INACTIVE"
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvServiceStatusDesc.text = "Required to dock above keyboard and insert text"

            btnOpenAccessibility.text = "Enable"
            btnOpenAccessibility.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_primary)
            )
            btnOpenAccessibility.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    /**
     * Foolproof 3-layer accessibility detection:
     * 1. In-memory companion state (direct running check)
     * 2. Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES check
     * 3. AccessibilityManager FEEDBACK_ALL_MASK check
     */
    private fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<out android.accessibilityservice.AccessibilityService>
    ): Boolean {
        // Layer 1: In-memory flag from service lifecycle
        if (RewriteAccessibilityService.isServiceRunning) {
            return true
        }

        // Layer 2: Settings.Secure string inspection
        try {
            val expectedId = "${context.packageName}/${serviceClass.name}"
            val simpleExpectedId = "${context.packageName}/.${serviceClass.simpleName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (enabledServicesSetting.isNotEmpty()) {
                val isPresent = enabledServicesSetting.split(':').any { item ->
                    item.equals(expectedId, ignoreCase = true) ||
                    item.equals(simpleExpectedId, ignoreCase = true) ||
                    (item.contains(context.packageName) && item.contains(serviceClass.simpleName))
                }
                if (isPresent) {
                    val accessibilityEnabled = Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        0
                    ) == 1
                    if (accessibilityEnabled) return true
                }
            }
        } catch (_: Exception) {}

        // Layer 3: AccessibilityManager with FEEDBACK_ALL_MASK
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expectedId = "${context.packageName}/${serviceClass.name}"
            val simpleExpectedId = "${context.packageName}/.${serviceClass.simpleName}"

            return enabledServices.any { serviceInfo ->
                val id = serviceInfo.id ?: ""
                id.equals(expectedId, ignoreCase = true) ||
                id.equals(simpleExpectedId, ignoreCase = true) ||
                (id.contains(context.packageName) && id.contains(serviceClass.simpleName))
            }
        } catch (_: Exception) {
            return false
        }
    }
}
