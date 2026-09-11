package com.keyflow.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.keyflow.app.service.RewriteAccessibilityService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_MIC_PERMISSION = 101
    }

    // Header Views
    private lateinit var btnHeaderSettings: View

    // Accessibility Service Views
    private lateinit var layoutAccessibilityRow: View
    private lateinit var tvServiceStatusBadge: TextView
    private lateinit var tvServiceStatusDesc: TextView

    // Microphone Permission Views
    private lateinit var layoutMicRow: View
    private lateinit var tvMicStatusBadge: TextView
    private lateinit var tvMicStatusDesc: TextView

    // Test Playground Views
    private lateinit var etTestInput: EditText
    private lateinit var btnClearTestInput: View
    private lateinit var btnRunTest: View
    private lateinit var chipSample1: TextView
    private lateinit var chipSample2: TextView
    private lateinit var chipSample3: TextView

    // Guide & Navigation Views
    private lateinit var btnViewFullGuide: View
    private lateinit var navTabHome: View
    private lateinit var navTabHistory: View
    private lateinit var navTabStyles: View
    private lateinit var navTabMore: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        btnHeaderSettings = findViewById(R.id.btnHeaderSettings)

        layoutAccessibilityRow = findViewById(R.id.layoutAccessibilityRow)
        tvServiceStatusBadge = findViewById(R.id.tvServiceStatusBadge)
        tvServiceStatusDesc = findViewById(R.id.tvServiceStatusDesc)

        layoutMicRow = findViewById(R.id.layoutMicRow)
        tvMicStatusBadge = findViewById(R.id.tvMicStatusBadge)
        tvMicStatusDesc = findViewById(R.id.tvMicStatusDesc)

        etTestInput = findViewById(R.id.etTestInput)
        btnClearTestInput = findViewById(R.id.btnClearTestInput)
        btnRunTest = findViewById(R.id.btnRunTest)
        chipSample1 = findViewById(R.id.chipSample1)
        chipSample2 = findViewById(R.id.chipSample2)
        chipSample3 = findViewById(R.id.chipSample3)

        btnViewFullGuide = findViewById(R.id.btnViewFullGuide)
        navTabHome = findViewById(R.id.navTabHome)
        navTabHistory = findViewById(R.id.navTabHistory)
        navTabStyles = findViewById(R.id.navTabStyles)
        navTabMore = findViewById(R.id.navTabMore)
    }

    private fun setupListeners() {
        // Header Settings
        btnHeaderSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openAppSettings()
        }

        // Accessibility Service Row
        layoutAccessibilityRow.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val isRunning = isAccessibilityServiceEnabled(this, RewriteAccessibilityService::class.java)
            if (isRunning) {
                Toast.makeText(
                    this,
                    "Keyflow is Active: You can manage accessibility in Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Microphone Access Row
        layoutMicRow.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasMic) {
                Toast.makeText(this, "Microphone is Ready: Managing permissions in Settings.", Toast.LENGTH_SHORT).show()
                openAppSettings()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_CODE_MIC_PERMISSION
                )
            }
        }

        // Clear Playground
        btnClearTestInput.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            etTestInput.setText("")
        }

        // Upward Arrow CTA Submit Button
        btnRunTest.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val currentText = etTestInput.text.toString().trim()
            if (currentText.isEmpty()) {
                insertSampleText("kal meeting kitne baje hai?")
            } else {
                etTestInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etTestInput, InputMethodManager.SHOW_IMPLICIT)
                Toast.makeText(this, "Tap the Keyflow floating pill to rewrite!", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Samples
        chipSample1.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            insertSampleText("kal meeting kitne baje hai?")
        }

        chipSample2.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            insertSampleText("please send invoice by eod")
        }

        chipSample3.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            insertSampleText("rewrite this")
        }

        // Full Guide
        btnViewFullGuide.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Toast.makeText(
                this,
                "Single Tap: Rewrite | Double Tap: Toggle Voice/Text | Long Press: Tone Menu | Drag: Reposition",
                Toast.LENGTH_LONG
            ).show()
        }

        // Bottom Navigation Tabs
        navTabHome.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollViewMain)?.smoothScrollTo(0, 0)
        }

        val stubTabListener = View.OnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Toast.makeText(this, "Keyflow v2.0 • Feature coming soon!", Toast.LENGTH_SHORT).show()
        }
        navTabHistory.setOnClickListener(stubTabListener)
        navTabStyles.setOnClickListener(stubTabListener)
        navTabMore.setOnClickListener(stubTabListener)
    }

    private fun insertSampleText(sample: String) {
        etTestInput.setText(sample)
        etTestInput.setSelection(sample.length)
        etTestInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etTestInput, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(this, "Sample loaded! Tap the floating pill to transform.", Toast.LENGTH_SHORT).show()
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
            tvMicStatusBadge.text = "Ready"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            tvMicStatusDesc.text = "Needed for voice dictation (Whisper)."
        } else {
            tvMicStatusBadge.text = "Required"
            tvMicStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvMicStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvMicStatusDesc.text = "Needed for voice dictation (Whisper)."
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
            tvServiceStatusBadge.text = "Active"
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            tvServiceStatusDesc.text = "Allows Keyflow to appear above your keyboard."
        } else {
            tvServiceStatusBadge.text = "Required"
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            tvServiceStatusBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            tvServiceStatusDesc.text = "Allows Keyflow to appear above your keyboard."
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
