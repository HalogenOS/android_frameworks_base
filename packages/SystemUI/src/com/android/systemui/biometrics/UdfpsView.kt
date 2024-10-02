/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.FrameLayout
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.doze.DozeReceiver
import com.android.systemui.res.R

private const val TAG = "UdfpsView"

/**
 * The main view group containing all UDFPS animations.
 */
class UdfpsView(
    context: Context,
    attrs: AttributeSet?
) : FrameLayout(context, attrs), DozeReceiver {
    // sensorRect may be bigger than the sensor. True sensor dimensions are defined in
    // overlayParams.sensorBounds
    var sensorRect = Rect()
    private var mUdfpsDisplayMode: UdfpsDisplayModeProvider? = null

    private var ghbmView: UdfpsSurfaceView? = null

    /** View controller (can be different for enrollment, BiometricPrompt, Keyguard, etc.). */
    var animationViewController: UdfpsAnimationViewController<*>? = null

    /** Parameters that affect the position and size of the overlay. */
    var overlayParams = UdfpsOverlayParams()

    fun setUdfpsDisplayModeProvider(udfpsDisplayModeProvider: UdfpsDisplayModeProvider?) {
        mUdfpsDisplayMode = udfpsDisplayModeProvider
    }

    // Don't propagate any touch events to the child views.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return (animationViewController == null || !animationViewController!!.shouldPauseAuth())
    }

    override fun onFinishInflate() {
        ghbmView = findViewById(R.id.hbm_view)
    }

    override fun dozeTimeTick() {
        animationViewController?.dozeTimeTick()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        // Updates sensor rect in relation to the overlay view
        animationViewController?.onSensorRectUpdated(RectF(sensorRect))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.v(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.v(TAG, "onDetachedFromWindow")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun illuminate() {
        ghbmView?.visibility = VISIBLE
        doIlluminate(null /* surface */)
    }

    private fun doIlluminate(surface: Surface?) {
        ghbmView?.drawIlluminationDot(RectF(sensorRect))
    }

    fun stopIlluminating() {
        animationViewController?.onDisplayUnconfigured()
        ghbmView?.visibility = INVISIBLE
    }
}
