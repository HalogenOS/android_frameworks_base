/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.biometrics.ui.view

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout

import com.android.systemui.biometrics.UdfpsDisplayModeProvider
import com.android.systemui.biometrics.UdfpsSurfaceView
import com.android.systemui.res.R

/**
 * A translucent (not visible to the user) view that receives touches to send to FingerprintManager
 * for fingerprint authentication.
 */
class UdfpsTouchOverlay(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var udfpsDisplayModeProvider: UdfpsDisplayModeProvider? = null

    var isDisplayConfigured: Boolean = false
        private set

    private val TAG = this::class.simpleName
    private var ghbmView: UdfpsSurfaceView? = null
    private var sensorRect = Rect()

    override fun onFinishInflate() {
        ghbmView = findViewById(R.id.hbm_view_refactored)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        sensorRect.let {
            it.left = left
            it.top = top
            it.right = right
            it.bottom = bottom
        }
    }

    fun configureDisplay(onDisplayConfigured: Runnable) {
        isDisplayConfigured = false

        ghbmView?.also { gView ->
            gView.setGhbmIlluminationListener(this::doIlluminate)
            gView.visibility = VISIBLE
            gView.startGhbmIllumination(onDisplayConfigured)
        } ?: doIlluminate(null /* surface */, onDisplayConfigured)
    }

    fun unconfigureDisplay() {
        isDisplayConfigured = false
        ghbmView?.let { view ->
            view.setGhbmIlluminationListener(null)
            view.visibility = INVISIBLE
        }
    }

    private fun doIlluminate(surface: Surface?, onDisplayConfigured: Runnable?) {
        if (ghbmView != null && surface == null) {
            Log.e(TAG, "doIlluminate | surface must be non-null for GHBM")
        }

        udfpsDisplayModeProvider?.enable {
            onDisplayConfigured?.run()
            ghbmView?.drawIlluminationDot(RectF(sensorRect))
        }
    }
}
