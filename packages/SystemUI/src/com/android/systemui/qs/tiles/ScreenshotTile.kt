/*
 * Copyright (C) 2020 The OmniROM Project
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.WindowManager.ScreenshotSource

import com.android.internal.logging.MetricsLogger
import com.android.internal.util.ScreenshotHelper
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tileimpl.QSTileImpl

import javax.inject.Inject

class ScreenshotTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val panelInteractor: PanelInteractor,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger,
) {
    companion object {
        const val TILE_SPEC = "screenshot"
        private const val COLLAPSE_DELAY_MS = 1000L
    }

    override fun newTileState() = BooleanState()

    override fun handleClick(expandable: Expandable?) {
        panelInteractor.collapsePanels()
        mHandler.postDelayed({
            ScreenshotHelper(mContext).takeScreenshot(
                ScreenshotSource.SCREENSHOT_OTHER, mHandler, null)
        }, COLLAPSE_DELAY_MS)
    }

    override fun getLongClickIntent(): Intent? = null

    override fun handleLongClick(expandable: Expandable?) {
        handleClick(expandable)
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(com.android.internal.R.string.global_action_screenshot)

    override fun getMetricsCategory(): Int =
        com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        state.label = getTileLabel()
        state.icon = ResourceIcon.get(com.android.internal.R.drawable.ic_screenshot)
        state.state = Tile.STATE_INACTIVE
    }

    override fun handleSetListening(listening: Boolean) {}
}
