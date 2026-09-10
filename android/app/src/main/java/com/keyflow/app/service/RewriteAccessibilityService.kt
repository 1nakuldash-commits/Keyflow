package com.keyflow.app.service

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
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
import com.keyflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

class RewriteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RewriteService"
        private const val BACKEND_URL = "http://10.0.2.2:8000/rewrite"
        private const val PREFS_KEYFLOW = "keyflow_prefs"
        private const val PREF_PILL_SNAP_SIDE = "pref_pill_snap_side" // "LEFT" or "RIGHT"
        private const val PREF_PILL_X = "pref_pill_x"
        private const val PREF_PILL_Y_OFFSET = "pref_pill_y_offset"
        private const val PREF_SELECTED_TONE = "pref_selected_tone"
        private const val DEFAULT_TONE = "simple"
        private const val BADGE_SIZE_DP = 44
        private const val ROOT_PADDING_DP = 8
        private const val BADGE_MARGIN_EDGE_DP = 6 // Clean 6dp spacing hugging screen edge
        private const val BADGE_GAP_ABOVE_KEYBOARD_DP = 8
        private const val LONG_PRESS_THRESHOLD_MS = 260L
        private const val KEY_PREVIEW_IGNORE_THRESHOLD_PX = 120
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var btnRewrite: View? = null
    private var ivIcon: ImageView? = null
    private var progressBar: ProgressBar? = null
    private var snapAnimator: ValueAnimator? = null

    // Vertical Tone Menu State
    private var toneMenuView: View? = null
    private var isToneMenuAttached = false

    private var isOverlayAttached = false
    private var isHiding = false
    private var isUserDragging = false
    private var lastKeyboardTopY = -1
    private var lastKnownKeyboardTop = -1
    private var activeDockedKeyboardTop = -1
    private var lastInteractedInputNode: AccessibilityNodeInfo? = null

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

    private val rootPaddingPx: Int
        get() = (ROOT_PADDING_DP * resources.displayMetrics.density).toInt()

    private val badgeMarginEdgePx: Int
        get() = (BADGE_MARGIN_EDGE_DP * resources.displayMetrics.density).toInt()

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
        Log.d(TAG, "Keyflow RewriteAccessibilityService connected")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initOverlayView()
    }

    private fun initOverlayView() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_toolbar, null).apply {
            btnRewrite = findViewById(R.id.btnRewrite)
            ivIcon = findViewById(R.id.ivIcon)
            progressBar = findViewById(R.id.progressBar)

            btnRewrite?.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            btnRewrite?.clipToOutline = true

            setupDragAndClickGesture(this, btnRewrite ?: this)
        }
    }

    /**
     * Implements Long-Press-to-Drag with zero jump, tactile haptic feedback,
     * smooth coordinate tracking, and Magnetic Edge Snapping on release.
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

            // Open the vertical liquid-glass Tone Menu
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
                            mainHandler.removeCallbacks(longPressRunnable)
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

                        val minX = -rootPaddingPx
                        val maxX = maxOf(0, screenWidth - totalViewWidth + rootPaddingPx)
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
                        isDragging = false
                        isUserDragging = false

                        // Execute Magnetic Edge Snapping with reduced edge margin (6dp)
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
                        // Hold was triggered and vertical menu is open. Do not trigger rewrite.
                        isUserDragging = false
                    } else {
                        // Instant Single Tap (0ms latency, double-tap delay eliminated!)
                        isUserDragging = false
                        handleRewriteClicked()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
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
        if (lastInteractedInputNode != newNode) {
            lastInteractedInputNode?.recycle()
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

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // User is actively typing or selecting text inside the input field.
                // Do NOT schedule keyboard check on keystrokes to prevent any overlay jumps.
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    updateLastInteractedNode(source)
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

        val text = node.text?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()

        // Filter out hint/placeholder (e.g. "Message ChatGPT")
        if (text.isNotEmpty() && (hint.isEmpty() || text != hint)) {
            return text
        }

        val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
        if (contentDesc.isNotEmpty() && (hint.isEmpty() || contentDesc != hint)) {
            return contentDesc
        }

        // Recursively inspect children for text (e.g. React Native inner text view)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractTextFromNode(child)
            if (childText.isNotEmpty()) {
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

        if (candidates.isEmpty() && currentWindows != null) {
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
     * Handles the AI tap: extracts text, queries backend, and injects rewritten text.
     */
    private fun handleRewriteClicked() {
        val inputNode = findActiveInputNode()
        if (inputNode == null) {
            Toast.makeText(this, "No active text field found", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "handleRewriteClicked: findActiveInputNode returned null")
            return
        }

        val originalText = extractTextFromNode(inputNode)
        if (originalText.isEmpty()) {
            Toast.makeText(this, "Text field is empty", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "handleRewriteClicked: extractTextFromNode returned empty text")
            return
        }

        Log.d(TAG, "Starting rewrite for text: '$originalText'")
        setLoading(true)

        serviceScope.launch {
            try {
                val rewrittenText = requestRewriteFromBackend(originalText)
                if (!rewrittenText.isNullOrBlank()) {
                    val targetNode = if (inputNode.refresh()) inputNode else (findActiveInputNode() ?: inputNode)
                    injectText(targetNode, rewrittenText)
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

    /**
     * Executes asynchronous OkHttp POST call to the FastAPI backend.
     */
    private suspend fun requestRewriteFromBackend(text: String): String? = withContext(Dispatchers.IO) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val selectedTone = prefs.getString(PREF_SELECTED_TONE, DEFAULT_TONE) ?: DEFAULT_TONE

        val payload = JSONObject().apply {
            put("text", text)
            put("tone", selectedTone)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val rawUrl = prefs.getString("pref_backend_url", BACKEND_URL) ?: BACKEND_URL
        val targetUrl = if (rawUrl.endsWith("/rewrite")) rawUrl else "${rawUrl.trimEnd('/')}/rewrite"

        val request = Request.Builder()
            .url(targetUrl)
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code: ${response.code} - ${response.message}")
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val jsonObject = JSONObject(responseBody)
            jsonObject.optString("rewritten_text", "")
        }
    }

    /**
     * Injects replacement text into the target node.
     * Strategy:
     * 1. Primary: ACTION_SET_TEXT (works in native views: WhatsApp, Telegram, Gemini, Claude).
     * 2. Fallback: Clipboard + ACTION_SET_SELECTION + ACTION_PASTE (for React Native / ChatGPT / Compose).
     * 3. Safety Net: Text copied to clipboard with friendly prompt if all injection actions fail.
     */
    private fun injectText(targetNode: AccessibilityNodeInfo, newText: String) {
        Log.d(TAG, "Attempting text injection into node: class=${targetNode.className}")

        // 1. Primary Method: ACTION_SET_TEXT
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        val setTextSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (setTextSuccess) {
            Log.d(TAG, "Successfully injected text via ACTION_SET_TEXT")
            return
        }

        Log.w(TAG, "ACTION_SET_TEXT returned false, applying Clipboard + PASTE fallback for React Native/ChatGPT")

        // 2. Fallback Method: Clipboard + PASTE
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Keyflow Rewrite", newText)
            clipboard.setPrimaryClip(clip)

            // Ensure the node is focused
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            // Select all existing text if supported
            val selectAllArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, targetNode.text?.length ?: 100000)
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

            // Perform PASTE on the target node
            val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteSuccess) {
                Log.d(TAG, "Successfully injected text via ACTION_PASTE")
                return
            }

            // Check if any child of targetNode accepts ACTION_PASTE
            for (i in 0 until targetNode.childCount) {
                val child = targetNode.getChild(i) ?: continue
                if (child.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
                    if (child.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                        Log.d(TAG, "Successfully injected text via child ACTION_PASTE")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during paste fallback", e)
        }

        // 3. Safety Net: Text copied to clipboard
        Toast.makeText(this, "Copied to clipboard! Long-press to paste.", Toast.LENGTH_LONG).show()
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        ivIcon?.visibility = if (isLoading) View.GONE else View.VISIBLE
        btnRewrite?.isEnabled = !isLoading
        btnRewrite?.alpha = if (isLoading) 0.6f else 1.0f
    }

    /**
     * Displays the minimal ChatGPT-inspired vertical tone selector menu adjacent to the circular pill.
     */
    private fun showToneMenu() {
        if (!isOverlayAttached || overlayView == null) return
        dismissToneMenu()

        val inflater = LayoutInflater.from(this)
        val menuView = inflater.inflate(R.layout.overlay_tone_menu, null) ?: return
        toneMenuView = menuView

        val chipSimple = menuView.findViewById<TextView>(R.id.chipToneSimple)
        val chipFormal = menuView.findViewById<TextView>(R.id.chipToneFormal)
        val chipPro = menuView.findViewById<TextView>(R.id.chipToneProfessional)
        val chipEmail = menuView.findViewById<TextView>(R.id.chipToneEmail)

        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val currentTone = prefs.getString(PREF_SELECTED_TONE, DEFAULT_TONE) ?: DEFAULT_TONE

        updateToneChipsHighlight(currentTone, chipSimple, chipFormal, chipPro, chipEmail)

        chipSimple?.setOnClickListener { onToneSelected("simple") }
        chipFormal?.setOnClickListener { onToneSelected("formal") }
        chipPro?.setOnClickListener { onToneSelected("professional") }
        chipEmail?.setOnClickListener { onToneSelected("email") }

        // Measure menu bounds to place it precisely adjacent to the circular pill
        val unspecifiedSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        menuView.measure(unspecifiedSpec, unspecifiedSpec)
        val menuWidth = menuView.measuredWidth
        val menuHeight = menuView.measuredHeight

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val edgeMargin = (8 * density).toInt()

        val pillScreenLeft = overlayLayoutParams.x + rootPaddingPx
        val pillScreenRight = pillScreenLeft + badgeSizePx
        val pillCenterY = overlayLayoutParams.y + rootPaddingPx + (badgeSizePx / 2)
        val isPillOnLeft = (pillScreenLeft + (badgeSizePx / 2)) < screenWidth / 2

        val targetX: Int = if (isPillOnLeft) {
            val candidateX = pillScreenRight + (6 * density).toInt()
            val maxX = screenWidth - menuWidth - edgeMargin
            candidateX.coerceAtMost(maxX)
        } else {
            val candidateX = pillScreenLeft - menuWidth - (6 * density).toInt()
            candidateX.coerceAtLeast(edgeMargin)
        }

        // Clamp vertically so the menu stays completely above the keyboard without dipping below
        val maxAllowedBottom = if (activeDockedKeyboardTop > 0) activeDockedKeyboardTop - (6 * density).toInt() else screenHeight - (6 * density).toInt()
        var targetY = pillCenterY - (menuHeight / 2)
        if (targetY + menuHeight > maxAllowedBottom) {
            targetY = maxAllowedBottom - menuHeight
        }
        if (targetY < (24 * density).toInt()) {
            targetY = (24 * density).toInt()
        }

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowAnimations = 0
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
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

        // Apple liquid glass bloom physics
        menuView.alpha = 0f
        menuView.scaleX = 0.70f
        menuView.scaleY = 0.70f
        if (isPillOnLeft) {
            menuView.pivotX = 0f
            menuView.pivotY = (menuHeight / 2f).coerceIn(0f, menuHeight.toFloat())
        } else {
            menuView.pivotX = menuWidth.toFloat()
            menuView.pivotY = (menuHeight / 2f).coerceIn(0f, menuHeight.toFloat())
        }

        try {
            windowManager.addView(menuView, lp)
            isToneMenuAttached = true
            menuView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
            Log.d(TAG, "Vertical liquid-glass tone menu attached at X=$targetX, Y=$targetY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach tone menu to WindowManager", e)
            toneMenuView = null
            isToneMenuAttached = false
        }
    }

    private fun updateToneChipsHighlight(
        activeTone: String,
        chipSimple: TextView? = null,
        chipFormal: TextView? = null,
        chipPro: TextView? = null,
        chipEmail: TextView? = null
    ) {
        val s = chipSimple ?: toneMenuView?.findViewById(R.id.chipToneSimple) ?: return
        val f = chipFormal ?: toneMenuView?.findViewById(R.id.chipToneFormal) ?: return
        val p = chipPro ?: toneMenuView?.findViewById(R.id.chipToneProfessional) ?: return
        val e = chipEmail ?: toneMenuView?.findViewById(R.id.chipToneEmail) ?: return

        fun styleChip(chip: TextView, isActive: Boolean) {
            if (isActive) {
                chip.setBackgroundResource(R.drawable.bg_tone_chip_active)
                chip.setTextColor(Color.parseColor("#38BDF8"))
            } else {
                chip.setBackgroundResource(R.drawable.bg_tone_chip_inactive)
                chip.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        styleChip(s, activeTone == "simple")
        styleChip(f, activeTone == "formal")
        styleChip(p, activeTone == "professional")
        styleChip(e, activeTone == "email")
    }

    private fun onToneSelected(tone: String) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SELECTED_TONE, tone).apply()

        updateToneChipsHighlight(tone)
        btnRewrite?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val displayName = when (tone) {
            "formal" -> "Formal Mode"
            "professional" -> "Professional Mode"
            "email" -> "Email Mode"
            else -> "Simple Mode"
        }
        Toast.makeText(applicationContext, "Keyflow: $displayName active", Toast.LENGTH_SHORT).show()

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
        Log.w(TAG, "Keyflow RewriteAccessibilityService interrupted")
        hideOverlaySmoothly()
        lastInteractedInputNode?.recycle()
        lastInteractedInputNode = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlaySmoothly()
        lastInteractedInputNode?.recycle()
        lastInteractedInputNode = null
        serviceScope.cancel()
        Log.d(TAG, "Keyflow RewriteAccessibilityService destroyed")
    }
}
