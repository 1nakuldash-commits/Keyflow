package com.keyflow.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
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
        private const val PREF_PILL_X = "pref_pill_x"
        private const val PREF_PILL_Y_OFFSET = "pref_pill_y_offset"
        private const val BADGE_HEIGHT_DP = 36
        private const val BADGE_MARGIN_LEFT_DP = 12
        private const val BADGE_GAP_ABOVE_KEYBOARD_DP = 6
        private const val LONG_PRESS_THRESHOLD_MS = 250L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var btnRewrite: View? = null
    private var tvIcon: TextView? = null
    private var progressBar: ProgressBar? = null

    private var isOverlayAttached = false
    private var isHiding = false
    private var isUserDragging = false
    private var lastKeyboardTopY = -1
    private var lastKnownKeyboardTop = -1
    private var settledKeyboardTop = -1
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

    private val badgeHeightPx: Int
        get() = (BADGE_HEIGHT_DP * resources.displayMetrics.density).toInt()

    private val badgeMarginLeftPx: Int
        get() = (BADGE_MARGIN_LEFT_DP * resources.displayMetrics.density).toInt()

    private val badgeGapAboveKeyboardPx: Int
        get() = (BADGE_GAP_ABOVE_KEYBOARD_DP * resources.displayMetrics.density).toInt()

    private val defaultYOffsetPx: Int
        get() = -(badgeHeightPx + badgeGapAboveKeyboardPx)

    private val overlayLayoutParams by lazy {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val initialX = prefs.getInt(PREF_PILL_X, badgeMarginLeftPx)

        WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowAnimations = 0 // Custom view property animations used for buttery smooth Gboard sync
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
            tvIcon = findViewById(R.id.tvIcon)
            progressBar = findViewById(R.id.progressBar)

            setupDragAndClickGesture(this, btnRewrite ?: this)
        }
    }

    /**
     * Implements Long-Press-to-Drag and Instant-Tap-to-Rewrite.
     * Prevents accidental displacements while letting users place the pill anywhere freehand.
     */
    private fun setupDragAndClickGesture(rootView: View, touchTarget: View) {
        var initialParamX = 0
        var initialParamY = 0
        var initialTouchRawX = 0f
        var initialTouchRawY = 0f
        var isDragging = false
        var longPressTriggered = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val longPressRunnable = Runnable {
            longPressTriggered = true
            isDragging = true
            isUserDragging = true
            // Provide haptic feedback so user knows dragging is unlocked
            touchTarget.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // Visual scale feedback
            touchTarget.animate()
                .scaleX(1.18f)
                .scaleY(1.18f)
                .setDuration(120)
                .start()
        }

        touchTarget.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialParamX = overlayLayoutParams.x
                    initialParamY = overlayLayoutParams.y
                    initialTouchRawX = event.rawX
                    initialTouchRawY = event.rawY
                    isDragging = false
                    isUserDragging = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchRawX
                    val dy = event.rawY - initialTouchRawY

                    if (!longPressTriggered) {
                        // If moved significantly before holding 250ms, cancel long press
                        if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                    } else if (isDragging) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val viewWidth = rootView.width.takeIf { it > 0 } ?: badgeHeightPx
                        val viewHeight = rootView.height.takeIf { it > 0 } ?: badgeHeightPx

                        val newX = (initialParamX + dx.toInt()).coerceIn(0, screenWidth - viewWidth)
                        val newY = (initialParamY + dy.toInt()).coerceIn(0, screenHeight - viewHeight)

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
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (isDragging) {
                        // Smoothly restore normal scale
                        touchTarget.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120)
                            .start()
                        isDragging = false
                        isUserDragging = false

                        // Save persistent position to SharedPreferences
                        savePillPosition(overlayLayoutParams.x, overlayLayoutParams.y)
                    } else {
                        isUserDragging = false
                        // Quick tap (< 250ms) -> Trigger Rewrite
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

    private fun savePillPosition(currentX: Int, currentY: Int) {
        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
        val keyboardTop = if (settledKeyboardTop > 0) settledKeyboardTop else lastKnownKeyboardTop
        val yOffset = if (keyboardTop > 0) {
            currentY - keyboardTop
        } else {
            defaultYOffsetPx
        }

        prefs.edit()
            .putInt(PREF_PILL_X, currentX)
            .putInt(PREF_PILL_Y_OFFSET, yOffset)
            .apply()

        Log.d(TAG, "Saved freehand position: X=$currentX, YOffset=$yOffset")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleKeyboardCheck(50L)
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                scheduleKeyboardCheck(60L)
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    lastInteractedInputNode = source
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // User is actively typing or selecting text inside the input field.
                // The keyboard is ALREADY open. Never re-evaluate keyboard visibility or re-animate overlay on keystrokes!
                val source = event.source
                if (source != null && isCandidateInputNode(source)) {
                    lastInteractedInputNode = source
                }
            }
        }
    }

    /**
     * Inspects window hierarchy to detect the soft keyboard (TYPE_INPUT_METHOD)
     * and positions the floating AI pill relative to the keyboard top.
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

        // Filter for the genuine soft keyboard window.
        // The real keyboard docks at the screen bottom (b.bottom >= screenHeight - 60) and spans most of the screen width.
        // This explicitly ignores key preview popups, magnifying bubbles, and floating tooltips from qwertyuiop keys!
        var mainKeyboardWindow: AccessibilityWindowInfo? = null
        var maxKeyboardArea = 0

        for (window in windowList) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val b = Rect()
                window.getBoundsInScreen(b)
                if (b.bottom >= screenHeight - 60 && b.width() >= (screenWidth * 0.6) && b.height() > 150) {
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

            val isKeyboardOpen = bounds.height() > 100 && bounds.top < screenHeight && bounds.top > 0

            if (isKeyboardOpen) {
                cancelPendingHide()

                // If overlay is already attached and positioned, LOCK IT IN PLACE!
                // Prevents key preview popups from moving the pill up or down while typing.
                if (isOverlayAttached && !isHiding && settledKeyboardTop > 0) {
                    return
                }

                settledKeyboardTop = bounds.top
                lastKnownKeyboardTop = bounds.top

                val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
                val savedYOffset = prefs.getInt(PREF_PILL_Y_OFFSET, defaultYOffsetPx)
                val targetY = maxOf(0, bounds.top + savedYOffset)
                val savedX = prefs.getInt(PREF_PILL_X, badgeMarginLeftPx)

                lastKeyboardTopY = targetY
                showOverlayAt(savedX, targetY)
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
                            settledKeyboardTop = -1
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
            // Priority 5A: Focused candidate with non-empty text
            candidates.firstOrNull { it.isFocused && extractTextFromNode(it).isNotEmpty() }?.let {
                Log.d(TAG, "Tier 5A: Found focused candidate with text: '${extractTextFromNode(it)}'")
                return it
            }

            // Priority 5B: Any candidate reporting isFocused == true
            candidates.firstOrNull { it.isFocused }?.let {
                Log.d(TAG, "Tier 5B: Found focused candidate: ${it.className}")
                return it
            }

            // Priority 5C: Proximity to keyboard top (ChatGPT input sits directly above soft keyboard)
            if (lastKnownKeyboardTop > 0) {
                val candidatesAboveKeyboard = candidates.map { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    Pair(node, rect)
                }.filter { (_, rect) ->
                    rect.bottom <= lastKnownKeyboardTop + 150 && rect.top < lastKnownKeyboardTop
                }

                // Prefer one that has user text entered
                val withText = candidatesAboveKeyboard.firstOrNull { extractTextFromNode(it.first).isNotEmpty() }
                if (withText != null) {
                    Log.d(TAG, "Tier 5C: Found candidate above keyboard with text: '${extractTextFromNode(withText.first)}'")
                    return withText.first
                }

                // Or the one closest to the keyboard top
                candidatesAboveKeyboard.maxByOrNull { it.second.bottom }?.let {
                    Log.d(TAG, "Tier 5C: Found candidate closest to keyboard top at Y=${it.second.bottom}")
                    return it.first
                }
            }

            // Priority 5D: Any candidate with text
            candidates.firstOrNull { extractTextFromNode(it).isNotEmpty() }?.let {
                Log.d(TAG, "Tier 5D: Found candidate with non-empty text: '${extractTextFromNode(it)}'")
                return it
            }

            // Fallback: First candidate
            return candidates.first()
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
        val payload = JSONObject().apply {
            put("text", text)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val prefs = getSharedPreferences(PREFS_KEYFLOW, Context.MODE_PRIVATE)
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
        tvIcon?.visibility = if (isLoading) View.GONE else View.VISIBLE
        btnRewrite?.isEnabled = !isLoading
        btnRewrite?.alpha = if (isLoading) 0.6f else 1.0f
    }

    override fun onInterrupt() {
        Log.w(TAG, "Keyflow RewriteAccessibilityService interrupted")
        hideOverlaySmoothly()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlaySmoothly()
        serviceScope.cancel()
        Log.d(TAG, "Keyflow RewriteAccessibilityService destroyed")
    }
}
