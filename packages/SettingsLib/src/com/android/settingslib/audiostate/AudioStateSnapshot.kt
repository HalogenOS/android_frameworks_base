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

/**
 * Immutable, fully recomputable description of the device's current audio state.
 *
 * This is the single contract shared by every renderer (the Quick Settings dialog and the
 * full settings page) and produced by a single repository. It is deterministic and reloadable:
 * the entire tree can be rebuilt from scratch at any moment with no dependence on prior state.
 *
 * The model deliberately describes the *system's* view of the audio path — what a stream
 * travels through on-device (mixer thread, effects) and how it leaves the device (output
 * interface, codec) — not any single app's internal DSP graph, which the platform cannot observe.
 *
 * Fields sourced from public framework APIs are populated in phase 1. Fields that require the
 * structured AudioFlinger accessors (mix format, output flags, resampling, effect chain) are
 * nullable and remain null until those accessors land; renderers must treat null as "unknown"
 * and omit the corresponding rows. Freezing them here keeps the contract stable across phases.
 */
data class AudioStateSnapshot(
    /**
     * Active output routes, ordered by relevance (the primary media route first).
     *
     * Renderers may show only [routes].first() (single active chain) or the full list.
     * Always at least conceptually present; empty when nothing is actively playing, in which
     * case renderers fall back to the currently selected output device from [outputDevices].
     */
    val routes: List<AudioRoute>,

    /** Active capture/input route, if any recording is in progress. */
    val inputRoute: AudioInputRoute?,

    /** Every enumerated output device (sinks) with full capability matrices. */
    val outputDevices: List<AudioDevice>,

    /** Every enumerated input device (sources) with full capability matrices. */
    val inputDevices: List<AudioDevice>,

    /** Available microphones and their characteristics. */
    val microphones: List<MicrophoneInfoSummary>,

    /** Wall-clock millis the snapshot was produced; renderers may show "as of". */
    val timestampMillis: Long,
)

/**
 * One end-to-end output chain: the stream as it leaves the mixer, through any effects, out the
 * physical interface. Mirrors the vertical "Output -> Output Device" layout of the reference UX.
 */
data class AudioRoute(
    /** Human label for what this route carries, e.g. "Media", "Call", "Notification". */
    val usageLabel: String,

    /** Format of the active track feeding this route (what the app handed to the system). */
    val sourceFormat: AudioFormatSummary?,

    // --- Phase 2: AudioFlinger output-thread internals (null until @hide accessors land) ---

    /** The mixer thread's actual output format. When this differs from [sourceFormat], the
     *  system is resampling/reformatting; renderers draw the "384k -> 96k" style arrow. */
    val mixFormat: AudioFormatSummary?,

    /** Output flags on the active path, e.g. DIRECT, FAST, RAW, DEEP_BUFFER. */
    val outputFlags: List<String>?,

    /** True when the mixer thread resamples this route's source rate. Null = unknown. */
    val resampling: Boolean?,

    /** Active effects applied on this output, in chain order (e.g. equalizer, loudness). */
    val effectChain: List<AudioEffectSummary>?,

    /** Reported output latency in milliseconds, if available. */
    val latencyMillis: Int?,

    // --- The physical interface the stream leaves through ---

    /** The device this route is currently routed to (the "Output Device" block). */
    val outputDevice: AudioDevice?,

    /** Bluetooth codec in use when [outputDevice] is a Bluetooth sink. */
    val bluetoothCodec: BluetoothCodecSummary?,
)

/** Active capture route counterpart to [AudioRoute]. */
data class AudioInputRoute(
    val usageLabel: String,
    val captureFormat: AudioFormatSummary?,
    val inputDevice: AudioDevice?,
)

/**
 * An enumerated audio device with its full capability matrix. Maps onto AudioDeviceInfo plus
 * a few derived/identity fields (e.g. USB DAC product name).
 */
data class AudioDevice(
    /** Stable id from AudioDeviceInfo.getId(). */
    val id: Int,
    /** Display name (product name where available, else type label). */
    val name: String,
    /** AudioDeviceInfo type constant. */
    val type: Int,
    /** Neutral, human label for [type], e.g. "USB", "Bluetooth A2DP", "Speaker". */
    val typeLabel: String,
    /** True for sinks, false for sources. */
    val isSink: Boolean,
    /** Whether this device is the one currently carrying audio. */
    val isActive: Boolean,
    /** Hardware/MAC-style address where exposed. */
    val address: String?,
    /** Supported sample rates in Hz (empty = device accepts any/unspecified). */
    val sampleRates: List<Int>,
    /** Supported channel counts. */
    val channelCounts: List<Int>,
    /** Supported channel masks (raw), for advanced display. */
    val channelMasks: List<Int>,
    /** Supported encodings as neutral labels, e.g. "PCM 24-bit", "PCM Float". */
    val encodings: List<String>,
    /**
     * Battery level of the accessory backing this device (e.g. a Bluetooth headset), 0–100, or
     * null when the device reports no battery (built-in speaker, USB DAC without reporting, etc.).
     * The phone's own battery is intentionally never surfaced here.
     */
    val batteryPercent: Int? = null,
)

/** A concrete format: rate / depth / channels / encoding. Used for source, mix, and device. */
data class AudioFormatSummary(
    val sampleRateHz: Int?,
    /** Bit depth derived from the encoding where determinate (16/24/32), else null. */
    val bitDepth: Int?,
    val channelCount: Int?,
    /** Neutral encoding label, e.g. "PCM 24-bit", "PCM Float", "AC3". */
    val encodingLabel: String?,
)

/** Bluetooth codec line, e.g. "LDAC 32 bit 96 kHz". */
data class BluetoothCodecSummary(
    val codecName: String,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channelLabel: String?,
)

/** One effect in an output's active chain. */
data class AudioEffectSummary(
    val name: String,
    val uuid: String?,
    val enabled: Boolean,
)

/** Microphone summary from AudioManager.getMicrophones(). */
data class MicrophoneInfoSummary(
    val description: String,
    val address: String?,
    /** Neutral location label, e.g. "Bottom", "Top", "Peripheral". */
    val locationLabel: String?,
    /** Neutral directionality label, e.g. "Omnidirectional", "Cardioid". */
    val directionalityLabel: String?,
)
