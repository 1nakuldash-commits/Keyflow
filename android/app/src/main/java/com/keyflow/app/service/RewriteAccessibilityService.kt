package com.keyflow.app.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.keyflow.app.MainActivity
import com.keyflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class RewriteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RewriteService"
        private const val BACKEND_URL = "http://10.0.2.2:8000/rewrite"
        private const val PREFS_KEYFLOW = "keyflow_prefs"
        private const val KEY_BACKEND_URL = "pref_backend_url"
        private const val PREF_PILL_SNAP_SIDE = "pref_pill_snap_side" // "LEFT" or "RIGHT"
        private const val PREF_PILL_X = "pref_pill_x"
        private const val PREF_PILL_Y_OFFSET = "pref_pill_y_offset"
        
        // Mode & Tone configuration
        const val MODE_VOICE = "VOICE"
        const val MODE_TEXT = "TEXT"
        private const val PREF_ACTIVE_MODE = "pref_active_mode"
        private const val PREF_VOICE_TONE = "pref_voice_tone"
        private const val PREF_TEXT_TONE = "pref_text_tone"
        const val TONE_RAW = "raw"
        const val TONE_NORMAL = "normal"
        const val TONE_PROFESSIONAL = "professional"
        private const val DEFAULT_TONE = TONE_NORMAL

        @Volatile
        var isServiceRunning = false
            internal set

        private const val BADGE_SIZE_DP = 44
        private const val CAPSULE_EXPANDED_WIDTH_DP = 156
        private const val ROOT_PADDING_DP = 8
        private const val BADGE_MARGIN_EDGE_DP = 10 // Clean 10dp margin for both pill and popup menu
        private const val BADGE_GAP_ABOVE_KEYBOARD_DP = 8
        private const val LONG_PRESS_THRESHOLD_MS = 260L
        private const val DOUBLE_TAP_THRESHOLD_MS = 280L
        private const val MENU_WIDTH_DP = 132
        private const val KEY_PREVIEW_IGNORE_THRESHOLD_PX = 120

        private const val RECORDING_CHANNEL_ID = "keyflow_voice_channel"
        private const val RECORDING_NOTIFICATION_ID = 1001
        private const val MIN_RECORDING_DURATION_MS = 650L

        private val CHAT_PLACEHOLDERS = setOf(
            "message",
            "message...",
            "type a message",
            "type a message...",
            "type message",
            "type message...",
            "write a message",
            "write a message...",
            "send a message",
            "send a message...",
            "send a chat",
            "start a chat",
            "say something",
            "say something...",
            "text message",
            "search",
            "search...",
            "add a comment",
            "add a comment...",
            "reply",
            "reply...",
            "ask a question",
            "ask a question..."
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var btnRewrite: View? = null
    private var ivIcon: ImageView? = null
    private var progressBar: ProgressBar? = null
    private var snapAnimator: ValueAnimator? = null

    // Mode State (Voice-to-Text is default)
    private var activeMode = MODE_VOICE

    // Voice Recording & Whisper Flow Live Wave Capsule State
    private var isRecording = false
    private var isCapsuleExpanded = false
    private var recordingStartTime = 0L
    private var audioFocusRequest: AudioFocusRequest? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var layoutVoiceVisualizer: View? = null
    private var btnCancelVoice: View? = null
    private var btnDoneVoice: View? = null
    private var layoutWaveBars: View? = null
    private val waveBars = arrayOfNulls<View>(11)
    private var livingWavePhaseAnimator: ValueAnimator? = null
    private var livingWavePhase = 0f
    private var smoothedAmplitude = 0f

    private val maxRecordingTimeoutRunnable = Runnable {
        if (isRecording) {
            Toast.makeText(this@RewriteAccessibilityService, "Recording limit reached (60s)", Toast.LENGTH_SHORT).show()
            stopVoiceRecording(discard = false)
        }
    }

    // Text Selection Tracking (preserves selection when overlay is tapped)
    private var lastSelectionStart = -1
    private var lastSelectionEnd = -1

    // Vertical Tone Menu State
    private var toneMenuView: View? = null
    private var isToneMenuAttached = false

    // Visual state controller to eliminate desynchronization and isolated (X) icons
    enum class OverlayVisualState {
        IDLE,
        RECORDING,
        LOADING
    }

    private var currentVisualState = OverlayVisualState.IDLE
    private var isLoading = false

    private fun applyOverlayVisualState(state: OverlayVisualState) {
        currentVisualState = state
        val btn = btnRewrite ?: return
        when (state) {
            OverlayVisualState.IDLE -> {
                progressBar?.visibility = View.GONE
                layoutVoiceVisualizer?.visibility = View.GONE
                layoutWaveBars?.visibility = View.GONE
                ivIcon?.visibility = View.VISIBLE
                updateModeIcon()
                btn.setBackgroundResource(R.drawable.bg_floating_ai_pill)
                btn.isEnabled = true
                btn.alpha = 1.0f
                val lp = btn.layoutParams
                if (lp != null && lp.width != badgeSizePx) {
                    lp.width = badgeSizePx
                    btn.layoutParams = lp
                }
            }
            OverlayVisualState.RECORDING -> {
                progressBar?.visibility = View.GONE
                ivIcon?.visibility = View.GONE
                layoutVoiceVisualizer?.visibility = View.VISIBLE
                layoutWaveBars?.visibility = View.VISIBLE
                btn.setBackgroundResource(R.drawable.bg_recording_capsule)
                btn.isEnabled = true
                btn.alpha = 1.0f
            }
            OverlayVisualState.LOADING -> {
                layoutVoiceVisualizer?.visibility = View.GONE
                layoutWaveBars?.visibility = View.GONE
                ivIcon?.visibility = View.GONE
                progressBar?.visibility = View.VISIBLE
                btn.setBackgroundResource(R.drawable.bg_floating_ai_pill)
                btn.isEnabled = false
                btn.alpha = 0.75f
                val lp = btn.layoutParams
                if (lp != null && lp.width != badgeSizePx) {
                    lp.width = badgeSizePx
                    btn.layoutParams = lp
                }
            }
        }
    }

    private var isOverlayAttached = false
    private var isHiding = false
    private var isUserDragging = false
    private var lastTapTime = 0L
    private var lastKeyboardTopY = -1
    private var lastKnownKeyboardTop = -1
    private var activeDockedKeyboardTop = -1
    private var lastInteractedInputNode: AccessibilityNodeInfo? = null

    private val singleTapRunnable = Runnable {
        lastTapTime = 0L
        if (activeMode == MODE_VOICE) {
            startVoiceRecording()
        } else {
            handleTextRewriteClicked()
        }
    }

    private val keyboardCheckRunnable = Runnable {
        evaluateKeyboardVisibility()
    }

    private val hideOverlayRunnable = Runnable {
        hideOverlaySmoothly()
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val badgeSizePx: Int
        get() = (BADGE_SIZE_DP * resources.displayMetrics.density).toInt()

    private val capsuleExpandedWidthPx: Int
        get() = (CAPSULE_EXPANDED_WIDTH_DP * resources.displayMetrics.density).toInt()

    private val rootPaddingPx: Int
        get() = (ROOT_PADDING_DP * resources.displayMetrics.density).toInt()

    private val badgeMarginEdgePx: Int
        get() = (BADGE_MARGIN_EDGE_DP * resources.displayMetrics.density).toInt()

    private fun isPlaceholderText(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val clean = text.trim().lowercase()
        return CHAT_PLACEHOLDERS.contains(clean) || (clean.startsWith("message ") && clean.length <= 16)
    }

    private val badgeGapAboveKeyboardPx: Int
        get() = (BADGE_GAP_ABOVE_KEYBOARD_DP * resources.displayMetrics.density).toInt()

    private val defaultYOffsetPx: Int
        get() = -(badgeSizePx + badgeGapAboveKeyboardPx)

    private val overlayLayoutParams by lazy {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val savedSnapSide = prefs.getString(PREF_PILL_SNAP_SIDE, "LEFT") ?: "LEFT"
        val screenWidth = resources.displayMetrics.widthPixels
        val initialX = if (savedSnapSide == "RIGHT") {
            screenWidth - badgeSizePx - badgeMarginEdgePx - rootPaddingPx
        } else {
            badgeMarginEdgePx - rootPaddingPx
        }

        WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowAnimations = 0
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = initialX
            y = 0
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        Log.d(TAG, "Keyflow RewriteAccessibilityService connected")
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initOverlayView()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        return super.onUnbind(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                "Keyflow Voice Dictation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active voice dictation recording"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun initOverlayView() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_toolbar, null).apply {
            btnRewrite = findViewById(R.id.btnRewrite)
            ivIcon = findViewById(R.id.ivIcon)
            progressBar = findViewById(R.id.progressBar)
            layoutVoiceVisualizer = findViewById(R.id.layoutVoiceVisualizer)
            btnCancelVoice = findViewById(R.id.btnCancelVoice)
            btnDoneVoice = findViewById(R.id.btnDoneVoice)
            layoutWaveBars = findViewById(R.id.layoutWaveBars)

            waveBars[0] = findViewById(R.id.waveBar1)
            waveBars[1] = findViewById(R.id.waveBar2)
            waveBars[2] = findViewById(R.id.waveBar3)
            waveBars[3] = findViewById(R.id.waveBar4)
            waveBars[4] = findViewById(R.id.waveBar5)
            waveBars[5] = findViewById(R.id.waveBar6)
            waveBars[6] = findViewById(R.id.waveBar7)
            waveBars[7] = findViewById(R.id.waveBar8)
            waveBars[8] = findViewById(R.id.waveBar9)
            waveBars[9] = findViewById(R.id.waveBar10)
            waveBars[10] = findViewById(R.id.waveBar11)

            btnCancelVoice?.setOnClickListener {
                if (isRecording) {
                    stopVoiceRecording(discard = true)
                }
            }

            btnDoneVoice?.setOnClickListener {
                if (isRecording) {
                    stopVoiceRecording(discard = false)
                }
            }

            btnRewrite?.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
            btnRewrite?.clipToOutline = true

            // Restore active mode (Voice-to-Text is default)
            val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
            activeMode = prefs.getString(PREF_ACTIVE_MODE, MODE_VOICE) ?: MODE_VOICE
            applyOverlayVisualState(OverlayVisualState.IDLE)

            setupDragAndClickGesture(this, btnRewrite ?: this)
        }
    }

    private fun updateModeIcon() {
        val resId = if (activeMode == MODE_VOICE) {
            R.drawable.ic_mode_voice_to_text
        } else {
            R.drawable.ic_mode_text_to_text
        }
        ivIcon?.setImageResource(resId)
    }

    /**
     * Toggles mode between Voice-to-Text and Text-to-Text with smooth 3D flip card physics.
     */
    private fun switchMode() {
        val newMode = if (activeMode == MODE_VOICE) MODE_TEXT else MODE_VOICE
        activeMode = newMode
        getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ACTIVE_MODE, activeMode)
            .apply()

        btnRewrite?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

        // Smooth 3D Y-axis flip animation
        btnRewrite?.animate()
            ?.rotationY(90f)
            ?.setDuration(110)
            ?.withEndAction {
                applyOverlayVisualState(OverlayVisualState.IDLE)
                btnRewrite?.rotationY = -90f
                btnRewrite?.animate()
                    ?.rotationY(0f)
                    ?.setDuration(110)
                    ?.start()
            }
            ?.start()

        val modeLabel = if (activeMode == MODE_VOICE) "Voice-to-Text Mode" else "Text-to-Text Mode"
        Toast.makeText(this, "Keyflow: $modeLabel", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Switched active mode to: $activeMode")
    }

    /**
     * Implements Long-Press for Tone Menu, Press-to-Drag with Magnetic Snapping,
     * Double-Tap for Mode Switching, and Single-Tap for Action execution.
     */
    private fun setupDragAndClickGesture(rootView: View, touchTarget: View) {
        var initialTouchRawX = 0f
        var initialTouchRawY = 0f
        var lastTouchRawX = 0f
        var lastTouchRawY = 0f
        var dragStartParamX = 0
        var dragStartParamY = 0
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        var isDragging = false
        var longPressTriggered = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val longPressRunnable = Runnable {
            if (isDragging) return@Runnable
            longPressTriggered = true
            lastTapTime = 0L
            mainHandler.removeCallbacks(singleTapRunnable)

            // Cancel any ongoing magnetic snap
            snapAnimator?.cancel()

            touchTarget.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            // Tactile liquid-glass pulse on the pill
            touchTarget.animate()
                .scaleX(1.10f)
                .scaleY(1.10f)
                .setDuration(160)
                .setInterpolator(OvershootInterpolator(1.4f))
                .withEndAction {
                    touchTarget.animate()
                        .scaleX(1.04f)
                        .scaleY(1.04f)
                        .setDuration(120)
                        .start()
                }
                .start()

            // Open the vertical liquid-glass Tone Menu for the active mode
            showToneMenu()
        }

        touchTarget.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    initialTouchRawX = event.rawX
                    initialTouchRawY = event.rawY
                    lastTouchRawX = event.rawX
                    lastTouchRawY = event.rawY
                    dragStartParamX = overlayLayoutParams.x
                    dragStartParamY = overlayLayoutParams.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    isDragging = false
                    isUserDragging = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lastTouchRawX = event.rawX
                    lastTouchRawY = event.rawY

                    if (!longPressTriggered) {
                        val moveDist = hypot((event.rawX - initialTouchRawX).toDouble(), (event.rawY - initialTouchRawY).toDouble())
                        if (moveDist > touchSlop) {
                            // Finger moved past touch slop -> Immediate press and drag!
                            lastTapTime = 0L
                            mainHandler.removeCallbacks(longPressRunnable)
                            mainHandler.removeCallbacks(singleTapRunnable)
                            isDragging = true
                            isUserDragging = true
                            dismissToneMenu()
                        }
                    }

                    if (isDragging) {
                        val dx = (event.rawX - dragStartRawX).toInt()
                        val dy = (event.rawY - dragStartRawY).toInt()

                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val totalViewWidth = rootView.width.takeIf { it > 0 } ?: (badgeSizePx + 2 * rootPaddingPx)
                        val totalViewHeight = rootView.height.takeIf { it > 0 } ?: (badgeSizePx + 2 * rootPaddingPx)

                        val minX = badgeMarginEdgePx - rootPaddingPx
                        val maxX = maxOf(minX, screenWidth - totalViewWidth + rootPaddingPx - badgeMarginEdgePx)
                        val minY = (24 * resources.displayMetrics.density).toInt() - rootPaddingPx
                        val maxY = maxOf(minY, screenHeight - totalViewHeight)

                        val newX = (dragStartParamX + dx).coerceIn(minX, maxX)
                        val newY = (dragStartParamY + dy).coerceIn(minY, maxY)

                        if (newX != overlayLayoutParams.x || newY != overlayLayoutParams.y) {
                            overlayLayoutParams.x = newX
                            overlayLayoutParams.y = newY

                            if (isOverlayAttached && overlayView != null) {
                                try {
                                    windowManager.updateViewLayout(overlayView, overlayLayoutParams)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error updating dragged position", e)
                                }
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    // Smoothly reset pill scale back to normal
                    touchTarget.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(140)
                        .start()

                    if (isDragging) {
                        lastTapTime = 0L
                        mainHandler.removeCallbacks(singleTapRunnable)
                        isDragging = false
                        isUserDragging = false

                        // Execute Magnetic Edge Snapping
                        val screenWidth = resources.displayMetrics.widthPixels
                        val totalViewWidth = rootView.width.takeIf { it > 0 } ?: (badgeSizePx + 2 * rootPaddingPx)
                        val pillCenterX = overlayLayoutParams.x + (totalViewWidth / 2)

                        val snapSide = if (pillCenterX < screenWidth / 2) "LEFT" else "RIGHT"
                        val targetSnapX = if (snapSide == "LEFT") {
                            badgeMarginEdgePx - rootPaddingPx
                        } else {
                            screenWidth - badgeSizePx - badgeMarginEdgePx - rootPaddingPx
                        }

                        animateSnapTo(targetSnapX, snapSide)
                    } else if (longPressTriggered) {
                        // Hold was triggered and vertical menu is open. Do not trigger action.
                        lastTapTime = 0L
                        mainHandler.removeCallbacks(singleTapRunnable)
                        isUserDragging = false
                    } else if (isRecording) {
                        // User tapped the recording capsule body -> finish recording and transcribe!
                        lastTapTime = 0L
                        mainHandler.removeCallbacks(singleTapRunnable)
                        isUserDragging = false
                        stopVoiceRecording(discard = false)
                    } else {
                        isUserDragging = false
                        val now = SystemClock.uptimeMillis()

                        if (now - lastTapTime <= DOUBLE_TAP_THRESHOLD_MS) {
                            // Double-Tap detected! Instantly switch mode with 3D flip (ZERO recording started!)
                            mainHandler.removeCallbacks(singleTapRunnable)
                            lastTapTime = 0L
                            switchMode()
                        } else {
                            // First tap: light tactile feedback & schedule single-tap action
                            lastTapTime = now
                            touchTarget.animate()
                                .scaleX(0.92f)
                                .scaleY(0.92f)
                                .setDuration(70)
                                .withEndAction {
                                    touchTarget.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                                }
                                .start()

                            mainHandler.removeCallbacks(singleTapRunnable)
                            mainHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_THRESHOLD_MS)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    lastTapTime = 0L
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.removeCallbacks(singleTapRunnable)
                    touchTarget.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    isDragging = false
                    isUserDragging = false
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Smoothly glides the pill to the Left or Right edge with balanced padding.
     */
    private fun animateSnapTo(targetX: Int, snapSide: String) {
        val startX = overlayLayoutParams.x
        val currentY = overlayLayoutParams.y

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animation ->
                val animatedX = animation.animatedValue as Int
                overlayLayoutParams.x = animatedX
                if (isOverlayAttached && overlayView != null) {
                    try {
                        windowManager.updateViewLayout(overlayView, overlayLayoutParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating snap position", e)
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    savePillPosition(targetX, currentY, snapSide)
                }
            })
            start()
        }
    }

    private fun savePillPosition(currentX: Int, currentY: Int, snapSide: String) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val keyboardTop = lastKnownKeyboardTop
        val currentVisibleY = currentY + rootPaddingPx

        val yOffset = if (keyboardTop > 0) {
            val offset = currentVisibleY - keyboardTop
            val screenHeight = resources.displayMetrics.heightPixels
            if (offset in -(screenHeight * 0.7f).toInt()..-10) {
                offset
            } else {
                defaultYOffsetPx
            }
        } else {
            defaultYOffsetPx
        }

        prefs.edit()
            .putString(PREF_PILL_SNAP_SIDE, snapSide)
            .putInt(PREF_PILL_X, currentX)
            .putInt(PREF_PILL_Y_OFFSET, yOffset)
            .apply()

        Log.d(TAG, "Saved position: SnapSide=$snapSide, X=$currentX, YOffset=$yOffset (keyboardTop=$keyboardTop)")
    }

    private fun scheduleKeyboardCheck(delayMs: Long = 50L) {
        mainHandler.removeCallbacks(keyboardCheckRunnable)
        mainHandler.postDelayed(keyboardCheckRunnable, delayMs)
    }

    private fun scheduleHideOverlay() {
        if (!isOverlayAttached || isHiding) return
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, 120L)
    }

    private fun cancelPendingHide() {
        mainHandler.removeCallbacks(hideOverlayRunnable)
    }

    private fun updateLastInteractedNode(newNode: AccessibilityNodeInfo) {
        try {
            val copy = AccessibilityNodeInfo.obtain(newNode)
            if (lastInteractedInputNode != null) {
                lastInteractedInputNode?.recycle()
            }
            lastInteractedInputNode = copy
        } catch (e: Exception) {
            lastInteractedInputNode = newNode
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleKeyboardCheck(40L)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                scheduleKeyboardCheck(50L)
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    updateLastInteractedNode(source)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Tapping on an input field commonly opens the keyboard
                scheduleKeyboardCheck(60L)
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    updateLastInteractedNode(source)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    updateLastInteractedNode(source)
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    updateLastInteractedNode(source)
                    val from = event.fromIndex
                    val to = event.toIndex
                    if (from >= 0 && to >= 0 && from != to) {
                        lastSelectionStart = minOf(from, to)
                        lastSelectionEnd = maxOf(from, to)
                        Log.d(TAG, "Captured text selection: [$lastSelectionStart, $lastSelectionEnd]")
                    } else {
                        lastSelectionStart = -1
                        lastSelectionEnd = -1
                    }
                }
            }
        }
    }

    /**
     * Inspects window hierarchy to detect the soft keyboard (TYPE_INPUT_METHOD)
     * and positions the floating AI pill consistently above the keyboard across all apps.
     */
    private fun evaluateKeyboardVisibility() {
        if (isUserDragging) return

        val windowList = windows
        if (windowList == null) {
            scheduleHideOverlay()
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        var mainKeyboardWindow: AccessibilityWindowInfo? = null
        var maxKeyboardArea = 0

        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val b = Rect()
                window.getBoundsInScreen(b)
                // Filter for genuine soft keyboard window: reasonable width and height on screen
                if (b.height() > 120 && b.width() >= (screenWidth * 0.4) && b.top < screenHeight && b.bottom > 0) {
                    val area = b.width() * b.height()
                    if (area > maxKeyboardArea) {
                        maxKeyboardArea = area
                        mainKeyboardWindow = window
                    }
                }
            }
        }

        if (mainKeyboardWindow != null) {
            val bounds = Rect()
            mainKeyboardWindow.getBoundsInScreen(bounds)

            val isKeyboardOpen = bounds.height() > 120 && bounds.top < screenHeight && bounds.top > 0

            if (isKeyboardOpen) {
                cancelPendingHide()

                // If already attached, visible, and settled at this keyboard top (or small key popup delta < 120px):
                // DO NOT reposition or animate! Lock it firmly in place!
                if (isOverlayAttached && !isHiding && activeDockedKeyboardTop > 0) {
                    if (Math.abs(bounds.top - activeDockedKeyboardTop) < KEY_PREVIEW_IGNORE_THRESHOLD_PX) {
                        return
                    }
                }

                activeDockedKeyboardTop = bounds.top
                lastKnownKeyboardTop = bounds.top

                val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
                var savedYOffset = prefs.getInt(PREF_PILL_Y_OFFSET, defaultYOffsetPx)
                if (savedYOffset >= -10 || savedYOffset < -(screenHeight * 0.7f)) {
                    savedYOffset = defaultYOffsetPx
                }

                val savedSnapSide = prefs.getString(PREF_PILL_SNAP_SIDE, "LEFT") ?: "LEFT"
                val targetX = if (savedSnapSide == "RIGHT") {
                    screenWidth - badgeSizePx - badgeMarginEdgePx - rootPaddingPx
                } else {
                    badgeMarginEdgePx - rootPaddingPx
                }

                val minY = (24 * resources.displayMetrics.density).toInt() - rootPaddingPx
                val maxY = bounds.top - badgeSizePx - (4 * resources.displayMetrics.density).toInt() - rootPaddingPx
                val targetY = (bounds.top + savedYOffset - rootPaddingPx).coerceIn(minY, maxOf(minY, maxY))

                lastKeyboardTopY = targetY
                showOverlayAt(targetX, targetY)
                return
            }
        }

        // Keyboard not detected; debounce hide smoothly to prevent transient window flicker
        if (isOverlayAttached) {
            scheduleHideOverlay()
        }
    }

    private fun showOverlayAt(x: Int, y: Int) {
        initOverlayView()
        val view = overlayView ?: return

        // If overlay is already attached, visible, and placed at (x, y), do nothing
        if (isOverlayAttached && !isHiding && overlayLayoutParams.x == x && overlayLayoutParams.y == y) {
            return
        }

        overlayLayoutParams.x = x
        overlayLayoutParams.y = y

        if (!isOverlayAttached) {
            try {
                isHiding = false
                view.alpha = 0f
                view.translationY = 25f // Glide up in sync with Gboard slide-in
                windowManager.addView(view, overlayLayoutParams)
                isOverlayAttached = true
                view.visibility = View.VISIBLE

                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()

                Log.d(TAG, "Attached floating AI pill at X=$x, Y=$y")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay view", e)
            }
        } else {
            try {
                if (isHiding) {
                    view.animate().cancel()
                    isHiding = false
                    view.alpha = 1f
                    view.translationY = 0f
                }
                view.visibility = View.VISIBLE
                windowManager.updateViewLayout(view, overlayLayoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update overlay view position", e)
            }
        }
    }

    /**
     * Glides the pill down and fades out in sync with Gboard closing.
     */
    private fun hideOverlaySmoothly() {
        mainHandler.removeCallbacks(keyboardCheckRunnable)
        cancelPendingHide()
        snapAnimator?.cancel()
        dismissToneMenu()
        val view = overlayView ?: return

        if (isOverlayAttached && !isHiding) {
            isHiding = true
            view.animate()
                .alpha(0f)
                .translationY(25f) // Glide down with Gboard
                .setDuration(120)
                .withEndAction {
                    if (isOverlayAttached && isHiding) {
                        try {
                            windowManager.removeViewImmediate(view)
                            Log.d(TAG, "Removed floating AI pill synchronously")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to remove overlay view", e)
                        } finally {
                            isOverlayAttached = false
                            isHiding = false
                            lastKeyboardTopY = -1
                            activeDockedKeyboardTop = -1
                        }
                    }
                }
                .start()
        }
    }

    /**
     * Checks if an accessibility node is a valid editable input candidate across Native,
     * React Native (ChatGPT), Compose, Flutter, or WebViews.
     */
    private fun isCandidateInputNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true

        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Compose", ignoreCase = true) ||
            className.contains("Editor", ignoreCase = true)) {
            return true
        }

        val hasTextAction = node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT ||
            it.id == AccessibilityNodeInfo.ACTION_PASTE ||
            it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION
        }
        if (hasTextAction) return true

        // Focused view with non-empty text or content description
        if (node.isFocused && (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty())) {
            return true
        }

        // Focusable or focused view in hybrid apps (Rapido / Flutter / React Native)
        if (node.isFocusable && (node.isFocused || !node.text.isNullOrEmpty())) {
            return true
        }

        return false
    }

    private fun collectInputCandidates(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > 40) return
        if (isCandidateInputNode(node)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectInputCandidates(child, results, depth + 1)
            }
        }
    }

    /**
     * Extracts text from an AccessibilityNodeInfo, taking into account hints,
     * content descriptions, and child elements (common in React Native / Compose).
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        // In Android 8.0+ (API 26), AccessibilityNodeInfo explicitly flags hint/placeholder text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            return ""
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()

        // Filter out hint/placeholder (e.g. "Message", "Type a message", "Message ChatGPT")
        if (text.isNotEmpty()) {
            if (text.equals(hint, ignoreCase = true) || isPlaceholderText(text)) {
                return ""
            }
            return text
        }

        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
        if (contentDesc.isNotEmpty() && !contentDesc.equals(hint, ignoreCase = true) && !isPlaceholderText(contentDesc)) {
            return contentDesc
        }

        // Recursively inspect children for text (e.g. React Native inner text view)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractTextFromNode(child)
            if (childText.isNotEmpty() && !isPlaceholderText(childText)) {
                return childText
            }
        }

        return ""
    }

    /**
     * Finds the currently focused or active input node across active windows.
     * Multi-tier resolution strategy tailored for React Native (ChatGPT),
     * Jetpack Compose, Flutter, and standard Android views.
     */
    private fun findActiveInputNode(): AccessibilityNodeInfo? {
        // Tier 1: Check tracked input node from recent typing/clicking
        lastInteractedInputNode?.let { node ->
            if (node.refresh() && isCandidateInputNode(node)) {
                if (node.isFocused) {
                    Log.d(TAG, "Tier 1: Found active focused input node: ${node.className}")
                    return node
                }
                val text = extractTextFromNode(node)
                if (text.isNotEmpty()) {
                    Log.d(TAG, "Tier 1: Found active tracked input node with text: '$text'")
                    return node
                }
            }
        }

        // Tier 2: Check standard FOCUS_INPUT on rootInActiveWindow
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
            if (isCandidateInputNode(node)) {
                Log.d(TAG, "Tier 2: Found input node via root FOCUS_INPUT (${node.className})")
                return node
            }
        }

        // Tier 3: Check FOCUS_ACCESSIBILITY on rootInActiveWindow
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
            if (isCandidateInputNode(node)) {
                Log.d(TAG, "Tier 3: Found input node via root FOCUS_ACCESSIBILITY (${node.className})")
                return node
            }
        }

        // Tier 4: Check all application windows for FOCUS_INPUT or FOCUS_ACCESSIBILITY
        val currentWindows = windows
        if (currentWindows != null) {
            val sortedWindows = currentWindows.sortedByDescending { it.isActive || it.isFocused }
            for (window in sortedWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                        if (isCandidateInputNode(node)) {
                            Log.d(TAG, "Tier 4: Found input node in window ${window.id} via FOCUS_INPUT")
                            return node
                        }
                    }
                    root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
                        if (isCandidateInputNode(node)) {
                            Log.d(TAG, "Tier 4: Found input node in window ${window.id} via FOCUS_ACCESSIBILITY")
                            return node
                        }
                    }
                }
            }
        }

        // Tier 5: Recursive hierarchy search across active windows
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { collectInputCandidates(it, candidates) }

        val hasCandidateWithText = candidates.any { extractTextFromNode(it).isNotEmpty() }
        if (!hasCandidateWithText && currentWindows != null) {
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    window.root?.let { collectInputCandidates(it, candidates) }
                }
            }
        }

        if (candidates.isNotEmpty()) {
            val chosen = when {
                // Priority 5A: Focused candidate with non-empty text
                candidates.any { it.isFocused && extractTextFromNode(it).isNotEmpty() } -> {
                    val match = candidates.first { it.isFocused && extractTextFromNode(it).isNotEmpty() }
                    Log.d(TAG, "Tier 5A: Found focused candidate with text: '${extractTextFromNode(match)}'")
                    match
                }

                // Priority 5B: Any candidate reporting isFocused == true
                candidates.any { it.isFocused } -> {
                    val match = candidates.first { it.isFocused }
                    Log.d(TAG, "Tier 5B: Found focused candidate: ${match.className}")
                    match
                }

                // Priority 5C: Proximity to keyboard top (ChatGPT input sits directly above soft keyboard)
                lastKnownKeyboardTop > 0 -> {
                    val candidatesAboveKeyboard = candidates.map { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        Pair(node, rect)
                    }.filter { (_, rect) ->
                        rect.bottom <= lastKnownKeyboardTop + 150 && rect.top < lastKnownKeyboardTop
                    }

                    val withText = candidatesAboveKeyboard.firstOrNull { extractTextFromNode(it.first).isNotEmpty() }
                    if (withText != null) {
                        Log.d(TAG, "Tier 5C: Found candidate above keyboard with text: '${extractTextFromNode(withText.first)}'")
                        withText.first
                    } else {
                        val closest = candidatesAboveKeyboard.maxByOrNull { it.second.bottom }
                        if (closest != null) {
                            Log.d(TAG, "Tier 5C: Found candidate closest to keyboard top at Y=${closest.second.bottom}")
                            closest.first
                        } else {
                            candidates.firstOrNull { extractTextFromNode(it).isNotEmpty() } ?: candidates.first()
                        }
                    }
                }

                // Priority 5D: Any candidate with text
                candidates.any { extractTextFromNode(it).isNotEmpty() } -> {
                    val match = candidates.first { extractTextFromNode(it).isNotEmpty() }
                    Log.d(TAG, "Tier 5D: Found candidate with non-empty text: '${extractTextFromNode(match)}'")
                    match
                }

                // Fallback: First candidate
                else -> candidates.first()
            }

            for (node in candidates) {
                if (node != chosen) {
                    node.recycle()
                }
            }
            return chosen
        }

        // Fallback: Tracked node even if empty
        lastInteractedInputNode?.let {
            if (it.refresh()) return it
        }

        return null
    }

    /**
     * Visualizer polling runnable: polls mediaRecorder maxAmplitude every 40ms,
     * driving the organic living wave bars inspired by Whisper Flow.
     */
    private val visualizerRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return

            val rawAmp = try {
                mediaRecorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }

            // Exponential Moving Average filter
            smoothedAmplitude = smoothedAmplitude * 0.40f + rawAmp * 0.60f
            updateLivingWaveBars(livingWavePhase, smoothedAmplitude)

            mainHandler.postDelayed(this, 40L)
        }
    }

    private fun startLivingWaveAnimation() {
        if (livingWavePhaseAnimator?.isRunning == true) return
        livingWavePhaseAnimator?.cancel()
        livingWavePhaseAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 1300L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                livingWavePhase = anim.animatedValue as Float
                updateLivingWaveBars(livingWavePhase, smoothedAmplitude)
            }
            start()
        }
    }

    private fun stopLivingWaveAnimation() {
        livingWavePhaseAnimator?.cancel()
        livingWavePhaseAnimator = null
        for (bar in waveBars) {
            bar?.scaleY = 0.4f
        }
    }

    private fun updateLivingWaveBars(phase: Float, amp: Float) {
        val normalized = ((amp - 1000f) / 16000f).coerceIn(0f, 1f)
        for (i in 0 until 11) {
            val bar = waveBars[i] ?: continue
            val centerDist = kotlin.math.abs(i - 5) / 5.5f
            val bellWeight = (1.0f - centerDist).coerceIn(0.3f, 1.0f)

            // Organic sine wave undulation (living wave object)
            val ripple = kotlin.math.sin(phase + i * 0.55f).toFloat()
            val restingScale = 0.40f + 0.25f * ripple * bellWeight

            // Live mic amplitude boost
            val voiceScale = normalized * (1.2f + 1.2f * bellWeight)

            val totalScale = (restingScale + voiceScale).coerceIn(0.25f, 2.3f)
            bar.scaleY = totalScale
        }
    }

    private fun expandCapsuleForRecording() {
        val btn = btnRewrite ?: return
        if (isCapsuleExpanded) return
        isCapsuleExpanded = true

        val screenWidth = resources.displayMetrics.widthPixels
        val targetWidth = capsuleExpandedWidthPx
        val currentX = overlayLayoutParams.x

        if (currentX + targetWidth > screenWidth - badgeMarginEdgePx) {
            overlayLayoutParams.x = (screenWidth - targetWidth - badgeMarginEdgePx).coerceAtLeast(badgeMarginEdgePx)
            if (isOverlayAttached && overlayView != null) {
                try {
                    windowManager.updateViewLayout(overlayView, overlayLayoutParams)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating expanded position", e)
                }
            }
        }

        applyOverlayVisualState(OverlayVisualState.RECORDING)

        val anim = ValueAnimator.ofInt(badgeSizePx, targetWidth).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                val lp = btn.layoutParams
                lp.width = w
                btn.layoutParams = lp
            }
        }
        anim.start()

        startLivingWaveAnimation()
    }

    private fun collapseCapsule(onEnd: () -> Unit = {}) {
        val btn = btnRewrite ?: return
        if (!isCapsuleExpanded) {
            applyOverlayVisualState(if (isLoading) OverlayVisualState.LOADING else OverlayVisualState.IDLE)
            onEnd()
            return
        }
        isCapsuleExpanded = false
        stopLivingWaveAnimation()

        val currentWidth = btn.width.takeIf { it > 0 } ?: capsuleExpandedWidthPx
        val anim = ValueAnimator.ofInt(currentWidth, badgeSizePx).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { va ->
                val w = va.animatedValue as Int
                val lp = btn.layoutParams
                lp.width = w
                btn.layoutParams = lp
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyOverlayVisualState(if (isLoading) OverlayVisualState.LOADING else OverlayVisualState.IDLE)

                    val screenWidth = resources.displayMetrics.widthPixels
                    val pillCenterX = overlayLayoutParams.x + (badgeSizePx / 2)
                    val snapSide = if (pillCenterX < screenWidth / 2) "LEFT" else "RIGHT"
                    val targetSnapX = if (snapSide == "LEFT") {
                        badgeMarginEdgePx - rootPaddingPx
                    } else {
                        screenWidth - badgeSizePx - badgeMarginEdgePx - rootPaddingPx
                    }
                    animateSnapTo(targetSnapX, snapSide)
                    onEnd()
                }
            })
        }
        anim.start()
    }

    /**
     * Starts voice capture with MediaRecorder in MPEG_4 / AAC at 16kHz Mono 64kbps,
     * running in an Android 14 compliant Foreground Service with Audio Focus.
     */
    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for Voice Mode", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MainActivity for mic permission", e)
            }
            return
        }

        try {
            // 1. Request Audio Focus so background media ducks or pauses cleanly
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                audioManager?.requestAudioFocus(focusReq)
                audioFocusRequest = focusReq
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }

            // 2. Start Foreground Service with MICROPHONE type for Android 14 compliance
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
                .setContentTitle("Keyflow Voice")
                .setContentText("Listening...")
                .setSmallIcon(R.drawable.ic_mode_voice_to_text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    RECORDING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(RECORDING_NOTIFICATION_ID, notification)
            }

            val audioFile = File(cacheDir, "recording_${System.currentTimeMillis()}.m4a")
            currentAudioFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Direct hardware microphone input for maximum clarity across all devices
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            recordingStartTime = SystemClock.uptimeMillis()
            smoothedAmplitude = 0f

            btnRewrite?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            // Smoothly expand pill into Whisper Flow recording capsule
            expandCapsuleForRecording()

            // Start live amplitude waveform polling
            mainHandler.removeCallbacks(visualizerRunnable)
            mainHandler.post(visualizerRunnable)

            // Safety 60s timeout
            mainHandler.removeCallbacks(maxRecordingTimeoutRunnable)
            mainHandler.postDelayed(maxRecordingTimeoutRunnable, 60_000L)

            Log.d(TAG, "Started voice recording to ${audioFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, "Failed to start mic: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            isRecording = false
            collapseCapsule()
            applyOverlayVisualState(OverlayVisualState.IDLE)
        }
    }

    /**
     * Stops voice recording, collapses the capsule, and sends audio to Groq Whisper for transcription.
     */
    private fun stopVoiceRecording(discard: Boolean = false) {
        if (!isRecording && mediaRecorder == null) return

        val duration = SystemClock.uptimeMillis() - recordingStartTime
        isRecording = false
        mainHandler.removeCallbacks(visualizerRunnable)
        mainHandler.removeCallbacks(maxRecordingTimeoutRunnable)

        // 1. Release Foreground Service and Notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(RECORDING_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground service", e)
        }

        // 2. Abandon Audio Focus
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }

        // 3. Smoothly collapse capsule back to 44dp circular pill
        collapseCapsule()

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder", e)
        } finally {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            mediaRecorder = null
        }

        val audioFile = currentAudioFile
        if (discard || audioFile == null || !audioFile.exists() || audioFile.length() < 100 || duration < MIN_RECORDING_DURATION_MS) {
            audioFile?.delete()
            currentAudioFile = null
            applyOverlayVisualState(OverlayVisualState.IDLE)
            return
        }

        btnRewrite?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        setLoading(true)

        serviceScope.launch {
            try {
                val response = requestTranscribeFromBackend(audioFile)
                val finalText = response?.second ?: response?.first
                if (!finalText.isNullOrBlank()) {
                    val inputNode = findActiveInputNode()
                    if (inputNode != null) {
                        val fullText = extractTextFromNode(inputNode)
                        val cleanExistingText = if (isPlaceholderText(fullText)) "" else fullText
                        val nodeSelStart = inputNode.textSelectionStart
                        val nodeSelEnd = inputNode.textSelectionEnd
                        val (selStart, selEnd, hasSelection) = when {
                            nodeSelStart in 0..cleanExistingText.length && nodeSelEnd in 0..cleanExistingText.length && nodeSelStart != nodeSelEnd -> {
                                val s = minOf(nodeSelStart, nodeSelEnd)
                                val e = maxOf(nodeSelStart, nodeSelEnd)
                                Triple(s, e, true)
                            }
                            lastSelectionStart in 0..cleanExistingText.length && lastSelectionEnd in 0..cleanExistingText.length && lastSelectionStart < lastSelectionEnd -> {
                                Triple(lastSelectionStart, lastSelectionEnd, true)
                            }
                            else -> Triple(0, 0, false)
                        }

                        if (hasSelection) {
                            val newFull = cleanExistingText.substring(0, selStart) + finalText + cleanExistingText.substring(selEnd)
                            injectText(inputNode, newFull, newCursorPos = selStart + finalText.length)
                            lastSelectionStart = -1
                            lastSelectionEnd = -1
                        } else if (cleanExistingText.isNotEmpty()) {
                            val separator = if (cleanExistingText.endsWith(" ") || cleanExistingText.endsWith("\n")) "" else " "
                            val newFull = cleanExistingText + separator + finalText
                            injectText(inputNode, newFull, newCursorPos = newFull.length)
                        } else {
                            injectText(inputNode, finalText, newCursorPos = finalText.length)
                        }
                    } else {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Keyflow Voice", finalText))
                        Toast.makeText(this@RewriteAccessibilityService, "Transcribed! Copied to clipboard.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@RewriteAccessibilityService, "No speech detected. Please speak closer to mic.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice transcription failed", e)
                Toast.makeText(this@RewriteAccessibilityService, "Voice error: ${e.localizedMessage ?: "Failed"}", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
                audioFile.delete()
                currentAudioFile = null
            }
        }
    }

    /**
     * Handles single-tap in Text Mode: selection-aware rewrite of full text or highlighted snippet.
     * When text or a line is selected, only that selection is rewritten and injected.
     */
    private fun handleTextRewriteClicked() {
        val inputNode = findActiveInputNode()
        if (inputNode == null) {
            Toast.makeText(this, "No active text field found", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "handleTextRewriteClicked: findActiveInputNode returned null")
            return
        }

        val fullText = extractTextFromNode(inputNode)
        if (fullText.isEmpty()) {
            Toast.makeText(this, "Text field is empty", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "handleTextRewriteClicked: extractTextFromNode returned empty text")
            return
        }

        // Selection-Aware: Check both accessibility node selection and cached selection
        val nodeSelStart = inputNode.textSelectionStart
        val nodeSelEnd = inputNode.textSelectionEnd
        val (selStart, selEnd, hasSelection) = when {
            nodeSelStart in 0..fullText.length && nodeSelEnd in 0..fullText.length && nodeSelStart != nodeSelEnd -> {
                val s = minOf(nodeSelStart, nodeSelEnd)
                val e = maxOf(nodeSelStart, nodeSelEnd)
                Triple(s, e, true)
            }
            lastSelectionStart in 0..fullText.length && lastSelectionEnd in 0..fullText.length && lastSelectionStart < lastSelectionEnd -> {
                Triple(lastSelectionStart, lastSelectionEnd, true)
            }
            else -> Triple(0, fullText.length, false)
        }

        val textToRewrite = if (hasSelection) {
            fullText.substring(selStart, selEnd)
        } else {
            fullText
        }

        Log.d(TAG, "Starting text rewrite [selection=$hasSelection ($selStart..$selEnd)]: '$textToRewrite'")
        setLoading(true)

        serviceScope.launch {
            try {
                val rewrittenText = requestRewriteFromBackend(textToRewrite)
                if (!rewrittenText.isNullOrBlank()) {
                    val targetNode = if (inputNode.refresh()) inputNode else (findActiveInputNode() ?: inputNode)
                    if (hasSelection) {
                        // Replace strictly the selected range
                        val newFullText = fullText.substring(0, selStart) + rewrittenText + fullText.substring(selEnd)
                        injectText(targetNode, newFullText, newCursorPos = selStart + rewrittenText.length)
                        lastSelectionStart = -1
                        lastSelectionEnd = -1
                    } else {
                        injectText(targetNode, rewrittenText, newCursorPos = rewrittenText.length)
                    }
                } else {
                    Toast.makeText(this@RewriteAccessibilityService, "Empty response from engine", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rewrite request failed", e)
                Toast.makeText(
                    this@RewriteAccessibilityService,
                    "Rewrite failed: ${e.localizedMessage ?: "Network error"}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun getCleanBaseUrl(rawUrl: String): String {
        var clean = rawUrl.trim().trimEnd('/')
        val suffixes = listOf("/rewrite", "/transcribe", "/api/rewrite", "/api/transcribe", "/api")
        for (suffix in suffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trimEnd('/')
                break
            }
        }
        return clean
    }

    /**
     * Executes asynchronous OkHttp POST call to the FastAPI backend for text rewriting.
     */
    private suspend fun requestRewriteFromBackend(text: String): String? = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val selectedTone = prefs.getString(PREF_TEXT_TONE, DEFAULT_TONE) ?: DEFAULT_TONE

        val payload = JSONObject().apply {
            put("text", text)
            put("tone", selectedTone)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val rawUrl = prefs.getString(KEY_BACKEND_URL, BACKEND_URL) ?: BACKEND_URL
        val baseUrl = getCleanBaseUrl(rawUrl)
        val targetUrl = "$baseUrl/rewrite"

        val request = Request.Builder()
            .url(targetUrl)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Rewrite failed (${response.code}): $errBody")
                throw IOException("HTTP ${response.code}: $errBody")
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonObject = JSONObject(responseBody)
            jsonObject.optString("rewritten_text", "")
        }
    }

    /**
     * Executes multipart audio upload to /transcribe endpoint.
     * Returns Pair(transcribedText, rewrittenText).
     */
    private suspend fun requestTranscribeFromBackend(audioFile: File): Pair<String, String>? = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val voiceTone = prefs.getString(PREF_VOICE_TONE, DEFAULT_TONE) ?: DEFAULT_TONE

        val rawUrl = prefs.getString(KEY_BACKEND_URL, BACKEND_URL) ?: BACKEND_URL
        val baseUrl = getCleanBaseUrl(rawUrl)
        val targetUrl = "$baseUrl/transcribe"

        val mediaType = "audio/m4a".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
            .addFormDataPart("tone", voiceTone)
            .build()

        val request = Request.Builder()
            .url(targetUrl)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "Transcribe failed (${response.code}): $errBody")
                throw IOException("HTTP ${response.code}: $errBody")
            }
            val responseBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(responseBody)
            val transcribed = json.optString("transcribed_text", "")
            val rewritten = json.optString("rewritten_text", transcribed)
            Pair(transcribed, rewritten)
        }
    }

    /**
     * Injects replacement text into the target node.
     * Hardened multi-stage pipeline:
     * 1. Immediate clipboard priming with rewritten text.
     * 2. Comprehensive candidate collection (target, parent, siblings, children).
     * 3. ACTION_SET_TEXT across candidate nodes with precise cursor placement.
     * 4. Safe-range selection + ACTION_PASTE.
     * 5. Click + ACTION_PASTE for hybrid frameworks (Flutter, Rapido, React Native).
     * 6. Safety Net: User-friendly clipboard prompt.
     */
    private fun injectText(targetNode: AccessibilityNodeInfo, newText: String, newCursorPos: Int = newText.length) {
        Log.d(TAG, "Attempting text injection into node: class=${targetNode.className}")

        // 1. Immediately prime system clipboard so user has it ready regardless of injection outcome
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Keyflow", newText)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }

        // 2. Gather candidate injection nodes (targetNode, parent, children, siblings)
        val candidateNodes = mutableListOf<AccessibilityNodeInfo>()
        candidateNodes.add(targetNode)

        targetNode.parent?.let { parent ->
            candidateNodes.add(parent)
            for (i in 0 until parent.childCount) {
                parent.getChild(i)?.let { sibling ->
                    if (sibling != targetNode && !candidateNodes.contains(sibling)) {
                        candidateNodes.add(sibling)
                    }
                }
            }
        }

        fun collectSubtree(n: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 4) return
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child ->
                    if (!candidateNodes.contains(child)) {
                        candidateNodes.add(child)
                    }
                    collectSubtree(child, depth + 1)
                }
            }
        }
        collectSubtree(targetNode)

        // 3. Primary Method: ACTION_SET_TEXT across candidate nodes
        val setTextArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        fun placeCursorAt(node: AccessibilityNodeInfo, pos: Int) {
            try {
                val clamped = pos.coerceIn(0, newText.length)
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, clamped)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, clamped)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            } catch (e: Exception) {
                // Ignore cursor positioning failure
            }
        }

        for (node in candidateNodes) {
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
                    Log.d(TAG, "Successfully injected text via ACTION_SET_TEXT on ${node.className}")
                    placeCursorAt(node, newCursorPos)
                    return
                }
            }
        }

        // Try direct ACTION_SET_TEXT on targetNode even if not advertised in actionList
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            Log.d(TAG, "Successfully injected text via direct ACTION_SET_TEXT on targetNode")
            placeCursorAt(targetNode, newCursorPos)
            return
        }

        Log.w(TAG, "ACTION_SET_TEXT returned false, applying selection + ACTION_PASTE for Flutter/React Native/Custom chats")

        // 4. Fallback Method: Focus + Selection + ACTION_PASTE
        for (node in candidateNodes) {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

                val textLen = node.text?.length ?: 0
                if (textLen > 0) {
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, minOf(textLen, 4000))
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                }

                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    Log.d(TAG, "Successfully injected text via ACTION_PASTE on ${node.className}")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during node paste attempt", e)
            }
        }

        // 5. Click + Paste fallback for hybrid frameworks (Flutter / Rapido chat)
        for (node in candidateNodes) {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    Log.d(TAG, "Successfully injected text via CLICK + ACTION_PASTE on ${node.className}")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during click+paste attempt", e)
            }
        }

        // 6. Safety Net: Text copied to clipboard
        Toast.makeText(this, "Copied to clipboard! Tap & paste.", Toast.LENGTH_SHORT).show()
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        applyOverlayVisualState(if (loading) OverlayVisualState.LOADING else OverlayVisualState.IDLE)
    }

    /**
     * Displays the minimal ChatGPT-inspired vertical tone selector menu anchored dynamically
     * above or below the floating pill based on screen placement.
     * Selects and saves tone independently for the currently active mode (Voice or Text).
     */
    private fun showToneMenu() {
        if (!isOverlayAttached || overlayView == null) return
        dismissToneMenu()

        val inflater = LayoutInflater.from(this)
        val menuView = inflater.inflate(R.layout.overlay_tone_menu, null) ?: return
        toneMenuView = menuView

        val chipRaw = menuView.findViewById<TextView>(R.id.chipToneRaw)
        val chipNormal = menuView.findViewById<TextView>(R.id.chipToneNormal)
        val chipPro = menuView.findViewById<TextView>(R.id.chipToneProfessional)

        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val tonePrefKey = if (activeMode == MODE_VOICE) PREF_VOICE_TONE else PREF_TEXT_TONE
        val currentTone = prefs.getString(tonePrefKey, DEFAULT_TONE) ?: DEFAULT_TONE

        updateToneChipsHighlight(currentTone, chipRaw, chipNormal, chipPro)

        chipRaw?.setOnClickListener { onToneSelected(TONE_RAW) }
        chipNormal?.setOnClickListener { onToneSelected(TONE_NORMAL) }
        chipPro?.setOnClickListener { onToneSelected(TONE_PROFESSIONAL) }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density

        // Measure menu bounds with exact allocated width (132dp)
        val menuWidthPx = (MENU_WIDTH_DP * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(menuWidthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        menuView.measure(widthSpec, heightSpec)
        val menuHeightPx = menuView.measuredHeight

        // Pill screen coordinates
        val pillScreenLeft = overlayLayoutParams.x + rootPaddingPx
        val pillVisibleTop = overlayLayoutParams.y + rootPaddingPx
        val pillVisibleBottom = pillVisibleTop + badgeSizePx
        val pillCenterY = pillVisibleTop + (badgeSizePx / 2)
        val isPillOnLeft = (pillScreenLeft + (badgeSizePx / 2)) < screenWidth / 2

        val gapPx = (8 * density).toInt()
        val minSafeTopPx = (36 * density).toInt() // Safe status bar clearance
        val maxAllowedBottom = if (activeDockedKeyboardTop > 0) {
            activeDockedKeyboardTop - (6 * density).toInt()
        } else {
            screenHeight - (32 * density).toInt()
        }

        val spaceAbove = pillVisibleTop - minSafeTopPx
        val canFitBelow = (pillVisibleBottom + gapPx + menuHeightPx) <= maxAllowedBottom
        val canFitAbove = spaceAbove >= (menuHeightPx + gapPx)

        val openDownward = when {
            pillCenterY < screenHeight * 0.45f && canFitBelow -> true
            !canFitAbove && canFitBelow -> true
            else -> false
        }

        val targetY: Int
        val pivotY: Float
        if (openDownward) {
            targetY = (pillVisibleBottom + gapPx).coerceAtMost(maxAllowedBottom - menuHeightPx)
            pivotY = 0f // Bloom downward from bottom of pill
        } else {
            targetY = (pillVisibleTop - menuHeightPx - gapPx).coerceAtLeast(minSafeTopPx)
            pivotY = menuHeightPx.toFloat() // Bloom upward from top of pill
        }

        val gravity = Gravity.TOP or (if (isPillOnLeft) Gravity.LEFT else Gravity.RIGHT)
        val targetX = badgeMarginEdgePx
        val pivotX = if (isPillOnLeft) 0f else menuWidthPx.toFloat()

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            windowAnimations = 0
            this.gravity = gravity
            width = menuWidthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = targetX
            y = targetY
        }

        menuView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissToneMenu()
                true
            } else {
                false
            }
        }

        menuView.alpha = 0f
        menuView.scaleX = 0.72f
        menuView.scaleY = 0.72f
        menuView.pivotX = pivotX
        menuView.pivotY = pivotY

        try {
            windowManager.addView(menuView, lp)
            isToneMenuAttached = true
            menuView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
            Log.d(TAG, "Tone menu attached for mode $activeMode: direction=${if (openDownward) "DOWN" else "UP"}, X=$targetX, Y=$targetY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach tone menu to WindowManager", e)
            toneMenuView = null
            isToneMenuAttached = false
        }
    }

    private fun updateToneChipsHighlight(
        activeTone: String,
        chipRaw: TextView? = null,
        chipNormal: TextView? = null,
        chipPro: TextView? = null
    ) {
        val r = chipRaw ?: toneMenuView?.findViewById(R.id.chipToneRaw) ?: return
        val n = chipNormal ?: toneMenuView?.findViewById(R.id.chipToneNormal) ?: return
        val p = chipPro ?: toneMenuView?.findViewById(R.id.chipToneProfessional) ?: return

        fun styleChip(chip: TextView, isActive: Boolean) {
            if (isActive) {
                chip.setBackgroundResource(R.drawable.bg_tone_chip_active)
                chip.setTextColor(Color.parseColor("#38BDF8"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_tone_chip_inactive)
                chip.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        val normTone = activeTone.lowercase().trim()
        val isRaw = normTone == "raw"
        val isPro = normTone == "professional" || normTone == "formal"
        val isNormal = !isRaw && !isPro // default normal (including "normal", "simple")

        styleChip(r, isRaw)
        styleChip(n, isNormal)
        styleChip(p, isPro)
    }

    private fun onToneSelected(tone: String) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val tonePrefKey = if (activeMode == MODE_VOICE) PREF_VOICE_TONE else PREF_TEXT_TONE
        prefs.edit().putString(tonePrefKey, tone).apply()

        updateToneChipsHighlight(tone)
        btnRewrite?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val modeLabel = if (activeMode == MODE_VOICE) "Voice" else "Text"
        val displayName = when (tone.lowercase().trim()) {
            "raw" -> "Raw"
            "professional", "formal" -> "Professional"
            else -> "Normal"
        }
        Toast.makeText(applicationContext, "Keyflow [$modeLabel]: $displayName active", Toast.LENGTH_SHORT).show()

        mainHandler.postDelayed({
            dismissToneMenu()
        }, 180L)
    }

    private fun dismissToneMenu() {
        if (!isToneMenuAttached && toneMenuView == null) return
        val viewToDismiss = toneMenuView ?: return
        isToneMenuAttached = false

        viewToDismiss.animate()
            .alpha(0f)
            .scaleX(0.75f)
            .scaleY(0.75f)
            .setDuration(120)
            .withEndAction {
                try {
                    windowManager.removeViewImmediate(viewToDismiss)
                    Log.d(TAG, "Tone menu dismissed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove tone menu view", e)
                } finally {
                    if (toneMenuView == viewToDismiss) {
                        toneMenuView = null
                    }
                }
            }
            .start()
    }

    override fun onInterrupt() {
        isServiceRunning = false
        Log.w(TAG, "Keyflow RewriteAccessibilityService interrupted")
        stopVoiceRecording(discard = true)
        hideOverlaySmoothly()
        lastInteractedInputNode?.recycle()
        lastInteractedInputNode = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopVoiceRecording(discard = true)
        hideOverlaySmoothly()
        lastInteractedInputNode?.recycle()
        lastInteractedInputNode = null
        serviceScope.cancel()
        Log.d(TAG, "Keyflow RewriteAccessibilityService destroyed")
    }
}
