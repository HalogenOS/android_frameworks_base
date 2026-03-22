/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.provider.Settings
import android.service.quicksettings.Tile

import custom.system.service.charging.IChargingControlManager

import com.android.internal.logging.MetricsLogger
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
import com.android.systemui.res.R

import javax.inject.Inject
import kotlin.math.roundToInt

class ChargingLimitTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger,
) {
    companion object {
        const val TILE_SPEC = "charging_limit"
        private const val SETTING = "charging_limit"
        private const val DISABLED = Int.MAX_VALUE
        private const val MIN_LIMIT = 50
        private const val MAX_LIMIT = 95
        private val PRESETS = intArrayOf(DISABLED, 80, 85, 90)
    }

    private var icon: Icon? = null

    override fun isAvailable(): Boolean {
        val binder = ServiceManager.checkService("custom.system.service.charging") ?: return false
        return try {
            IChargingControlManager.Stub.asInterface(binder).isSupported
        } catch (e: Exception) {
            false
        }
    }

    override fun newTileState() = BooleanState()

    override fun handleClick(expandable: Expandable?) {
        val current = getCurrentLimit()
        val currentIndex = PRESETS.indexOf(current).let { if (it < 0) 0 else it }
        val newLimit = PRESETS[(currentIndex + 1) % PRESETS.size]
        Settings.System.putInt(mContext.contentResolver, SETTING, newLimit)
        refreshState()
    }

    override fun handleSliderChanged(value: Float) {
        val limit = MIN_LIMIT + ((MAX_LIMIT - MIN_LIMIT) * value).roundToInt()
        Settings.System.putInt(mContext.contentResolver, SETTING, limit)
        refreshState()
    }

    override fun handleLongClick(expandable: Expandable?) {
        Settings.System.putInt(mContext.contentResolver, SETTING, DISABLED)
        refreshState()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_charging_limit_label)

    override fun getMetricsCategory() =
        com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val limit = getCurrentLimit()
        val active = limit in MIN_LIMIT..MAX_LIMIT
        state.value = active
        state.sliderEnabled = true
        if (icon == null) {
            icon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_limit)
        }
        state.icon = icon
        state.label = mContext.getString(R.string.quick_settings_charging_limit_label)
        state.state = Tile.STATE_INACTIVE
        if (active) {
            state.secondaryLabel = "$limit%"
            state.sliderShortLabel = "$limit%"
            state.sliderValue = (limit - MIN_LIMIT).toFloat() / (MAX_LIMIT - MIN_LIMIT)
        } else {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_charging_limit_off)
            state.sliderShortLabel = null
            state.sliderValue = 0f
        }
    }

    override fun handleSetListening(listening: Boolean) {}

    private fun getCurrentLimit(): Int =
        Settings.System.getInt(mContext.contentResolver, SETTING, DISABLED)
}
