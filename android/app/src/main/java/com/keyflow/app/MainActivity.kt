package com.keyflow.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.keyflow.app.service.RewriteAccessibilityService

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "keyflow_prefs"
        const val KEY_BACKEND_URL = "pref_backend_url"
        const val DEFAULT_BACKEND_URL = "http://10.98.66.198:8000/rewrite"
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

    // Test Playground Views
    private lateinit var etTestInput: EditText
    private lateinit var btnClearTestInput: View
    private lateinit var chipSample1: TextView
    private lateinit var chipSample2: TextView
    private lateinit var chipSample3: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
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
                    "Redirecting to Settings: You can manage Keyflow here.",
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
            tvMicStatusBadge.text = "READY ✓"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            tvMicStatusDesc.text = "Whisper voice dictation is ready to use"

            btnRequestMicPermission.text = "Settings"
            btnRequestMicPermission.setBackgroundResource(R.drawable.bg_btn_secondary)
            btnRequestMicPermission.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            tvMicStatusBadge.text = "REQUIRED"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvMicStatusDesc.text = "Required for voice dictation & Whisper"

            btnRequestMicPermission.text = "Grant"
            btnRequestMicPermission.setBackgroundResource(R.drawable.bg_btn_cta)
            btnRequestMicPermission.setTextColor(ContextCompat.getColor(this, R.color.btn_cta_text))
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
            btnOpenAccessibility.setBackgroundResource(R.drawable.bg_btn_secondary)
            btnOpenAccessibility.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            tvServiceStatusBadge.text = "REQUIRED"
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvServiceStatusDesc.text = "Required to dock above keyboard and insert text"

            btnOpenAccessibility.text = "Enable"
            btnOpenAccessibility.setBackgroundResource(R.drawable.bg_btn_cta)
            btnOpenAccessibility.setTextColor(ContextCompat.getColor(this, R.color.btn_cta_text))
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
