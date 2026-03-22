/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.android.systemui.animation.Expandable
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart

@Immutable
data class TileViewModel(private val tile: QSTile, val spec: TileSpec) {
    val state: Flow<QSTile.State> =
        conflatedCallbackFlow {
                val callback = QSTile.Callback { trySend(it.copy()) }

                tile.addCallback(callback)

                awaitClose { tile.removeCallback(callback) }
            }
            .onStart { emit(tile.state) }
            .filterNotNull()
            .distinctUntilChanged()

    val currentState: QSTile.State
        get() = tile.state

    /**
     * Callback for the tile's main click, i.e. the primary function of the tile.
     *
     * @param expandable the [Expandable] to use if expanding to a dialog/activity
     */
    fun mainClick(expandable: Expandable?) {
        tile.click(expandable)
    }

    /**
     * Callback to open the tile's settings page
     *
     * @param expandable the [Expandable] to use if expanding to the settings page
     */
    fun settingsClick(expandable: Expandable?) {
        tile.longClick(expandable)
    }

    /**
     * Callback to the tile's toggle function.
     *
     * This is used for one-tap operations.
     */
    fun toggleClick() {
        tile.secondaryClick(null)
    }

    fun sliderChanged(value: Float) {
        tile.sliderChanged(value)
    }

    fun startListening(token: Any) = tile.setListening(token, true)

    fun stopListening(token: Any) = tile.setListening(token, false)
}
