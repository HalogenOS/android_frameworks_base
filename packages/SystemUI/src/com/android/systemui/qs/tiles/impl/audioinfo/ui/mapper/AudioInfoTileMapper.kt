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

package com.android.systemui.qs.tiles.impl.audioinfo.ui.mapper

import android.content.res.Resources
import com.android.settingslib.audiostate.AudioStateSnapshot
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.audioinfo.domain.model.AudioInfoTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/** Maps [AudioInfoTileModel] to [QSTileState], surfacing the active output as the secondary label. */
class AudioInfoTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<AudioInfoTileModel> {

    override fun map(config: QSTileConfig, data: AudioInfoTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.quick_settings_audio_info_label)
            contentDescription = label
            val iconRes = R.drawable.ic_spatial_audio
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            activationState = QSTileState.ActivationState.ACTIVE
            secondaryLabel = activeOutputSummary(data.snapshot)
        }

    /** A short description of where audio is currently going, for the tile's secondary line. */
    private fun activeOutputSummary(snapshot: AudioStateSnapshot): String? {
        val device =
            snapshot.routes.firstOrNull()?.outputDevice
                ?: snapshot.outputDevices.firstOrNull { it.isActive }
                ?: snapshot.outputDevices.firstOrNull()
        return device?.name
    }
}
