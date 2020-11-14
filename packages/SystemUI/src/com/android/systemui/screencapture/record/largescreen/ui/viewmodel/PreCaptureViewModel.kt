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

package com.android.systemui.screencapture.record.largescreen.ui.viewmodel

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.res.R
import com.android.systemui.screencapture.ScreenCaptureEvent
import com.android.systemui.screencapture.common.ScreenCapture
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModelImpl
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.LargeScreenCaptureFeaturesInteractor
import com.android.systemui.screencapture.record.largescreen.domain.interactor.ScreenshotInteractor
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureRegion
import com.android.systemui.screencapture.record.largescreen.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import com.android.systemui.screenrecord.domain.ScreenRecordingParameters
import com.android.systemui.screenrecord.domain.interactor.ScreenRecordingServiceInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Models UI for the Screen Capture UI for large screen devices. */
class PreCaptureViewModel
@AssistedInject
constructor(
    @Assisted private val displayId: Int,
    @Application private val applicationContext: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val windowManager: WindowManager,
    private val iconProvider: ScreenCaptureIconProvider,
    private val screenshotInteractor: ScreenshotInteractor,
    private val featuresInteractor: LargeScreenCaptureFeaturesInteractor,
    private val drawableLoaderViewModelImpl: DrawableLoaderViewModelImpl,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val screenRecordingServiceInteractor: ScreenRecordingServiceInteractor,
    private val uiEventLogger: UiEventLogger,
    @ScreenCapture private val screenCaptureUiParams: ScreenCaptureUiParameters,
    screenCaptureRecordParametersViewModelFactory: ScreenCaptureRecordParametersViewModel.Factory,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModelImpl {
    private val isShowingUiFlow = MutableStateFlow(true)
    private val captureTypeSource =
        MutableStateFlow(
            screenCaptureUiParams.largeScreenParameters?.defaultCaptureType
                ?: ScreenCaptureType.SCREENSHOT
        )
    private val captureRegionSource =
        MutableStateFlow(
            screenCaptureUiParams.largeScreenParameters?.defaultCaptureRegion
                ?: ScreenCaptureRegion.FULLSCREEN
        )
    private val regionBoxSource = MutableStateFlow<Rect?>(null)
    private val toolbarBoundsSource = MutableStateFlow(Rect())
    private val toolbarOpacitySource = MutableStateFlow(1f)

    // TODO(b/423697394) Init default value to be user's previously selected option
    val screenCaptureRecordParametersViewModel =
        screenCaptureRecordParametersViewModelFactory.create()

    val icons: ScreenCaptureIcons? by iconProvider.icons.hydratedStateOf()

    val isShowingUi: Boolean by isShowingUiFlow.hydratedStateOf()

    // TODO(b/423697394) Init default value to be user's previously selected option
    val captureType: ScreenCaptureType by captureTypeSource.hydratedStateOf()

    // TODO(b/423697394) Init default value to be user's previously selected option
    val captureRegion: ScreenCaptureRegion by captureRegionSource.hydratedStateOf()

    val regionBox: Rect? by regionBoxSource.hydratedStateOf()
    val toolbarOpacity: Float by toolbarOpacitySource.hydratedStateOf()

    val screenRecordingSupported = featuresInteractor.screenRecordingSupported

    val captureTypeButtonViewModels: List<RadioButtonGroupItemViewModel> by
        combine(captureTypeSource, iconProvider.icons) { selectedType, icons ->
                generateCaptureTypeButtonViewModels(selectedType, icons)
            }
            .hydratedStateOf(
                initialValue = generateCaptureTypeButtonViewModels(captureTypeSource.value, null)
            )

    val captureRegionButtonViewModels: List<RadioButtonGroupItemViewModel> by
        combine(captureRegionSource, captureTypeSource, iconProvider.icons) {
                selectedRegion,
                selectedCaptureType,
                icons ->
                generateCaptureRegionButtonViewModels(selectedRegion, selectedCaptureType, icons)
            }
            .hydratedStateOf(
                initialValue =
                    generateCaptureRegionButtonViewModels(
                        captureRegionSource.value,
                        captureTypeSource.value,
                        null,
                    )
            )

    /** Closes the Screen Capture UI from the pre-capture toolbar. */
    fun closeFromToolbar() {
        uiEventLogger.log(ScreenCaptureEvent.SCREEN_CAPTURE_LARGE_SCREEN_CLOSE_UI_WITHOUT_CAPTURE)
        closeUi()
    }

    fun updateCaptureType(selectedType: ScreenCaptureType) {
        captureTypeSource.value = selectedType
    }

    fun updateCaptureRegion(selectedRegion: ScreenCaptureRegion) {
        captureRegionSource.value = selectedRegion
    }

    fun updateRegionBoxBounds(bounds: Rect) {
        regionBoxSource.value = bounds
    }

    /**
     * Updates the toolbar opacity based on whether the region box selection intersects with the
     * toolbar, and whether the region box is resized or moved.
     *
     * @param isInteracting whether the region box is currently resized or moved.
     * @param regionBoxRect the current bounds of the region box selection. If not provided, will
     *   use the last selected region as the input to calculate the desired opacity.
     */
    fun updateToolbarOpacityForRegionBox(isInteracting: Boolean, regionBoxRect: Rect? = null) {
        if (isInteracting) {
            toolbarOpacitySource.value = 0f
            return
        }

        // When interaction stops, or a region is selected, calculate the final opacity.
        val finalRegion = regionBoxRect ?: regionBoxSource.value
        if (finalRegion == null) {
            toolbarOpacitySource.value = 1f
            return
        }

        val toolbarRect = toolbarBoundsSource.value
        if (!toolbarRect.isEmpty && Rect.intersects(finalRegion, toolbarRect)) {
            toolbarOpacitySource.value = 0.15f
        } else {
            toolbarOpacitySource.value = 1f
        }
    }

    fun updateToolbarBounds(bounds: Rect) {
        toolbarBoundsSource.value = bounds
    }

    /** Initiates capture of the screen depending on the currently chosen capture type. */
    fun beginCapture() {
        when (captureTypeSource.value) {
            ScreenCaptureType.SCREENSHOT -> takeScreenshot()
            ScreenCaptureType.RECORDING -> startRecording()
        }
    }

    private fun takeScreenshot() {
        when (captureRegionSource.value) {
            ScreenCaptureRegion.FULLSCREEN -> beginFullscreenScreenshot()
            ScreenCaptureRegion.PARTIAL -> beginPartialScreenshot()
            ScreenCaptureRegion.APP_WINDOW -> {}
        }
    }

    private fun beginFullscreenScreenshot() {
        // Hide the UI to avoid the parent window closing animation.
        hideUi()
        backgroundScope.launch { screenshotInteractor.requestFullscreenScreenshot(displayId) }
        closeUi()
    }

    private fun beginPartialScreenshot() {
        val regionBoxRect = requireNotNull(regionBoxSource.value)

        // Hide the UI to avoid the parent window closing animation.
        hideUi()
        backgroundScope.launch {
            // Temporary fix to allow enough time for the pre-capture UI to dismiss.
            // TODO(b/435225255) Implement a more reliable way to ensure the UI is hidden prior to
            // taking the screenshot.
            delay(100)
            screenshotInteractor.requestPartialScreenshot(regionBoxRect, displayId)
        }
        closeUi()
    }

    private fun startRecording() {
        when (captureRegionSource.value) {
            ScreenCaptureRegion.FULLSCREEN -> startFullscreenRecording()
            ScreenCaptureRegion.PARTIAL -> {}
            ScreenCaptureRegion.APP_WINDOW -> {}
        }
    }

    private fun startFullscreenRecording() {
        require(captureTypeSource.value == ScreenCaptureType.RECORDING)
        require(captureRegionSource.value == ScreenCaptureRegion.FULLSCREEN)

        // Hide the pre-capture UI before starting the recording.
        // TODO(b/437970158): Show the countdown before starting recording.
        hideUi()
        closeUi()

        backgroundScope.launch {
            screenRecordingServiceInteractor.startRecording(
                // TODO(b/437971334): Get options from the UI.
                ScreenRecordingParameters(
                    captureTarget = null, // Fullscreen.
                    audioSource = ScreenRecordingAudioSource.INTERNAL,
                    displayId = displayId,
                    shouldShowTaps = false,
                    lowQuality = false,
                )
            )
        }
    }

    /**
     * Simply hides all Composables from being visible, which avoids the parent window close
     * animation. This is useful to ensure the UI is not visible before a screenshot is taken. Note:
     * this does NOT close the parent window. See [closeUi] for closing the window.
     */
    fun hideUi() {
        isShowingUiFlow.value = false
    }

    /** Closes the UI by hiding the parent window. */
    private fun closeUi() {
        screenCaptureUiInteractor.hide(
            com.android.systemui.screencapture.common.shared.model.ScreenCaptureType.RECORD
        )
    }

    override suspend fun onActivated() {
        coroutineScope {
            launch { iconProvider.collectIcons() }
            launch { screenCaptureRecordParametersViewModel.activate() }
            launch { initializeRegionBox() }
        }
    }

    private fun initializeRegionBox() {
        if (regionBoxSource.value != null) {
            return
        }
        val bounds = windowManager.currentWindowMetrics.bounds
        regionBoxSource.value =
            Rect(bounds).apply { inset(bounds.width() / 4, bounds.height() / 4) }
    }

    private fun generateCaptureTypeButtonViewModels(
        selectedType: ScreenCaptureType,
        icons: ScreenCaptureIcons?,
    ): List<RadioButtonGroupItemViewModel> {
        return listOf(
            RadioButtonGroupItemViewModel(
                icon = icons?.screenRecord,
                label = applicationContext.getString(R.string.screen_capture_toolbar_record_button),
                isSelected = selectedType == ScreenCaptureType.RECORDING,
                onClick = { updateCaptureType(ScreenCaptureType.RECORDING) },
            ),
            RadioButtonGroupItemViewModel(
                selectedIcon = icons?.screenshotToolbar,
                unselectedIcon = icons?.screenshotToolbarUnselected,
                label =
                    applicationContext.getString(R.string.screen_capture_toolbar_screenshot_button),
                isSelected = selectedType == ScreenCaptureType.SCREENSHOT,
                onClick = { updateCaptureType(ScreenCaptureType.SCREENSHOT) },
            ),
        )
    }

    private fun generateCaptureRegionButtonViewModels(
        selectedRegion: ScreenCaptureRegion,
        selectedCaptureType: ScreenCaptureType,
        icons: ScreenCaptureIcons?,
    ): List<RadioButtonGroupItemViewModel> {
        return buildList {
            if (featuresInteractor.appWindowRegionSupported) {
                add(
                    RadioButtonGroupItemViewModel(
                        icon = icons?.appWindow,
                        isSelected = (selectedRegion == ScreenCaptureRegion.APP_WINDOW),
                        onClick = { updateCaptureRegion(ScreenCaptureRegion.APP_WINDOW) },
                        contentDescription =
                            applicationContext.getString(
                                when (selectedCaptureType) {
                                    ScreenCaptureType.SCREENSHOT ->
                                        R.string
                                            .screen_capture_toolbar_app_window_button_screenshot_a11y
                                    ScreenCaptureType.RECORDING ->
                                        R.string
                                            .screen_capture_toolbar_app_window_button_record_a11y
                                }
                            ),
                    )
                )
            }

            add(
                RadioButtonGroupItemViewModel(
                    icon = icons?.region,
                    isSelected = (selectedRegion == ScreenCaptureRegion.PARTIAL),
                    onClick = { updateCaptureRegion(ScreenCaptureRegion.PARTIAL) },
                    contentDescription =
                        applicationContext.getString(
                            when (selectedCaptureType) {
                                ScreenCaptureType.SCREENSHOT ->
                                    R.string.screen_capture_toolbar_region_button_screenshot_a11y
                                ScreenCaptureType.RECORDING ->
                                    R.string.screen_capture_toolbar_region_button_record_a11y
                            }
                        ),
                )
            )

            add(
                RadioButtonGroupItemViewModel(
                    icon = icons?.fullscreen,
                    isSelected = (selectedRegion == ScreenCaptureRegion.FULLSCREEN),
                    onClick = { updateCaptureRegion(ScreenCaptureRegion.FULLSCREEN) },
                    contentDescription =
                        applicationContext.getString(
                            when (selectedCaptureType) {
                                ScreenCaptureType.SCREENSHOT ->
                                    R.string
                                        .screen_capture_toolbar_fullscreen_button_screenshot_a11y
                                ScreenCaptureType.RECORDING ->
                                    R.string.screen_capture_toolbar_fullscreen_button_record_a11y
                            }
                        ),
                )
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(displayId: Int): PreCaptureViewModel
    }
}
