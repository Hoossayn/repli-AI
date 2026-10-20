/*
 * Copyright (C) 2025-2026 The Repli Keyboard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.repli.capture

import android.animation.ValueAnimator
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Shows a non-touchable capture border plus a compact Capture view / Done control while the user
 * manually chooses chat pages. The component name is retained so existing Accessibility opt-ins
 * survive upgrades. It never inspects the node tree, performs gestures, types, or sends.
 */
class ReplyAutoScrollAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var foregroundPackage: String? = null
    private var guidePackage: String? = null
    private var guideView: ManualCaptureGuideView? = null
    private var controlsView: ManualCaptureControlsView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ReplyAutoScrollBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()?.takeIf(String::isNotBlank) ?: return
        if (eventPackage != packageName && eventPackage != SYSTEM_UI_PACKAGE) {
            foregroundPackage = eventPackage
        }
        val expected = guidePackage
        if (expected != null && eventPackage !in setOf(expected, packageName, SYSTEM_UI_PACKAGE)) hideGuide()
    }

    override fun onInterrupt() = hideGuide()

    override fun onDestroy() {
        hideGuide()
        ReplyAutoScrollBridge.detach(this)
        super.onDestroy()
    }

    internal fun showGuide(
        expectedPackage: String,
        requestId: String,
        captured: Int,
        maximum: Int,
        finished: (Boolean) -> Unit,
    ) {
        val action = Runnable {
            if (expectedPackage.isBlank() || requestId.isBlank() || foregroundPackage != expectedPackage) {
                finished(false)
            } else {
                hideGuide()
                val windowManager = getSystemService(WindowManager::class.java)
                val border = ManualCaptureGuideView(this)
                val controls = ManualCaptureControlsView(this).apply {
                    updateCount(captured, maximum)
                    onCapture = {
                        ReplyScreenCaptureService.requestCaptureView(this@ReplyAutoScrollAccessibilityService, requestId)
                    }
                    onDone = {
                        ReplyScreenCaptureService.finishCapture(this@ReplyAutoScrollAccessibilityService, requestId)
                    }
                }
                val borderParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { title = "Repli manual capture border" }
                val controlsParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    title = "Repli manual capture controls"
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(30)
                }
                val added = runCatching {
                    windowManager.addView(border, borderParams)
                    windowManager.addView(controls, controlsParams)
                    guidePackage = expectedPackage
                    guideView = border
                    controlsView = controls
                    border.start()
                }.isSuccess
                if (!added) {
                    border.stop()
                    runCatching { windowManager.removeViewImmediate(controls) }
                    runCatching { windowManager.removeViewImmediate(border) }
                }
                finished(added)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    internal fun updateGuide(captured: Int, maximum: Int) {
        val action = Runnable { controlsView?.updateCount(captured, maximum) }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else mainHandler.post(action)
    }

    internal fun hideGuide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hideGuide)
            return
        }
        val border = guideView
        val controls = controlsView
        guideView = null
        controlsView = null
        guidePackage = null
        border?.stop()
        val windowManager = getSystemService(WindowManager::class.java)
        if (controls != null) runCatching { windowManager.removeViewImmediate(controls) }
        if (border != null) runCatching { windowManager.removeViewImmediate(border) }
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

/** Kept under the existing bridge name to avoid churning compatibility-sensitive internals. */
object ReplyAutoScrollBridge {
    @Volatile private var service: ReplyAutoScrollAccessibilityService? = null

    internal fun attach(value: ReplyAutoScrollAccessibilityService) { service = value }
    internal fun detach(value: ReplyAutoScrollAccessibilityService) {
        if (service === value) service = null
    }

    fun showGuide(expectedPackage: String, requestId: String, captured: Int, maximum: Int): Boolean {
        val active = service ?: return false
        var accepted = false
        active.showGuide(expectedPackage, requestId, captured, maximum) { accepted = it }
        // Calls from the capture service arrive on the main thread, so showGuide executes in order.
        return accepted
    }

    fun updateGuide(captured: Int, maximum: Int) {
        service?.updateGuide(captured, maximum)
    }

    fun hideGuide() {
        service?.hideGuide()
    }
}

private class ManualCaptureControlsView(context: Context) : LinearLayout(context) {
    private val status = controlText().apply {
        setTextColor(Color.rgb(226, 220, 242))
        setPadding(context.dp(12), 0, context.dp(8), 0)
    }
    var onCapture: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = context.dp(8).toFloat()
        setPadding(context.dp(5), context.dp(5), context.dp(5), context.dp(5))
        background = rounded(Color.rgb(37, 30, 56), 28)
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(42)))
        addView(controlText().apply {
            text = "Capture view"
            contentDescription = "Capture this chat view"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(context.dp(14), 0, context.dp(14), 0)
            background = rounded(Color.rgb(109, 78, 214), 22)
            setOnClickListener { onCapture?.invoke() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(42)))
        addView(controlText().apply {
            text = "Done"
            contentDescription = "Finish capturing chat views"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(context.dp(13), 0, context.dp(11), 0)
            setOnClickListener { onDone?.invoke() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(42)))
    }

    fun updateCount(captured: Int, maximum: Int) {
        status.text = "$captured/$maximum · old → new"
        status.contentDescription = "$captured of $maximum chat views captured. Continue from older to newer messages."
    }

    private fun controlText() = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD,
        )
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
    }
}

private class ManualCaptureGuideView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 92, 246)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
    }
    private val animator = ValueAnimator.ofFloat(0.4f, 1f).apply {
        duration = 850L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            borderPaint.alpha = (255 * (it.animatedValue as Float)).roundToInt()
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun start() = animator.start()
    fun stop() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 5f * density
        canvas.drawRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            18f * density,
            18f * density,
            borderPaint,
        )
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
