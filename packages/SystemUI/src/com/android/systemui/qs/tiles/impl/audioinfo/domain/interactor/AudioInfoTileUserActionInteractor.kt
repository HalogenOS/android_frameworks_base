/*
 * Copyright (C) 2026 The halogenOS Project
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

package com.android.systemui.qs.tiles.impl.audioinfo.domain.interactor

import android.content.Intent
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.shared.QSSettingsPackageRepository
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.audioinfo.domain.model.AudioInfoTileModel
import com.android.systemui.qs.tiles.impl.audioinfo.ui.dialog.AudioInfoDialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * Click opens the live audio-information dialog (animated from the tile); long-click deep-links to
 * the full "Audio information" Settings page. Mirrors the font-scaling tile's dialog + settings
 * handling, including keyguard-aware dialog presentation.
 */
class AudioInfoTileUserActionInteractor
@Inject
constructor(
    @Main private val coroutineContext: CoroutineContext,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
    private val dialogDelegateProvider: Provider<AudioInfoDialogDelegate>,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
    private val settingsPackageRepository: QSSettingsPackageRepository,
) : QSTileUserActionInteractor<AudioInfoTileModel> {

    override suspend fun handleInput(input: QSTileInput<AudioInfoTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    val animateFromExpandable =
                        action.expandable != null && !keyguardStateController.isShowing
                    val runnable = Runnable {
                        val dialog: SystemUIDialog = dialogDelegateProvider.get().createDialog()
                        if (animateFromExpandable) {
                            action.expandable
                                ?.dialogTransitionController(
                                    DialogCuj(
                                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                        INTERACTION_JANK_TAG,
                                    )
                                )
                                ?.let { dialogTransitionAnimator.show(dialog, it) } ?: dialog.show()
                        } else {
                            dialog.show()
                        }
                    }
                    withContext(coroutineContext) {
                        activityStarter.executeRunnableDismissingKeyguard(
                            runnable,
                            /* cancelAction= */ null,
                            /* dismissShade= */ true,
                            /* afterKeyguardGone= */ true,
                            /* deferred= */ false,
                        )
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(action.expandable, settingsPageIntent())
                }
                is QSTileUserAction.ToggleClick -> {}
            }
        }

    /** Builds an explicit intent to the Settings SPA "Audio information" page. */
    private fun settingsPageIntent(): Intent {
        val settingsPackage = settingsPackageRepository.getSettingsPackageName()
        return Intent()
            .setClassName(settingsPackage, SPA_ACTIVITY_CLASS)
            .putExtra(SPA_DESTINATION_EXTRA, AUDIO_INFO_SPA_DESTINATION)
    }

    companion object {
        private const val INTERACTION_JANK_TAG = "audio_info"

        // Explicit SPA deep-link wiring; mirrors SpaActivity.startSpaActivity without taking a
        // build dependency on the Settings SpaLib from SystemUI.
        private const val SPA_ACTIVITY_CLASS = "com.android.settings.spa.SpaActivity"
        private const val SPA_DESTINATION_EXTRA = "spaActivityDestination"
        private const val AUDIO_INFO_SPA_DESTINATION = "AudioInformation"
    }
}
