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
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTile.Icon
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R

import javax.inject.Inject

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
        private val LIMITS = intArrayOf(0, 80, 85, 90)
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
        val currentIndex = LIMITS.indexOf(current).let { if (it < 0) 0 else it }
        val nextIndex = (currentIndex + 1) % LIMITS.size
        val newLimit = LIMITS[nextIndex]
        Settings.System.putInt(mContext.contentResolver, SETTING, newLimit)
        refreshState()
    }

    override fun handleLongClick(expandable: Expandable?) {
        // Long press disables
        Settings.System.putInt(mContext.contentResolver, SETTING, 0)
        refreshState()
    }

    override fun getLongClickIntent(): Intent? = null

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_charging_limit_label)

    override fun getMetricsCategory() =
        com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val limit = getCurrentLimit()
        state.value = limit > 0
        if (icon == null) {
            icon = maybeLoadResourceIcon(R.drawable.ic_qs_charging_limit)
        }
        state.icon = icon
        state.label = mContext.getString(R.string.quick_settings_charging_limit_label)
        if (limit > 0) {
            state.secondaryLabel = "$limit%"
            state.state = Tile.STATE_ACTIVE
        } else {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_charging_limit_off)
            state.state = Tile.STATE_INACTIVE
        }
    }

    override fun handleSetListening(listening: Boolean) {}

    private fun getCurrentLimit(): Int =
        Settings.System.getInt(mContext.contentResolver, SETTING, 0)
}
