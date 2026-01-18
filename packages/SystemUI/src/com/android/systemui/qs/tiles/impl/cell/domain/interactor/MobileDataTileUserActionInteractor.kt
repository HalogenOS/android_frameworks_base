/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.cell.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.animation.Expandable
import com.android.systemui.qs.tiles.base.domain.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.domain.model.QSTileInput
import com.android.systemui.qs.tiles.base.shared.model.QSTileUserAction
import com.android.systemui.qs.tiles.impl.cell.domain.model.MobileDataTileModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import javax.inject.Inject

class MobileDataTileUserActionInteractor
@Inject
constructor(
    private val mobileConnectionsRepository: MobileConnectionsRepository,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<MobileDataTileModel> {
    val longClickIntent = Intent(Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS)

    override suspend fun handleInput(input: QSTileInput<MobileDataTileModel>) {
        when (input.action) {
            is QSTileUserAction.Click -> {
                handleClick(input.action.expandable)
            }
            is QSTileUserAction.LongClick -> {
                qsTileIntentUserActionHandler.handle(input.action.expandable, longClickIntent)
            }
            else -> {}
        }
    }

    suspend fun handleClick(expandable: Expandable?) {
        val activeRepo = mobileConnectionsRepository.activeMobileDataRepository.value ?: return
        activeRepo.setDataEnabled(!activeRepo.dataEnabled.value)
    }
}
