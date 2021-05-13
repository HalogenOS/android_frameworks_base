/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screencapture.record.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordParametersInteractor
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.map

class ScreenCaptureRecordParametersViewModel
@AssistedInject
constructor(private val interactor: ScreenCaptureRecordParametersInteractor) :
    HydratedActivatable() {

    val audioSource: ScreenRecordingAudioSource? by
        interactor.parameters
            .map { it.audioSource }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#audioSource", null)

    val target: ScreenCaptureTarget? by
        interactor.parameters
            .map { it.target }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#target", null)

    val shouldShowTaps: Boolean? by
        interactor.parameters
            .map { it.shouldShowTaps }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#shouldShowTaps", null)

    val shouldShowFrontCamera: Boolean? by
        interactor.parameters
            .map { it.shouldShowFrontCamera }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#shouldShowFrontCamera", null)

    val lowQuality: Boolean? by
        interactor.parameters
            .map { it.lowQuality }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#lowQuality", null)

    val longerDuration: Boolean? by
        interactor.parameters
            .map { it.longerDuration }
            .hydratedStateOf("ScreenCaptureAudioSourceViewModel#longerDuration", null)

    var shouldRecordDevice: Boolean
        get() =
            audioSource == ScreenRecordingAudioSource.MIC_AND_INTERNAL ||
                audioSource == ScreenRecordingAudioSource.INTERNAL
        set(value) {
            if (value) {
                if (shouldRecordMicrophone) {
                    setAudioSource(ScreenRecordingAudioSource.MIC_AND_INTERNAL)
                } else {
                    setAudioSource(ScreenRecordingAudioSource.INTERNAL)
                }
            } else {
                if (shouldRecordMicrophone) {
                    setAudioSource(ScreenRecordingAudioSource.MIC)
                } else {
                    setAudioSource(ScreenRecordingAudioSource.NONE)
                }
            }
        }

    var shouldRecordMicrophone: Boolean
        get() =
            audioSource == ScreenRecordingAudioSource.MIC_AND_INTERNAL ||
                audioSource == ScreenRecordingAudioSource.MIC
        set(value) {
            if (value) {
                if (shouldRecordDevice) {
                    setAudioSource(ScreenRecordingAudioSource.MIC_AND_INTERNAL)
                } else {
                    setAudioSource(ScreenRecordingAudioSource.MIC)
                }
            } else {
                if (shouldRecordDevice) {
                    setAudioSource(ScreenRecordingAudioSource.INTERNAL)
                } else {
                    setAudioSource(ScreenRecordingAudioSource.NONE)
                }
            }
        }

    fun setAudioSource(audioSource: ScreenRecordingAudioSource) {
        interactor.setAudioSource(audioSource)
    }

    fun setRecordTarget(target: ScreenCaptureTarget) {
        interactor.setRecordTarget(target)
    }

    fun setShouldShowTaps(shouldShowTaps: Boolean) {
        interactor.setShouldShowTaps(shouldShowTaps)
    }

    fun setShouldShowFrontCamera(shouldShowFrontCamera: Boolean) {
        interactor.setShouldShowFrontCamera(shouldShowFrontCamera)
    }

    fun setLowQuality(lowQuality: Boolean) {
        interactor.setLowQuality(lowQuality)
    }

    fun setLongerDuration(longerDuration: Boolean) {
        interactor.setLongerDuration(longerDuration)
    }

    @AssistedFactory
    interface Factory {
        fun create(): ScreenCaptureRecordParametersViewModel
    }
}
