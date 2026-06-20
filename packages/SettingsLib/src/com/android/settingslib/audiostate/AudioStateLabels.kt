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

package com.android.settingslib.audiostate

import android.bluetooth.BluetoothCodecConfig
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MicrophoneInfo

/**
 * Pure, side-effect-free mapping from framework constants to neutral, human-readable labels and
 * derived values (bit depth, rate kHz). Kept separate from the repository so it can be reasoned
 * about and unit-tested without any Android service interaction.
 *
 * All labels are intentionally generic ("USB", "Bluetooth", "PCM 24-bit") rather than localized
 * here; the data layer must stay free of resource lookups so the same snapshot is renderable by
 * any host. Renderers may localize on top of these stable keys where desired.
 */
object AudioStateLabels {

    /** Neutral label for an [AudioDeviceInfo] type constant. */
    fun deviceTypeLabel(type: Int): String =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            AudioDeviceInfo.TYPE_FM -> "FM"
            AudioDeviceInfo.TYPE_AUX_LINE -> "AUX line"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line (analog)"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line (digital)"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
            else -> "Unknown ($type)"
        }

    /** Neutral encoding label for an [AudioFormat] encoding constant. */
    fun encodingLabel(encoding: Int): String =
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
            AudioFormat.ENCODING_AC3 -> "AC3"
            AudioFormat.ENCODING_E_AC3 -> "E-AC3"
            AudioFormat.ENCODING_DTS -> "DTS"
            AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
            AudioFormat.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
            AudioFormat.ENCODING_DEFAULT -> "Default"
            AudioFormat.ENCODING_INVALID -> "Invalid"
            else -> "Encoding $encoding"
        }

    /** Determinate PCM bit depth for an encoding, or null for compressed/float/unknown. */
    fun bitDepthForEncoding(encoding: Int): Int? =
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            // Float carries 32 bits per sample but is not an integer "bit depth"; report 32.
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }

    /** Neutral microphone location label. */
    fun micLocationLabel(location: Int): String =
        when (location) {
            MicrophoneInfo.LOCATION_MAINBODY -> "Main body"
            MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "Main body (movable)"
            MicrophoneInfo.LOCATION_PERIPHERAL -> "Peripheral"
            else -> "Unknown"
        }

    /** Neutral microphone directionality label. */
    fun micDirectionalityLabel(directionality: Int): String =
        when (directionality) {
            MicrophoneInfo.DIRECTIONALITY_OMNI -> "Omnidirectional"
            MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "Bi-directional"
            MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "Cardioid"
            MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "Hyper-cardioid"
            MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "Super-cardioid"
            else -> "Unknown"
        }

    /** Neutral Bluetooth A2DP codec name from a [BluetoothCodecConfig] codec type. */
    fun bluetoothCodecName(codecType: Int): String =
        when (codecType) {
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_LC3 -> "LC3"
            BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS -> "Opus"
            else -> "Unknown"
        }

    /** Bluetooth codec sample rate in Hz from the [BluetoothCodecConfig] SAMPLE_RATE_* bitmask. */
    fun bluetoothSampleRateHz(sampleRate: Int): Int? =
        when (sampleRate) {
            BluetoothCodecConfig.SAMPLE_RATE_44100 -> 44100
            BluetoothCodecConfig.SAMPLE_RATE_48000 -> 48000
            BluetoothCodecConfig.SAMPLE_RATE_88200 -> 88200
            BluetoothCodecConfig.SAMPLE_RATE_96000 -> 96000
            BluetoothCodecConfig.SAMPLE_RATE_176400 -> 176400
            BluetoothCodecConfig.SAMPLE_RATE_192000 -> 192000
            else -> null
        }

    /** Bluetooth codec bit depth from the [BluetoothCodecConfig] BITS_PER_SAMPLE_* bitmask. */
    fun bluetoothBitDepth(bitsPerSample: Int): Int? =
        when (bitsPerSample) {
            BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> 16
            BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> 24
            BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> 32
            else -> null
        }

    /** Bluetooth channel-mode label. */
    fun bluetoothChannelLabel(channelMode: Int): String =
        when (channelMode) {
            BluetoothCodecConfig.CHANNEL_MODE_MONO -> "Mono"
            BluetoothCodecConfig.CHANNEL_MODE_STEREO -> "Stereo"
            else -> "Unknown"
        }

    /** Format a rate in Hz as a compact kHz string, e.g. 96000 -> "96 kHz", 44100 -> "44.1 kHz". */
    fun formatRateKHz(rateHz: Int): String {
        val khz = rateHz / 1000.0
        return if (khz == khz.toLong().toDouble()) "${khz.toLong()} kHz"
        else "${(Math.round(khz * 10) / 10.0)} kHz"
    }
}
