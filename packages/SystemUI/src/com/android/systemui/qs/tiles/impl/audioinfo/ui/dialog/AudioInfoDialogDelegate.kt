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

package com.android.systemui.qs.tiles.impl.audioinfo.ui.dialog

import android.content.Context
import android.content.Intent
import android.os.Handler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.theme.PlatformTheme
import com.android.settingslib.audiostate.AudioStateRepository
import com.android.settingslib.audiostate.AudioStateSnapshot
import com.android.settingslib.audiostate.LocalBluetoothBatteryProvider
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.audiostate.compose.AudioStateTree
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import javax.inject.Inject

/**
 * A live audio-information dialog rendered with the shared [AudioStateTree] composable. The dialog
 * collects the [AudioStateRepository] flow so it updates in place as devices and routes change
 * while open. The same composable backs the Settings page, so the two surfaces stay identical.
 */
class AudioInfoDialogDelegate
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgHandler: Handler,
    private val sysuiDialogFactory: SystemUIDialogFactory,
    localBluetoothManager: LocalBluetoothManager?,
) : SystemUIDialog.Delegate {

    private val repository =
        AudioStateRepository(
            context.applicationContext,
            bgHandler,
            deviceBatteryProvider = LocalBluetoothBatteryProvider(localBluetoothManager),
        )

    override fun createDialog(): SystemUIDialog =
        sysuiDialogFactory.create(context = context) { dialog -> AudioInfoDialogContent(dialog) }

    @Composable
    private fun AudioInfoDialogContent(dialog: SystemUIDialog) {
        // Cache the theme at open time to avoid the known background/foreground mismatch when the
        // system theme flips while a dialog is showing (see FontScaling/Flashlight workaround).
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }
        val snapshot: AudioStateSnapshot? by
            repository.audioState.collectAsStateWithLifecycle(initialValue = null)

        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.quick_settings_audio_info_label)) },
                content = {
                    val current = snapshot
                    if (current != null) {
                        AudioStateTree(
                            snapshot = current,
                            modifier =
                                Modifier.heightIn(max = 480.dp)
                                    .verticalScroll(rememberScrollState()),
                            full = false,
                        )
                    }
                },
                neutralButton = {
                    PlatformOutlinedButton(
                        onClick = {
                            openAudioInformationSettings()
                            dialog.dismiss()
                        }
                    ) {
                        Text(stringResource(R.string.quick_settings_more))
                    }
                },
                positiveButton = {
                    PlatformButton(onClick = { dialog.dismiss() }) {
                        Text(stringResource(R.string.quick_settings_done))
                    }
                },
            )
        }
    }

    private fun openAudioInformationSettings() {
        val intent =
            Intent()
                .setClassName(SETTINGS_PACKAGE, SPA_ACTIVITY_CLASS)
                .putExtra(SPA_DESTINATION_EXTRA, AUDIO_INFO_SPA_DESTINATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SPA_ACTIVITY_CLASS = "com.android.settings.spa.SpaActivity"
        const val SPA_DESTINATION_EXTRA = "spaActivityDestination"
        const val AUDIO_INFO_SPA_DESTINATION = "AudioInformation"
    }
}
