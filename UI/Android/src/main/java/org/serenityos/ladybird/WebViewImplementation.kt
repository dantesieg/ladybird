/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.net.URL

// What the UI shows of a page.
data class PageState(val url: String, val title: String, val isLoading: Boolean, val canGoBack: Boolean, val canGoForward: Boolean)

/**
 * Wrapper around WebView::ViewImplementation for use by Kotlin
 */
class WebViewImplementation(private val view: WebView) {
    // Instance Pointer to native object, very unsafe :)
    private var nativeInstance: Long = 0
    fun initialize(isPrivate: Boolean, websitesPreferDark: Boolean) {
        nativeInstance = nativeObjectInit(isPrivate, websitesPreferDark)
    }

    fun goBack() = nativeGoBack(nativeInstance)
    fun goForward() = nativeGoForward(nativeInstance)
    fun reload() = nativeReload(nativeInstance)
    fun stopLoading() = nativeStopLoading(nativeInstance)
    fun setVisible(visible: Boolean) = nativeSetVisible(nativeInstance, visible)
    fun findInPage(query: String) = nativeFindInPage(nativeInstance, query)
    fun findInPageNextMatch() = nativeFindInPageNextMatch(nativeInstance)
    fun findInPagePreviousMatch() = nativeFindInPagePreviousMatch(nativeInstance)
    fun findInPageEnd() = nativeFindInPageEnd(nativeInstance)

    fun dispose() {
        nativeObjectDispose(nativeInstance)
        nativeInstance = 0
    }

    fun loadURL(url: String) {
        nativeLoadURL(nativeInstance, url)
    }

    fun drawIntoBitmap(bitmap: Bitmap) {
        nativeDrawIntoBitmap(nativeInstance, bitmap)
    }

    fun currentHardwareBuffer(): HardwareBuffer? = nativeCurrentHardwareBuffer(nativeInstance)

    fun setViewportGeometry(w: Int, h: Int) {
        nativeSetViewportGeometry(nativeInstance, w, h)
    }

    fun setDevicePixelRatio(ratio: Float) {
        nativeSetDevicePixelRatio(nativeInstance, ratio)
    }

    fun mouseEvent(eventType: Int, x: Float, y: Float, rawX: Float, rawY: Float) {
        nativeMouseEvent(nativeInstance, eventType, x, y, rawX, rawY)
    }

    fun setMaximumFramesPerSecond(framesPerSecond: Float) {
        nativeSetMaximumFramesPerSecond(nativeInstance, framesPerSecond)
    }

    fun scroll(x: Float, y: Float, deltaX: Float, deltaY: Float, phase: Int) {
        nativeScroll(nativeInstance, x, y, deltaX, deltaY, phase)
    }

    fun commitText(text: String) {
        nativeCommitText(nativeInstance, text)
    }

    fun pressKey(key: Int) {
        nativePressKey(nativeInstance, key)
    }

    // Functions called from native code
    fun onInputMethodStateChanged(isEnabled: Boolean) {
        view.post { view.onInputMethodStateChanged(isEnabled) }
    }

    fun invalidateLayout() {
        view.requestLayout()
        view.invalidate()
    }

    fun onStateChanged(url: String, title: String, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
        view.onStateChanged(PageState(url, title, isLoading, canGoBack, canGoForward))
    }

    fun onLoadStart(url: String, isRedirect: Boolean) {
        view.onLoadStart(url, isRedirect)
    }

    // Functions implemented in native code
    private external fun nativeObjectInit(isPrivate: Boolean, websitesPreferDark: Boolean): Long
    private external fun nativeGoBack(instance: Long)
    private external fun nativeGoForward(instance: Long)
    private external fun nativeReload(instance: Long)
    private external fun nativeStopLoading(instance: Long)
    private external fun nativeSetVisible(instance: Long, visible: Boolean)
    private external fun nativeFindInPage(instance: Long, query: String)
    private external fun nativeFindInPageNextMatch(instance: Long)
    private external fun nativeFindInPagePreviousMatch(instance: Long)
    private external fun nativeFindInPageEnd(instance: Long)
    private external fun nativeObjectDispose(instance: Long)

    private external fun nativeDrawIntoBitmap(instance: Long, bitmap: Bitmap)
    private external fun nativeSetViewportGeometry(instance: Long, w: Int, h: Int)
    private external fun nativeSetDevicePixelRatio(instance: Long, ratio: Float)
    private external fun nativeLoadURL(instance: Long, url: String)
    private external fun nativeMouseEvent(instance: Long, eventType: Int, x: Float, y: Float, rawX: Float, rawY: Float)
    private external fun nativeCurrentHardwareBuffer(instance: Long): HardwareBuffer?
    private external fun nativeSetMaximumFramesPerSecond(instance: Long, framesPerSecond: Float)
    private external fun nativeScroll(instance: Long, x: Float, y: Float, deltaX: Float, deltaY: Float, phase: Int)
    private external fun nativeCommitText(instance: Long, text: String)
    private external fun nativePressKey(instance: Long, key: Int)

    companion object {
        /*
         * We use a static class initializer to allow the native code to cache some
         * field offsets. This native function looks up and caches interesting
         * class/field/method IDs. Throws on failure.
         */
        private external fun nativeClassInit()

        // Must match nativePressKey in LadybirdActivity.cpp.
        const val KEY_BACKSPACE = 0
        const val KEY_ENTER = 1

        // Must match nativeScroll in LadybirdActivity.cpp.
        const val SCROLL_DRAG = 0
        const val SCROLL_MOMENTUM = 1
        const val SCROLL_END = 2

        init {
            nativeClassInit()
        }
    }
};
