/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.hypot

// FIXME: This should (eventually) implement NestedScrollingChild3 and ScrollingView
class WebView(context: Context, attributeSet: AttributeSet?) : View(context, attributeSet) {
    constructor(context: Context) : this(context, null)

    private val viewImpl = WebViewImplementation(this)
    private lateinit var contentBitmap: Bitmap
    // The native side hands out one HardwareBuffer per backing store, and drops it once the store is gone.
    private val hardwareBitmaps = java.util.WeakHashMap<HardwareBuffer, Bitmap>()
    var onLoadStart: (url: String, isRedirect: Boolean) -> Unit = { _, _ -> }
    var onStateChanged: (PageState) -> Unit = {}
    var state = PageState("", "", false, false, false)
        private set
    private var inputMethodIsEnabled = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun initialize(isPrivate: Boolean, websitesPreferDark: Boolean) {
        viewImpl.initialize(isPrivate, websitesPreferDark)
    }

    fun goBack() = viewImpl.goBack()
    fun goForward() = viewImpl.goForward()
    fun reload() = viewImpl.reload()
    fun stopLoading() = viewImpl.stopLoading()
    fun findInPage(query: String) = viewImpl.findInPage(query)
    fun findInPageNextMatch() = viewImpl.findInPageNextMatch()
    fun findInPagePreviousMatch() = viewImpl.findInPagePreviousMatch()
    fun findInPageEnd() = viewImpl.findInPageEnd()

    // Only the tab on screen is visible to its page.
    fun setPageVisible(visible: Boolean) = viewImpl.setVisible(visible)

    // The last frame, drawn from the GPU, for showing the tab elsewhere, such as in the tab overview.
    fun snapshot(): Bitmap? = viewImpl.currentHardwareBuffer()?.let { buffer ->
        hardwareBitmaps.getOrPut(buffer) { Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) }
    }

    fun onStateChanged(newState: PageState) {
        state = newState
        onStateChanged.invoke(newState)
    }

    fun dispose() {
        viewImpl.dispose()
    }

    fun loadURL(url: String) {
        viewImpl.loadURL(url)
    }

    fun onInputMethodStateChanged(isEnabled: Boolean) {
        inputMethodIsEnabled = isEnabled
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(this)
        if (isEnabled) {
            requestFocus()
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = inputMethodIsEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputMethodIsEnabled)
            return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return WebInputConnection()
    }

    // Composition (underlined, uncommitted text) is not shown in the page; text reaches it once committed.
    private inner class WebInputConnection : BaseInputConnection(this@WebView, false) {
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (text.isNotEmpty())
                viewImpl.commitText(text.toString())
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { viewImpl.pressKey(WebViewImplementation.KEY_BACKSPACE) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN)
                handleKey(event)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            viewImpl.pressKey(WebViewImplementation.KEY_ENTER)
            return true
        }
    }

    private fun handleKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> viewImpl.pressKey(WebViewImplementation.KEY_BACKSPACE)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> viewImpl.pressKey(WebViewImplementation.KEY_ENTER)
            else -> {
                val c = event.unicodeChar
                if (c == 0)
                    return false
                viewImpl.commitText(String(Character.toChars(c)))
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKey(event) || super.onKeyDown(keyCode, event)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var isFlinging = false
    private var flingX = 0
    private var flingY = 0

    // The page scrolls by CSS pixels, so convert the finger's device pixels.
    private fun scrollBy(fingerDeltaX: Float, fingerDeltaY: Float, phase: Int) {
        val density = context.resources.displayMetrics.density
        viewImpl.scroll(lastX, lastY, -fingerDeltaX / density, -fingerDeltaY / density, phase)
    }

    private fun stopFling() {
        if (!isFlinging)
            return
        isFlinging = false
        scroller.forceFinished(true)
        scrollBy(0f, 0f, WebViewImplementation.SCROLL_END)
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!isFlinging)
                return
            if (!scroller.computeScrollOffset()) {
                stopFling()
                return
            }
            val dx = (scroller.currX - flingX).toFloat()
            val dy = (scroller.currY - flingY).toFloat()
            flingX = scroller.currX
            flingY = scroller.currY
            if (dx != 0f || dy != 0f)
                scrollBy(dx, dy, WebViewImplementation.SCROLL_MOMENTUM)
            postOnAnimation(this)
        }
    }

    // FIXME: Send touches to the page as touch events. For now, a tap is a click and a drag scrolls.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!isDragging && hypot(event.x - downX, event.y - downY) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    scrollBy(event.x - lastX, event.y - lastY, WebViewImplementation.SCROLL_DRAG)
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                velocityTracker = null
                if (!isDragging) {
                    requestFocus()
                    viewImpl.mouseEvent(MotionEvent.ACTION_DOWN, event.x, event.y, event.rawX, event.rawY)
                    viewImpl.mouseEvent(MotionEvent.ACTION_UP, event.x, event.y, event.rawX, event.rawY)
                    performClick()
                } else {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    if (hypot(vx, vy) >= minFlingVelocity) {
                        flingX = 0
                        flingY = 0
                        scroller.fling(0, 0, vx.toInt(), vy.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
                        isFlinging = true
                        postOnAnimation(flingStep)
                    } else {
                        scrollBy(0f, 0f, WebViewImplementation.SCROLL_END)
                    }
                }
                tracker?.recycle()
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                if (isDragging)
                    scrollBy(0f, 0f, WebViewImplementation.SCROLL_END)
                isDragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0)
            return
        contentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val pixelDensity = context.resources.displayMetrics.density
        viewImpl.setDevicePixelRatio(pixelDensity)
        // LadybirdActivity asks for the display's fastest mode, so let the page render that fast.
        display?.let { display -> viewImpl.setMaximumFramesPerSecond(display.supportedModes.maxOf { it.refreshRate }) }

        // FIXME: Account for scroll offset when view supports scrolling
        viewImpl.setViewportGeometry(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        viewImpl.currentHardwareBuffer()?.let { buffer ->
            val bitmap = hardwareBitmaps.getOrPut(buffer) {
                Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
            }
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                return
            }
        }

        if (!::contentBitmap.isInitialized)
            return
        viewImpl.drawIntoBitmap(contentBitmap);
        canvas.drawBitmap(contentBitmap, 0f, 0f, null)
    }

}
