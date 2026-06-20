/*
 * Copyright (C) 2024 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.custom

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.CaffeineTile
import com.android.systemui.qs.tiles.ChargingLimitTile
import com.android.systemui.qs.tiles.PowerShareTile
import com.android.systemui.qs.tiles.ScreenshotTile
import com.android.systemui.qs.tiles.impl.audioinfo.ui.model.AudioInfoTileModule
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module(includes = [AudioInfoTileModule::class])
interface CustomModule {
    /** Inject CaffeineTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CaffeineTile.TILE_SPEC)
    fun bindCaffeineTile(caffeineTile: CaffeineTile): QSTileImpl<*>

    /** Inject ChargingLimitTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ChargingLimitTile.TILE_SPEC)
    fun bindChargingLimitTile(chargingLimitTile: ChargingLimitTile): QSTileImpl<*>

    /** Inject PowerShareTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(PowerShareTile.TILE_SPEC)
    fun bindPowerShareTile(powerShareTile: PowerShareTile): QSTileImpl<*>

    /** Inject ScreenshotTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ScreenshotTile.TILE_SPEC)
    fun bindScreenshotTile(screenshotTile: ScreenshotTile): QSTileImpl<*>

    companion object {
        const val CAFFEINE_TILE_SPEC = "caffeine"
        const val CHARGING_LIMIT_TILE_SPEC = "charging_limit"
        const val SCREENSHOT_TILE_SPEC = "screenshot"

        @Provides
        @IntoMap
        @StringKey(CAFFEINE_TILE_SPEC)
        fun provideCaffeineTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CAFFEINE_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_caffeine,
                        labelRes = R.string.quick_settings_caffeine_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(CHARGING_LIMIT_TILE_SPEC)
        fun provideChargingLimitTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(CHARGING_LIMIT_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_charging_limit,
                        labelRes = R.string.quick_settings_charging_limit_label
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )

        @Provides
        @IntoMap
        @StringKey(SCREENSHOT_TILE_SPEC)
        fun provideScreenshotTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(SCREENSHOT_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = com.android.internal.R.drawable.ic_screenshot,
                        labelRes = com.android.internal.R.string.global_action_screenshot
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
