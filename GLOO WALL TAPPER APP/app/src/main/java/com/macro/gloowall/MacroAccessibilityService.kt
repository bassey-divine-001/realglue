package com.macro.gloowall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

class MacroAccessibilityService : AccessibilityService() {
    companion object {
        private const val PREFERENCES = "macro_settings"
        private const val KEY_MACRO_ENABLED = "macro_enabled"
        private const val KEY_TARGET_SIZE_DP = "target_size_dp"
        private const val DEFAULT_TARGET_SIZE_DP = 120
        const val MIN_TARGET_SIZE_DP = 80
        const val MAX_TARGET_SIZE_DP = 220
        private const val HOLD_THRESHOLD_MS = 1_500L
        private const val REPEAT_DELAY_MS = 30L
        private const val GESTURE_DURATION_MS = 10L

        @Volatile
        private var connectedService: MacroAccessibilityService? = null

        fun isMacroEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_MACRO_ENABLED, false)

        fun setMacroEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MACRO_ENABLED, enabled)
                .apply()
            if (enabled) {
                connectedService?.showTarget()
            } else {
                connectedService?.removeTarget()
            }
        }

        fun getTargetSizeDp(context: android.content.Context): Int =
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .getInt(KEY_TARGET_SIZE_DP, DEFAULT_TARGET_SIZE_DP)
                .coerceIn(MIN_TARGET_SIZE_DP, MAX_TARGET_SIZE_DP)

        fun setTargetSizeDp(context: android.content.Context, sizeDp: Int) {
            val clampedSize = sizeDp.coerceIn(MIN_TARGET_SIZE_DP, MAX_TARGET_SIZE_DP)
            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_TARGET_SIZE_DP, clampedSize)
                .apply()
            connectedService?.resizeTarget(clampedSize)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var pointerDown = false
    private var dragging = false
    private var holdTriggered = false
    private var gestureInFlight = false
    private var gestureGeneration = 0L
    private var downTimeMillis = 0L
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var tapX = 0f
    private var tapY = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val holdAndRepeat = object : Runnable {
        override fun run() {
            if (!pointerDown || dragging) return
            holdTriggered = true
            dispatchTargetCenterTap(repeatAfterCompletion = true)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        connectedService = this
        if (isMacroEnabled(this)) showTarget()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        cancelTouchWork()
    }

    override fun onDestroy() {
        removeTarget()
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun showTarget() {
        if (targetView != null || !Settings.canDrawOverlays(this)) return

        val size = dp(getTargetSizeDp(this))
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size) / 2
            y = (metrics.heightPixels - size) / 2
        }

        val view = TargetView().apply {
            contentDescription = "Macro tap target. Drag to move; hold to repeat taps."
            setOnTouchListener { _, event -> handleTargetTouch(event) }
        }
        try {
            windowManager.addView(view, params)
            targetParams = params
            targetView = view
        } catch (_: SecurityException) {
            setMacroEnabled(this, false)
        } catch (_: WindowManager.BadTokenException) {
            setMacroEnabled(this, false)
        }
    }

    private fun removeTarget() {
        cancelTouchWork()
        val view = targetView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // The window may already have been removed by the system.
        }
        targetView = null
        targetParams = null
    }

    private fun handleTargetTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouchWork()
                pointerDown = true
                dragging = false
                holdTriggered = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTimeMillis = event.eventTime
                targetView?.let { view ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    tapX = location[0] + view.width / 2f
                    tapY = location[1] + view.height / 2f
                }
                val params = targetParams ?: return true
                downWindowX = params.x
                downWindowY = params.y
                handler.postDelayed(holdAndRepeat, HOLD_THRESHOLD_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pointerDown) return true
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                val touchSlopSquared = touchSlop.toFloat() * touchSlop
                if (!dragging && deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                    dragging = true
                    cancelTouchWork()
                }
                if (dragging) {
                    targetParams?.let { params ->
                        params.x = downWindowX + deltaX.toInt()
                        params.y = downWindowY + deltaY.toInt()
                        targetView?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!pointerDown) return true
                val heldForMs = event.eventTime - downTimeMillis
                pointerDown = false
                cancelTouchWork()
                if (!dragging && !holdTriggered && heldForMs < HOLD_THRESHOLD_MS) {
                    dispatchTargetCenterTap(repeatAfterCompletion = false)
                }
                dragging = false
                holdTriggered = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                dragging = false
                holdTriggered = false
                cancelTouchWork()
                return true
            }

            MotionEvent.ACTION_OUTSIDE -> return false
        }
        return true
    }

    private fun cancelTouchWork() {
        handler.removeCallbacks(holdAndRepeat)
        gestureGeneration++
        gestureInFlight = false
    }

    private fun dispatchTargetCenterTap(repeatAfterCompletion: Boolean) {
        if (!Settings.canDrawOverlays(this) || targetView == null || gestureInFlight) return
        val generation = gestureGeneration
        val tapPath = Path().apply {
            moveTo(tapX, tapY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0L, GESTURE_DURATION_MS))
            .build()
        val callback = object : GestureDescription.GestureResultCallback() {
            private fun finishGesture() {
                if (generation != gestureGeneration) return
                gestureInFlight = false
                if (repeatAfterCompletion && pointerDown && !dragging) {
                    handler.postDelayed(holdAndRepeat, REPEAT_DELAY_MS)
                }
            }

            override fun onCompleted(gestureDescription: GestureDescription) {
                finishGesture()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                finishGesture()
            }
        }

        gestureInFlight = true
        if (!dispatchGesture(gesture, callback, handler)) {
            gestureInFlight = false
            if (repeatAfterCompletion && pointerDown && !dragging) {
                handler.postDelayed(holdAndRepeat, REPEAT_DELAY_MS)
            }
        }
    }

    private fun resizeTarget(sizeDp: Int) {
        val view = targetView ?: return
        val params = targetParams ?: return
        val newSizePx = dp(sizeDp.coerceIn(MIN_TARGET_SIZE_DP, MAX_TARGET_SIZE_DP))
        if (params.width == newSizePx && params.height == newSizePx) return

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val centerX = location[0] + view.width / 2
        val centerY = location[1] + view.height / 2
        params.width = newSizePx
        params.height = newSizePx
        params.x = centerX - newSizePx / 2
        params.y = centerY - newSizePx / 2
        windowManager.updateViewLayout(view, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class TargetView : View(this@MacroAccessibilityService) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(128, 0, 255, 0)
            style = Paint.Style.FILL
        }
        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 255, 205)
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = minOf(width, height) * 0.47f
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawCircle(centerX, centerY, radius, edgePaint)
            canvas.drawCircle(centerX, centerY, dp(7).toFloat(), centerPaint)
            canvas.drawLine(centerX - dp(15), centerY, centerX - dp(9), centerY, centerPaint)
            canvas.drawLine(centerX + dp(9), centerY, centerX + dp(15), centerY, centerPaint)
            canvas.drawLine(centerX, centerY - dp(15), centerX, centerY - dp(9), centerPaint)
            canvas.drawLine(centerX, centerY + dp(9), centerX, centerY + dp(15), centerPaint)
        }
    }
}
