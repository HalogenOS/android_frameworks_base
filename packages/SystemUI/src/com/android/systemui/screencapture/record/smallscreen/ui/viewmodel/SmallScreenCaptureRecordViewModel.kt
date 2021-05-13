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

package com.android.systemui.screencapture.record.smallscreen.ui.viewmodel

import android.app.ActivityOptions
import android.app.ActivityOptions.LaunchCookie
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
import android.app.IActivityTaskManager
import android.media.projection.StopReason
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.screencapture.common.ScreenCaptureUiScope
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModelImpl
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.screenrecord.domain.ScreenRecordingParameters
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import com.android.systemui.screenrecord.domain.interactor.Status
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map

class SmallScreenCaptureRecordViewModel
@AssistedInject
constructor(
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
    recordDetailsAppSelectorViewModelFactory: RecordDetailsAppSelectorViewModel.Factory,
    screenCaptureRecordParametersViewModelFactory: ScreenCaptureRecordParametersViewModel.Factory,
    recordDetailsTargetViewModelFactory: RecordDetailsTargetViewModel.Factory,
    private val drawableLoaderViewModelImpl: DrawableLoaderViewModelImpl,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val activityTaskManager: IActivityTaskManager,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModelImpl {

    val recordDetailsAppSelectorViewModel: RecordDetailsAppSelectorViewModel =
        recordDetailsAppSelectorViewModelFactory.create()
    val recordDetailsParametersViewModel: ScreenCaptureRecordParametersViewModel =
        screenCaptureRecordParametersViewModelFactory.create()
    val recordDetailsTargetViewModel: RecordDetailsTargetViewModel =
        recordDetailsTargetViewModelFactory.create()

    val isRecording: Boolean by
        screenRecordingServiceInteractor.status
            .map { it.isRecording }
            .hydratedStateOf(
                traceName = "SmallScreenCaptureRecordViewModel#isRecording",
                initialValue = screenRecordingServiceInteractor.status.value.isRecording,
            )

    var detailsPopup: RecordDetailsPopupType by mutableStateOf(RecordDetailsPopupType.Settings)
        private set

    var shouldShowDetails: Boolean by
        mutableStateOf(!screenRecordingServiceInteractor.status.value.isRecording)
        private set

    val shouldShowSettingsButton: Boolean by
        screenRecordingServiceInteractor.status
            .map { status ->
                if (status.isRecording) {
                    true
                } else {
                    shouldShowDetails = true
                    false
                }
            }
            .hydratedStateOf(
                traceName = "SmallScreenCaptureRecordViewModel#shouldShowSettingsButton",
                initialValue = !screenRecordingServiceInteractor.status.value.isRecording,
            )

    override suspend fun onActivated() {
        coroutineScope {
            launchTraced("SmallScreenCaptureRecordViewModel#recordDetailsAppSelectorViewModel") {
                recordDetailsAppSelectorViewModel.activate()
            }
            launchTraced(
                "ScreenCaptureRecordSmallScreenViewModel#recordDetailsParametersViewModel"
            ) {
                recordDetailsParametersViewModel.activate()
            }
            launchTraced("ScreenCaptureRecordSmallScreenViewModel#recordDetailsTargetViewModel") {
                recordDetailsTargetViewModel.activate()
            }
        }
    }

    fun showSettings() {
        detailsPopup = RecordDetailsPopupType.Settings
    }

    fun showAppSelector() {
        detailsPopup = RecordDetailsPopupType.AppSelector
    }

    fun showMarkupColorSelector() {
        detailsPopup = RecordDetailsPopupType.MarkupColorSelector
    }

    fun dismiss() {
        screenCaptureUiInteractor.hide(ScreenCaptureType.RECORD)
    }

    fun onPrimaryButtonTapped() {
        if (screenRecordingServiceInteractor.status.value.isRecording) {
            screenRecordingServiceInteractor.stopRecording(StopReason.STOP_HOST_APP)
        } else {
            startRecording()
        }
        dismiss()
    }

    private fun startRecording() {
        val audioSource = recordDetailsParametersViewModel.audioSource ?: return
        val target = recordDetailsTargetViewModel.currentTarget?.screenCaptureTarget ?: return
        val lowQuality = recordDetailsParametersViewModel.lowQuality ?: return
        val longerDuration = recordDetailsParametersViewModel.longerDuration ?: return
        when (target) {
            is ScreenCaptureTarget.Fullscreen -> {
                val shouldShowTaps = recordDetailsParametersViewModel.shouldShowTaps ?: return
                screenRecordingServiceInteractor.startRecording(
                    ScreenRecordingParameters(
                        captureTarget = null,
                        displayId = target.displayId,
                        shouldShowTaps = shouldShowTaps,
                        audioSource = audioSource,
                        lowQuality = lowQuality,
                        longerDuration = longerDuration,
                    )
                )
            }
            is ScreenCaptureTarget.App -> {
                val cookie = LaunchCookie("screen_record")
                activityTaskManager.startActivityFromRecents(
                    target.taskId,
                    ActivityOptions.makeBasic()
                        .apply {
                            pendingIntentBackgroundActivityStartMode =
                                MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            setLaunchCookie(cookie)
                        }
                        .toBundle(),
                )
                screenRecordingServiceInteractor.startRecording(
                    ScreenRecordingParameters(
                        captureTarget =
                            MediaProjectionCaptureTarget(
                                launchCookie = cookie,
                                taskId = target.taskId,
                            ),
                        displayId = target.displayId,
                        shouldShowTaps = false,
                        audioSource = audioSource,
                        lowQuality = lowQuality,
                        longerDuration = longerDuration,
                    )
                )
            }
            else -> error("Unsupported target=$target")
        }
    }

    fun shouldShowSettings(visible: Boolean) {
        if (shouldShowSettingsButton) {
            shouldShowDetails = visible
        }
    }

    @AssistedFactory
    @ScreenCaptureUiScope
    interface Factory {
        fun create(): SmallScreenCaptureRecordViewModel
    }
}

private val Status.isRecording
    get() = this is Status.Started
