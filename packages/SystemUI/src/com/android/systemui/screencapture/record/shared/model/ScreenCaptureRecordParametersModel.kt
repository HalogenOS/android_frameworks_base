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

package com.android.systemui.screencapture.record.shared.model

import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screenrecord.ScreenRecordingAudioSource

/** Models a set of parameters necessary to start a screen recording. */
data class ScreenCaptureRecordParametersModel(
    val target: ScreenCaptureTarget,
    val audioSource: ScreenRecordingAudioSource,
    val shouldShowTaps: Boolean,
    val shouldShowFrontCamera: Boolean,
    val lowQuality: Boolean,
)
