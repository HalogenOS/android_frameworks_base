/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles.impl.audioinfo.ui.model

import com.android.systemui.Flags
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.AudioInfoTile
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.base.ui.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.base.ui.viewmodel.StubQSTileViewModel
import com.android.systemui.qs.tiles.impl.audioinfo.domain.interactor.AudioInfoTileDataInteractor
import com.android.systemui.qs.tiles.impl.audioinfo.domain.interactor.AudioInfoTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.audioinfo.domain.model.AudioInfoTileModel
import com.android.systemui.qs.tiles.impl.audioinfo.ui.mapper.AudioInfoTileMapper
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/**
 * Dagger wiring for the audio-information tile. Included from the fork-owned CustomModule so the
 * AOSP QSTilesModule stays untouched. Provides both the new-architecture view model (active when
 * [Flags.qsNewTilesFuture] is on) and the legacy [AudioInfoTile] fallback binding.
 */
@Module
interface AudioInfoTileModule {

    /** Legacy fallback binding into the QSTileImpl map (flag-off path). */
    @Binds
    @IntoMap
    @StringKey(AudioInfoTile.TILE_SPEC)
    fun bindAudioInfoTile(tile: AudioInfoTile): QSTileImpl<*>

    /** Lets the QS framework resolve availability without instantiating the tile. */
    @Binds
    @IntoMap
    @StringKey(AudioInfoTile.TILE_SPEC)
    fun provideAudioInfoAvailabilityInteractor(
        impl: AudioInfoTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        const val AUDIO_INFO_TILE_SPEC = AudioInfoTile.TILE_SPEC

        @Provides
        @IntoMap
        @StringKey(AUDIO_INFO_TILE_SPEC)
        fun provideAudioInfoTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(AUDIO_INFO_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_spatial_audio,
                        labelRes = R.string.quick_settings_audio_info_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY,
            )

        @Provides
        @IntoMap
        @StringKey(AUDIO_INFO_TILE_SPEC)
        fun provideAudioInfoTileViewModel(
            factory: QSTileViewModelFactory.Static<AudioInfoTileModel>,
            mapper: AudioInfoTileMapper,
            stateInteractor: AudioInfoTileDataInteractor,
            userActionInteractor: AudioInfoTileUserActionInteractor,
        ): QSTileViewModel =
            if (Flags.qsNewTilesFuture())
                factory.create(
                    TileSpec.create(AUDIO_INFO_TILE_SPEC),
                    userActionInteractor,
                    stateInteractor,
                    mapper,
                )
            else StubQSTileViewModel
    }
}
