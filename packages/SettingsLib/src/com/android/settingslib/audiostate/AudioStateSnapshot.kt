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
     * Active output chains — one per active output thread, not per app/usage. Each entry is a
     * complete chain described entirely from one thread's own state (its own sources, its own
     * AudioFlinger stage, its own sink device matched by port id). Nothing here is "the primary
     * route": multi-output hardware produces several entries, and the renderer stacks them.
     *
     * Renderers MUST render every entry (the stacked chains) — never select one by position; picking
     * "the first" chain is the banned heuristic this model exists to remove. Empty when no output
     * thread is active, in which case renderers fall back to the currently selected output device
     * from [outputDevices].
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
 * One end-to-end output chain for a single active output thread: the thread's source track(s) as
 * they feed the mixer, through the AudioFlinger stage and any effects, out the thread's own sink.
 * Mirrors the vertical "Output -> Output Device" layout of the reference UX. A thread can mix
 * several client tracks, so a chain carries *all* of its [sources], never a single picked one.
 */
data class AudioRoute(
    /**
     * Human label for what this chain carries, e.g. "Media", "Call", "Notification". Derived from
     * the routed usage where it can still be attributed to this thread; "Audio" otherwise. Usage no
     * longer selects the thread — it is display only.
     */
    val usageLabel: String,

    /**
     * Every active external client track feeding this thread's mixer, each with its own format and
     * per-source resampling verdict. This is the truth: a thread's "source" is all of these tracks,
     * never a single first()/pointer-order pick. Listed unordered (the native side reads them in
     * pointer-address order, which is not a signal/pipeline order — see the design doc's
     * "Track ordering" gap). Empty when the facade found no external track or was absent.
     */
    val sources: List<AudioSource> = emptyList(),

    /**
     * Whether a stream is actually flowing through this chain right now. A standing AudioFlinger
     * thread (e.g. the PRIMARY or telephony mixer) stays alive and patched to its sink even when
     * nothing plays; such a chain is real but idle. True only when the thread carries ≥1 active
     * source (playback: an active client track; MMAP: the thread is active). Renderers draw an idle
     * chain de-emphasized and omit its Source stage (there is no source to show), so the UI never
     * implies a flow that is not happening.
     */
    val isPlaying: Boolean = false,

    /**
     * A single representative source format for renderers that draw one Source stage. This is a
     * *display* convenience derived from [sources] (the first listed track when exactly one source
     * is present, else null so the renderer does not imply a picked source); the per-source truth
     * always lives in [sources]. Falls back to the AudioPlaybackConfiguration format when the
     * facade contributed no source.
     */
    val sourceFormat: AudioFormatSummary?,

    /**
     * True when [sourceFormat] / [sources] were contributed by the audioserver facade (real
     * per-track bit depth) rather than the AudioPlaybackConfiguration fallback. Drives the Source
     * stage's Hybrid provenance. Null/false when the facade was absent and the source came from
     * AudioManager only.
     */
    val sourceFromFacade: Boolean? = null,

    // --- Phase 2: AudioFlinger output-thread internals (null until @hide accessors land) ---

    /** The AudioFlinger mixer stage's *output* (sink) format: rate and channels from the mix thread,
     *  and the bit depth/encoding of what leaves the mixer to the HAL (mFormat) — the output end of
     *  the AF bit-depth round-trip. The internal float accumulation format is carried separately in
     *  [afInternalFormat]; the source/AF-input format is [sourceFormat]. Renderers draw the source→mix
     *  "384k -> 96k" rate arrow from its rate when that differs from [sourceFormat]. */
    val mixFormat: AudioFormatSummary?,

    /** The mixer's *internal* accumulation format (typically 32-bit PCM float) — the middle of the
     *  AudioFlinger bit-depth round-trip (input [sourceFormat] → internal → output [mixFormat]).
     *  Null when the facade reported none (older facade / no mixer buffer); renderers then omit the
     *  round-trip line rather than assuming float. */
    val afInternalFormat: AudioFormatSummary? = null,

    /** The actual HAL/DAC-side format the stream leaves through. When its rate differs from the
     *  feeding rate (mix rate, or source rate on a bypass path) the stream is resampled on the way
     *  to the hardware; renderers draw the device-side "384k -> 96k" arrow from this. */
    val hardwareFormat: AudioFormatSummary?,

    /** Output flags on the active path, e.g. DIRECT, FAST, RAW, DEEP_BUFFER. */
    val outputFlags: List<String>?,

    /**
     * Derived "any source resampling" verdict: true when *any* of this thread's [sources] resamples
     * against the thread rate. Each track resamples independently, so the per-source truth lives in
     * [AudioSource.resampling]; this is a route-level convenience for renderers that show one
     * Resampling row. Null = unknown (facade absent / phase 1).
     */
    val resampling: Boolean?,

    /** Active effects applied on this output, in chain order (e.g. equalizer, loudness). */
    val effectChain: List<AudioEffectSummary>?,

    /** Reported output latency in milliseconds, if available. */
    val latencyMillis: Int?,

    /** Neutral label for the data path this route runs on, e.g. "Mixed", "Direct", "Offload". */
    val pathTypeLabel: String? = null,

    /**
     * Whether this route has a mixer stage. Null = unknown (phase 1). False = a bypass path
     * (direct/offload/mmap) where the source is handed straight to the HAL with no mix/resample
     * stage; renderers say so explicitly rather than drawing a hollow Output stage.
     */
    val hasMixerStage: Boolean? = null,

    /**
     * Bit-perfect verdict for this route: true only when the output delivers bit-exact samples (a
     * BIT_PERFECT thread with one active bit-perfect track and all others muted). Null = unknown
     * (phase 1 / facade absent); renderers omit the verdict row when null.
     */
    val bitPerfect: Boolean? = null,

    /**
     * Number of active, audible (non-muted) tracks on this thread. Null = unknown. NOTE: for an
     * MMAP thread (see [mmapActive]) this is *not* a precise mixer track count — the native side can
     * only report a boolean-ish "active / not" for MMAP — so renderers must not present an MMAP
     * value as "Mixing N active tracks". Honest only as a count for mixer (playback) threads.
     */
    val activeTrackCount: Int? = null,

    /**
     * True when this chain is an MMAP thread, whose [activeTrackCount] is a boolean-ish "active /
     * not" rather than a precise mixer track count (the native MMAP path lacks the playback
     * active-set check). Renderers use this to avoid the playback-mixer "Mixing N active tracks"
     * phrasing for MMAP. Null = unknown (facade absent / not applicable).
     */
    val mmapActive: Boolean? = null,

    /**
     * Discrete reasons the verdict is No, in signal order (e.g. "Mixed path (float re-mix)",
     * "Mixing 2 active tracks", "Resampling 48 → 44 kHz"). Empty when [bitPerfect] is true; null
     * when unknown.
     */
    val bitPerfectReasons: List<String>? = null,

    // --- The physical interface the stream leaves through ---

    /** The device this route is currently routed to (the "Output Device" block). */
    val outputDevice: AudioDevice?,

    /**
     * Tri-state knowledge of whether this thread has a sink, used to tell the truth when [outputDevice]
     * is null. The facade reports the thread's sink port ids, so:
     *  - false → the facade says the thread has ZERO sinks (empty device types / unpatched) → there is
     *    genuinely NO output device. Render "No output device" — never "could not be resolved" (which
     *    would falsely assert a device exists).
     *  - true  → the facade says the thread has ≥1 sink port id, but none matched an enumerated device →
     *    a device exists but could not be identified. "Unidentified" is honest here.
     *  - null  → no facade sink info at all (phase-1 fallback) → we genuinely don't know. Render the
     *    neutral "unknown" wording, asserting neither presence nor absence.
     */
    val hasSinkPortId: Boolean? = null,

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
    /**
     * Encoding FAMILY without the bit depth, e.g. "PCM" / "PCM float" / "AC3" — so a readout can show
     * depth and family as separate tokens ("16 bit · … · PCM") instead of the fused [encodingLabel]
     * ("PCM 16-bit"). Null when not computed (only the Source stage decomposes this way; AF/Device use
     * [encodingLabel]).
     */
    val encodingFamily: String? = null,
    /**
     * Whether the samples are floating-point, so the depth token reads "32 bit float" (a
     * sample-representation specialization), with "PCM" staying as the [encodingFamily]. Read from the
     * real format constant, never inferred. Default false (integer/unknown).
     */
    val isFloat: Boolean = false,
)

/**
 * One active external client track feeding an output thread's mixer: its real source format plus
 * whether it resamples against the thread rate. Each track resamples independently, so resampling
 * is a property of the track, not the chain.
 */
data class AudioSource(
    /** This track's real source format (rate / depth / channels / encoding). */
    val format: AudioFormatSummary,
    /** True when this track's source rate differs from the thread's rate (this track resamples). */
    val resampling: Boolean,
    /**
     * The track's NEGOTIATED output flags as neutral labels (e.g. "FAST", "DIRECT") from
     * `IAfTrack::getOutputFlags()` — how this track is *configured* after AudioFlinger negotiation,
     * a per-track source-side property distinct from the thread flags. Honest naming only: this is
     * NOT the raw app request (which is unreadable), so renderers label it "Track: FAST" / "Direct",
     * never "Requested". Empty when the facade reported no flags.
     */
    val outputFlags: List<String> = emptyList(),
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
