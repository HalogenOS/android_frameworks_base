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

import android.content.Context
import android.os.Handler
import android.os.UserHandle
import com.android.settingslib.audiostate.AudioFlingerInfoProvider
import com.android.settingslib.audiostate.AudioStateRepository
import com.android.settingslib.audiostate.LocalBluetoothBatteryProvider
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.audioinfo.domain.model.AudioInfoTileModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Bridges the shared [AudioStateRepository] into the tile pipeline. Each repository emission (a full
 * recompute on any audio change) becomes a fresh [AudioInfoTileModel], so the tile's secondary label
 * tracks the live output device. The repository is owned here and fed the SystemUI background
 * handler for its framework callbacks; the mapping itself runs on the background context.
 */
class AudioInfoTileDataInteractor
@Inject
constructor(
    @Application context: Context,
    @Background private val bgCoroutineContext: CoroutineContext,
    @Background bgHandler: Handler,
    localBluetoothManager: LocalBluetoothManager?,
) : QSTileDataInteractor<AudioInfoTileModel> {

    private val repository =
        AudioStateRepository(
            context.applicationContext,
            bgHandler,
            deviceBatteryProvider = LocalBluetoothBatteryProvider(localBluetoothManager),
            outputThreadProvider = AudioFlingerInfoProvider(),
        )

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<AudioInfoTileModel> =
        repository.audioState
            .map { AudioInfoTileModel(it) }
            .flowOn(bgCoroutineContext)

    /** Always available; this is a read-only diagnostic surface with no hardware precondition. */
    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(true)
}
