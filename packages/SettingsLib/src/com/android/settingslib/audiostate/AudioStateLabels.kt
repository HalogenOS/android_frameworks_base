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

    // Path-type ints, matching the audio-information contract (custom.media.audio_information).
    private const val PATH_TYPE_MIXED = 0
    private const val PATH_TYPE_DIRECT = 1
    private const val PATH_TYPE_OFFLOAD = 2
    private const val PATH_TYPE_MMAP_EXCLUSIVE = 3
    private const val PATH_TYPE_BIT_PERFECT = 4
    private const val PATH_TYPE_SPATIALIZER = 5
    // 6 = UNKNOWN, handled by the else branch of pathTypeLabel.

    // audio_output_flags_t bits (system/audio-hal-enums.h). Each is a distinct power-of-two flag in
    // the raw bitmask the facade reads from the output thread.
    private const val OUTPUT_FLAG_DIRECT = 0x1
    private const val OUTPUT_FLAG_PRIMARY = 0x2
    private const val OUTPUT_FLAG_FAST = 0x4
    private const val OUTPUT_FLAG_DEEP_BUFFER = 0x8
    private const val OUTPUT_FLAG_COMPRESS_OFFLOAD = 0x10
    private const val OUTPUT_FLAG_NON_BLOCKING = 0x20
    private const val OUTPUT_FLAG_HW_AV_SYNC = 0x40
    private const val OUTPUT_FLAG_RAW = 0x100
    private const val OUTPUT_FLAG_SYNC = 0x200
    private const val OUTPUT_FLAG_IEC958_NONAUDIO = 0x400
    private const val OUTPUT_FLAG_MMAP_NOIRQ = 0x4000
    private const val OUTPUT_FLAG_VOIP_RX = 0x8000
    private const val OUTPUT_FLAG_SPATIALIZER = 0x40000
    private const val OUTPUT_FLAG_ULTRASOUND = 0x80000
    private const val OUTPUT_FLAG_BIT_PERFECT = 0x100000

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

    // Native audio_format_t values (system/media/audio audio-base.h). These are a DIFFERENT number
    // space from AudioFormat.ENCODING_* above — values arriving from the AudioFlinger facade are
    // raw audio_format_t and must be mapped with these, never with encodingLabel()/bitDepthForEncoding().
    private const val NATIVE_PCM_16_BIT = 0x1
    private const val NATIVE_PCM_8_BIT = 0x2
    private const val NATIVE_PCM_32_BIT = 0x3
    private const val NATIVE_PCM_8_24_BIT = 0x4
    private const val NATIVE_PCM_FLOAT = 0x5
    private const val NATIVE_PCM_24_BIT_PACKED = 0x6

    /** Neutral label for a native audio_format_t value (as delivered by the AudioFlinger facade). */
    fun nativeFormatLabel(nativeFormat: Int): String =
        when (nativeFormat) {
            NATIVE_PCM_16_BIT -> "PCM 16-bit"
            NATIVE_PCM_8_BIT -> "PCM 8-bit"
            NATIVE_PCM_32_BIT -> "PCM 32-bit"
            NATIVE_PCM_8_24_BIT -> "PCM 8.24-bit"
            NATIVE_PCM_FLOAT -> "PCM float"
            NATIVE_PCM_24_BIT_PACKED -> "PCM 24-bit"
            else -> "Format 0x${Integer.toHexString(nativeFormat)}"
        }

    /**
     * Encoding FAMILY label for a native audio_format_t — the encoding nature WITHOUT the bit depth,
     * so a readout can show depth and family as separate tokens ("16 bit · … · PCM" rather than the
     * fused "PCM 16-bit"). Integer PCM variants all collapse to "PCM" (their depth is carried by
     * [bitDepthForNativeFormat]); float keeps "PCM float" because float is the encoding's nature, not a
     * bit count. Unknown formats fall back to the full hex label (no family to strip).
     */
    fun nativeFormatFamily(nativeFormat: Int): String =
        when (nativeFormat) {
            NATIVE_PCM_8_BIT,
            NATIVE_PCM_16_BIT,
            NATIVE_PCM_32_BIT,
            NATIVE_PCM_8_24_BIT,
            NATIVE_PCM_24_BIT_PACKED,
            // Float is PCM too: "PCM" is the family, and float-ness is a sample-representation
            // specialization carried by the DEPTH token ("32 bit float"), not the family.
            NATIVE_PCM_FLOAT -> "PCM"
            else -> "Format 0x${Integer.toHexString(nativeFormat)}"
        }

    /**
     * Whether a native audio_format_t encodes samples as floating point (the "float" specialization of
     * the depth token, e.g. "32 bit float"). Read from the format constant, never inferred.
     */
    fun isFloatNativeFormat(nativeFormat: Int): Boolean = nativeFormat == NATIVE_PCM_FLOAT

    /** Determinate PCM bit depth for a native audio_format_t value, or null for non-PCM/unclean. */
    fun bitDepthForNativeFormat(nativeFormat: Int): Int? =
        when (nativeFormat) {
            NATIVE_PCM_8_BIT -> 8
            NATIVE_PCM_16_BIT -> 16
            NATIVE_PCM_24_BIT_PACKED -> 24
            NATIVE_PCM_32_BIT -> 32
            // Float carries 32 bits per sample but is not an integer "bit depth"; report 32.
            NATIVE_PCM_FLOAT -> 32
            // 8.24 is a 32-bit container with 24 significant bits — not a clean integer depth.
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

    /**
     * Neutral label for a path-type int from the audio-information contract. Describes which
     * AudioFlinger data path an active stream actually runs on.
     */
    fun pathTypeLabel(pathType: Int): String =
        when (pathType) {
            PATH_TYPE_MIXED -> "Mixed"
            PATH_TYPE_DIRECT -> "Direct"
            PATH_TYPE_OFFLOAD -> "Offload"
            PATH_TYPE_MMAP_EXCLUSIVE -> "MMAP exclusive"
            PATH_TYPE_BIT_PERFECT -> "Bit-perfect"
            PATH_TYPE_SPATIALIZER -> "Spatializer"
            else -> "Unknown"
        }

    /**
     * Decomposes a raw `audio_output_flags_t` bitmask into neutral flag labels, in bit order. Only
     * the flags worth surfacing on a diagnostic readout are named; unknown bits are dropped rather
     * than shown as raw numbers. Mirrors the native `flagsAsString()` vocabulary.
     */
    fun outputFlagLabels(flags: Int): List<String> =
        buildList {
            if (flags and OUTPUT_FLAG_DIRECT != 0) add("DIRECT")
            if (flags and OUTPUT_FLAG_PRIMARY != 0) add("PRIMARY")
            if (flags and OUTPUT_FLAG_FAST != 0) add("FAST")
            if (flags and OUTPUT_FLAG_DEEP_BUFFER != 0) add("DEEP_BUFFER")
            if (flags and OUTPUT_FLAG_COMPRESS_OFFLOAD != 0) add("OFFLOAD")
            if (flags and OUTPUT_FLAG_NON_BLOCKING != 0) add("NON_BLOCKING")
            if (flags and OUTPUT_FLAG_HW_AV_SYNC != 0) add("HW_AV_SYNC")
            if (flags and OUTPUT_FLAG_RAW != 0) add("RAW")
            if (flags and OUTPUT_FLAG_SYNC != 0) add("SYNC")
            if (flags and OUTPUT_FLAG_IEC958_NONAUDIO != 0) add("IEC958_NONAUDIO")
            if (flags and OUTPUT_FLAG_MMAP_NOIRQ != 0) add("MMAP_NOIRQ")
            if (flags and OUTPUT_FLAG_VOIP_RX != 0) add("VOIP_RX")
            if (flags and OUTPUT_FLAG_SPATIALIZER != 0) add("SPATIALIZER")
            if (flags and OUTPUT_FLAG_ULTRASOUND != 0) add("ULTRASOUND")
            if (flags and OUTPUT_FLAG_BIT_PERFECT != 0) add("BIT_PERFECT")
        }

    /**
     * Format a rate in Hz as an exact kHz string — no rounding, only trailing-zero trimming.
     * 48000 -> "48 kHz", 44100 -> "44.1 kHz", 11025 -> "11.025 kHz", 22050 -> "22.05 kHz".
     * Uses integer arithmetic on the Hz value so the kHz fraction is always exact (a rate is an
     * integer number of Hz, so dividing by 1000 has at most three decimal places).
     */
    fun formatRateKHz(rateHz: Int): String {
        val whole = rateHz / 1000
        val remainderHz = rateHz % 1000
        if (remainderHz == 0) return "$whole kHz"
        // Three-digit fraction (Hz remainder out of 1000), with trailing zeros trimmed.
        val fraction = "%03d".format(remainderHz).trimEnd('0')
        return "$whole.$fraction kHz"
    }
}
