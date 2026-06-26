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

import android.media.AudioFormat
import android.os.ServiceManager
import android.util.Log
import com.android.settingslib.audiostate.AudioStateRepository.OutputThreadInfo
import custom.media.audio_information.AudioEffectInfo
import custom.media.audio_information.AudioPathInfo
import custom.media.audio_information.AudioSourceInfo
import custom.media.audio_information.IAudioInformation

/**
 * Resolves AudioFlinger output-thread internals (sources, mix format, output flags, effect chain,
 * latency, sink port ids) for the [AudioStateRepository.OutputThreadInfoProvider] hook by talking to
 * the in-server audio-information facade hosted by audioserver.
 *
 * The facade reads AudioFlinger state in-process — output flags, the thread `type_t`, the HAL/DAC
 * format, the per-output effect chain and each thread's own sink port ids are not exposed over any
 * pre-existing binder API, so this is the only path to them from a privileged-but-external process
 * (SystemUI / Settings). The binder is looked up non-blocking; when the facade is absent (older
 * builds, or a stripped image) every call degrades to null and the panel falls back to its phase-1
 * fields.
 *
 * Per-thread, not per-route: this provider exposes *every* active output thread for the snapshot,
 * each described from its own state and tagged with its own sink port id(s). Nothing is "picked" —
 * there is no first()/primary path. The repository matches an enumerated [AudioDevice] to a thread
 * by sink port id ([AudioPathInfo.sinkPortIds] contains `AudioDeviceInfo.getId()`); a thread with no
 * resolvable sink renders without a device. The honest gaps from the data contract (offload bitrate,
 * device-wide effects, true SPDIF DAC clock, DuplicatingThread sinks) are not surfaced here either.
 */
class AudioFlingerInfoProvider : AudioStateRepository.OutputThreadInfoProvider {

    /**
     * Binder handle to the facade, resolved lazily and cached. [ServiceManager.checkService] is
     * non-blocking (never waits for the service to register), so a missing facade costs nothing.
     */
    private val service: IAudioInformation? by lazy { resolveService() }

    override fun outputThreadSnapshot(): List<OutputThreadInfo> {
        // Enumerate ALL active output threads and map each from its own state. No path is chosen and
        // no path is dropped — the repository renders one chain per thread and matches devices by
        // sink port id. Facade absent / query failure → empty list (graceful degrade to phase-1).
        val info = service ?: return emptyList()
        return runCatching {
            info.listActiveAudioPaths()?.map { it.toOutputThreadInfo() }.orEmpty()
        }.getOrElse {
            Log.w(TAG, "audio path query failed", it)
            emptyList()
        }
    }

    private fun AudioPathInfo.toOutputThreadInfo(): OutputThreadInfo {
        // A mixer stage exists only on mixed/bit-perfect/spatializer paths; bypass paths (direct,
        // offload, mmap-exclusive) hand the source straight to the HAL with no mix format.
        //
        // The AudioFlinger stage carries an internal bit-depth round-trip — input → internal → output —
        // and the three formats are kept DISTINCT (never conflated into one) so the UI can render it:
        //   - input    = each active client track's format (built as `sourceList` below)
        //   - internal = mixInternalFormat (mMixerBufferFormat, the real float accumulation format)
        //   - output   = mixFormat (mFormat, the mixer's sink/output PCM format leaving to the HAL)
        // The mix stage's rate/channels carrier ([mix]) uses the OUTPUT (sink) format's bit depth so it
        // no longer drops the real AF-output value; the internal float is carried separately as
        // [afInternalFormat]. Rate and channel count are shared (the float buffer mixes at the same
        // rate/channels), so those come from the mix thread either way.
        val mix =
            if (hasMixerStage)
                AudioFormatSummary(
                    sampleRateHz = mixSampleRate.takeIf { it > 0 },
                    bitDepth = AudioStateLabels.bitDepthForNativeFormat(mixFormat),
                    channelCount = channelCount(mixChannelMask),
                    encodingLabel = AudioStateLabels.nativeFormatLabel(mixFormat),
                )
            else null

        // The mixer's internal accumulation format (mMixerBufferFormat, typically PCM float / 32-bit).
        // This is the middle of the AF round-trip and is kept separate from [mix] (the sink/output
        // format) so the UI renders "input → internal → output" instead of a single conflated number.
        // Null when mixInternalFormat is 0 (AUDIO_FORMAT_INVALID — older facade, or a path with no
        // mixer buffer); the UI then omits the round-trip line rather than assuming float.
        val afInternal =
            if (hasMixerStage && mixInternalFormat != 0)
                AudioFormatSummary(
                    sampleRateHz = mixSampleRate.takeIf { it > 0 },
                    bitDepth = AudioStateLabels.bitDepthForNativeFormat(mixInternalFormat),
                    channelCount = channelCount(mixChannelMask),
                    encodingLabel = AudioStateLabels.nativeFormatLabel(mixInternalFormat),
                )
            else null

        // The hardware/DAC-side format the stream actually leaves through — present on every path.
        val hardware =
            if (hardwareSampleRate > 0 || hardwareFormat != 0)
                AudioFormatSummary(
                    sampleRateHz = hardwareSampleRate.takeIf { it > 0 },
                    bitDepth = AudioStateLabels.bitDepthForNativeFormat(hardwareFormat),
                    channelCount = channelCount(hardwareChannelMask),
                    encodingLabel = AudioStateLabels.nativeFormatLabel(hardwareFormat),
                )
            else null

        // Every active external client track feeding this thread, read straight from the thread's
        // own tracks inside the facade — the only place the real per-track source bit depth is
        // observable, and more authoritative than the AudioPlaybackConfiguration-derived format
        // (rate + channels, no bit depth). The thread's "source" is ALL of these tracks, not a
        // picked one; per-track resampling rides each element. Empty when the facade found no
        // external track. Order is the native pointer-address order (not a signal order) — the model
        // and UI treat it as unordered.
        val sourceList =
            sources.orEmpty().map { src ->
                AudioSource(
                    format =
                        AudioFormatSummary(
                            sampleRateHz = src.sampleRate.takeIf { it > 0 },
                            bitDepth = AudioStateLabels.bitDepthForNativeFormat(src.format),
                            channelCount = channelCount(src.channelMask),
                            encodingLabel = AudioStateLabels.nativeFormatLabel(src.format),
                            // Family (no depth) so the source line shows "16 bit · … · PCM" with the
                            // depth as its own token; the fused encodingLabel stays for other readouts.
                            encodingFamily = AudioStateLabels.nativeFormatFamily(src.format),
                            // Float-ness rides the depth token ("32 bit float"); read from the format.
                            isFloat = AudioStateLabels.isFloatNativeFormat(src.format),
                        ),
                    resampling = src.resampling,
                    // The track's NEGOTIATED output flags (per-track, post-negotiation), labelled as
                    // how the track is configured — never as the (unreadable) raw request.
                    outputFlags = AudioStateLabels.outputFlagLabels(src.outputFlags),
                )
            }

        return OutputThreadInfo(
            sources = sourceList,
            mixFormat = mix,
            afInternalFormat = afInternal,
            hardwareFormat = hardware,
            outputFlags = AudioStateLabels.outputFlagLabels(outputFlags),
            // Per-source truth lives in each AudioSource; the route-level "any source resampling" is
            // derived in the repository, not collapsed here.
            effectChain = effects.orEmpty().map { it.toSummary() },
            latencyMillis = latencyMs.takeIf { it > 0 },
            pathTypeLabel = AudioStateLabels.pathTypeLabel(pathType),
            hasMixerStage = hasMixerStage,
            // Bit-perfect verdict computed in the facade under the AF + thread mutex; the reasons
            // are ready-formatted strings, so they pass straight through to the UI.
            bitPerfect = bitPerfect,
            activeTrackCount = activeTrackCount,
            bitPerfectReasons = bitPerfectReasons.orEmpty().toList(),
            // Authoritative device-match key: each sink's audio_port_handle_t (== AudioDeviceInfo
            // .getId()). Empty when the thread has no patch yet or sinks are unresolvable; the
            // repository then renders this chain without a resolved device.
            sinkPortIds = sinkPortIds?.toList().orEmpty(),
            // MMAP liveness tri-state. Only an MMAP-exclusive path uses the boolean-ish "active / not"
            // signal (its activeTrackCount is 0/1, not a precise mixer count); for that path it is
            // active when it carries a track. For ANY non-MMAP path this MUST be null — not false —
            // so the UI uses the mixer track-count signal instead of printing "Not active" on a live
            // mixer chain.
            mmapActive =
                if (pathType == PATH_TYPE_MMAP_EXCLUSIVE) activeTrackCount > 0 else null,
        )
    }

    private fun AudioEffectInfo.toSummary(): AudioEffectSummary =
        AudioEffectSummary(
            // The native name field is not enforced to be human-readable; fall back to the type UUID
            // when it is blank so the row is never empty.
            name = name?.takeIf { it.isNotBlank() } ?: typeUuid ?: "Effect",
            uuid = typeUuid?.takeIf { it.isNotBlank() },
            // The facade already folds suspension into the effective state; mirror that here.
            enabled = enabled && !suspended,
        )

    private fun channelCount(mask: Int): Int? =
        if (mask == AudioFormat.CHANNEL_INVALID) null
        else AudioFormat.channelCountFromOutChannelMask(mask).takeIf { it > 0 }

    private fun resolveService(): IAudioInformation? =
        runCatching {
            val binder = ServiceManager.checkService(SERVICE_NAME) ?: return null
            IAudioInformation.Stub.asInterface(binder)
        }.getOrElse {
            Log.w(TAG, "service lookup failed", it)
            null
        }

    private companion object {
        const val TAG = "AudioFlingerInfo"
        const val SERVICE_NAME = "custom.media.audio_information"

        /** Mirrors the path-type ints frozen in the AIDL contract (3 = MMAP exclusive). */
        const val PATH_TYPE_MMAP_EXCLUSIVE = 3
    }
}
