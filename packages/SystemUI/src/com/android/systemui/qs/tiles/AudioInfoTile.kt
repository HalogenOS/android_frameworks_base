/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile

import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.Icon
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.impl.audioinfo.ui.dialog.AudioInfoDialogDelegate
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController

import javax.inject.Inject
import javax.inject.Provider

/**
 * Quick Settings audio-information tile (legacy [QSTileImpl] path, which is the one that runs while
 * `qsNewTilesFuture()` is disabled). A tap shows the live audio-path dialog; a long-press opens the
 * full "Audio information" Settings page. The new-architecture pipeline (see the audioinfo impl
 * package) provides the same behavior for when that flag is enabled.
 */
class AudioInfoTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val dialogDelegateProvider: Provider<AudioInfoDialogDelegate>,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger,
) {
    companion object {
        const val TILE_SPEC = "audio_info"

        private const val INTERACTION_JANK_TAG = "audio_info"
        private const val SPA_ACTIVITY_CLASS = "com.android.settings.spa.SpaActivity"
        private const val SPA_DESTINATION_EXTRA = "spaActivityDestination"
        private const val AUDIO_INFO_SPA_DESTINATION = "AudioInformation"
    }

    private var icon: Icon? = null

    override fun isAvailable(): Boolean = true

    override fun newTileState() = BooleanState()

    override fun handleClick(expandable: Expandable?) {
        // Show the live audio-path dialog. Long-press (handled by the base class via
        // getLongClickIntent()) opens the full Settings page instead.
        val animateFromExpandable = expandable != null && !keyguardStateController.isShowing
        val runnable = Runnable {
            val dialog: SystemUIDialog = dialogDelegateProvider.get().createDialog()
            if (animateFromExpandable) {
                val controller =
                    expandable?.dialogTransitionController(
                        DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                    )
                controller?.let { dialogTransitionAnimator.show(dialog, controller) }
                    ?: dialog.show()
            } else {
                dialog.show()
            }
        }
        mainHandler.post {
            mActivityStarter.executeRunnableDismissingKeyguard(
                runnable,
                /* cancelAction= */ null,
                /* dismissShade= */ true,
                /* afterKeyguardGone= */ true,
                /* deferred= */ false,
            )
        }
    }

    override fun getLongClickIntent(): Intent =
        Intent()
            .setClassName("com.android.settings", SPA_ACTIVITY_CLASS)
            .putExtra(SPA_DESTINATION_EXTRA, AUDIO_INFO_SPA_DESTINATION)

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_audio_info_label)

    override fun getMetricsCategory() = MetricsLogger.VIEW_UNKNOWN

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        if (icon == null) {
            icon = maybeLoadResourceIcon(R.drawable.ic_spatial_audio)
        }
        state.icon = icon
        state.label = tileLabel
        state.contentDescription = state.label
        state.state = Tile.STATE_INACTIVE
    }

    override fun handleSetListening(listening: Boolean) {}
}
