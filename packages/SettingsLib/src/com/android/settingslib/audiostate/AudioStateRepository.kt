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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MicrophoneInfo
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Single source of truth for the device's audio state, shared by every renderer.
 *
 * Each emission of [audioState] is a *complete recompute* via [snapshot]; there is no incremental
 * mutation, which makes the state deterministic and trivially reloadable. The flow re-emits a fresh
 * snapshot whenever a relevant subsystem signals a change (devices added/removed, playback config
 * changed, battery changed). Callers that just want a one-shot read can call [snapshot] directly.
 *
 * Hosting context must be platform-privileged (SystemUI / Settings) for the richest fields:
 * - Active-track format ([AudioPlaybackConfiguration.getSampleRate]/getChannelMask) is @SystemApi
 *   and needs MODIFY_AUDIO_ROUTING.
 * - Bluetooth codec status is @SystemApi and needs BLUETOOTH_CONNECT.
 * Each such read is guarded; a [SecurityException] degrades that field to null rather than failing
 * the whole snapshot, so the component remains useful even under a reduced permission set.
 *
 * @param context a long-lived context (application context recommended).
 * @param handler handler on which framework callbacks are delivered. The emitting flow is cold;
 *   the handler thread only posts change *triggers*, the heavy mapping runs on the collector.
 * @param codecProvider optional hook supplying the active Bluetooth codec; injected so the data
 *   layer does not hard-depend on the Bluetooth stack and remains unit-testable. May return null.
 * @param outputThreadProvider optional hook supplying the per-thread AudioFlinger snapshot (sources,
 *   mix format, flags, effects, sink port ids). Null in phase 1; wired to the @hide facade later.
 */
class AudioStateRepository(
    private val context: Context,
    private val handler: Handler,
    private val codecProvider: BluetoothCodecProvider? = null,
    private val deviceBatteryProvider: DeviceBatteryProvider? = null,
    private val outputThreadProvider: OutputThreadInfoProvider? = null,
) {
    private val audioManager: AudioManager =
        context.getSystemService(AudioManager::class.java)

    /**
     * Live stream of complete snapshots. Cold: work happens per-collector. Conflated so a burst of
     * triggers collapses to the latest recompute, and de-duplicated so identical snapshots (the
     * data classes give us structural equality for free) don't churn the UI.
     */
    val audioState: Flow<AudioStateSnapshot> =
        changeTriggers()
            .conflate()
            .map { snapshot() }
            .distinctUntilChanged()

    /**
     * Build a full snapshot from the current system state. Safe to call from any thread; performs
     * no mutation. Intended for one-shot reads and as the per-emission recompute for [audioState].
     */
    fun snapshot(): AudioStateSnapshot {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val activePlaybacks = readActivePlaybackConfigurations()
        // Pull the per-thread snapshot once: every active output thread, each carrying its own sink
        // port id(s). Empty when the facade is absent (phase 1) — buildRoutes then degrades to a
        // usage-based view with null thread info. Reading it once keeps the whole route build against
        // one stable thread set.
        val outputThreads = readOutputThreadSnapshot()

        // Enumerate devices first (without active state), then build the chains.
        val baseOutputDevices = outputs.map { it.toAudioDevice(isActive = false) }
        val routes = buildRoutes(outputThreads, activePlaybacks, baseOutputDevices)

        // ACTIVE marks a device the system is *currently routing audio to* — a routing truth, NOT
        // "this device is some standing thread's sink". A device is the sink of an idle PRIMARY/
        // telephony thread even when nothing plays, so deriving ACTIVE from the rendered chains would
        // falsely light up an idle speaker. Instead resolve the live route for each active playback
        // (the same policy query usage labelling uses): no active playback → nothing routed → no
        // ACTIVE pill.
        val activeOutputDeviceIds =
            activePlaybacks.mapNotNull { routedDeviceFor(it.usage, baseOutputDevices)?.id }.toSet()
        val outputDevices =
            baseOutputDevices.map { it.copy(isActive = activeOutputDeviceIds.contains(it.id)) }
        val inputDevices = inputs.map { it.toAudioDevice(isActive = false) }

        return AudioStateSnapshot(
            routes = routes,
            inputRoute = null, // populated alongside record-config support
            outputDevices = outputDevices,
            inputDevices = inputDevices,
            microphones = readMicrophones(),
            timestampMillis = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Change triggers
    // ---------------------------------------------------------------------------------------------

    /**
     * Merge of all "something changed, recompute" signals into a single [Flow] of [Unit]. Emits
     * once on start so the first collection produces an immediate snapshot.
     */
    private fun changeTriggers(): Flow<Unit> =
        callbackFlow {
            val deviceCallback =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                        trySend(Unit)
                    }

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                        trySend(Unit)
                    }
                }

            val playbackCallback =
                object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(
                        configs: MutableList<AudioPlaybackConfiguration>?
                    ) {
                        trySend(Unit)
                    }
                }

            val batteryReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        trySend(Unit)
                    }
                }

            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
            context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )

            awaitClose {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                runCatching { context.unregisterReceiver(batteryReceiver) }
            }
        }
            .onStart { emit(Unit) }

    // ---------------------------------------------------------------------------------------------
    // Mapping: framework -> snapshot
    // ---------------------------------------------------------------------------------------------

    private fun AudioDeviceInfo.toAudioDevice(isActive: Boolean): AudioDevice {
        val resolvedAddress = address.takeIf { it.isNotBlank() }
        return AudioDevice(
            id = id,
            name = productName?.toString()?.takeIf { it.isNotBlank() }
                ?: AudioStateLabels.deviceTypeLabel(type),
            type = type,
            typeLabel = AudioStateLabels.deviceTypeLabel(type),
            isSink = isSink,
            isActive = isActive,
            address = resolvedAddress,
            sampleRates = sampleRates.toList().sorted(),
            channelCounts = channelCounts.toList().sorted(),
            channelMasks = channelMasks.toList(),
            encodings = encodings.map { AudioStateLabels.encodingLabel(it) }.distinct(),
            batteryPercent = readAccessoryBattery(type, resolvedAddress),
        )
    }

    /**
     * Battery level of the accessory backing a device, when it reports one. Only meaningful for
     * Bluetooth sinks; resolved through the injected [deviceBatteryProvider] so this library keeps
     * no hard Bluetooth dependency. Never the phone's own battery.
     */
    private fun readAccessoryBattery(type: Int, address: String?): Int? {
        val provider = deviceBatteryProvider ?: return null
        val isBtSink =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        if (!isBtSink || address == null) return null
        return runCatching { provider.batteryPercentFor(address)?.takeIf { it in 0..100 } }
            .getOrNull()
    }

    /**
     * Builds the rendered output chains. The unit of iteration is the active output THREAD (each
     * [OutputThreadInfo] from the facade), not the playback usage: every active thread renders one
     * complete chain from its own state — its own [OutputThreadInfo.sources], its own AF stage, and
     * its own sink device(s) matched by port id. Nothing is picked, so there is no path/thread
     * selection to get wrong (the design's per-thread model).
     *
     * A thread's sink device is the enumerated [AudioDevice] whose id is in the thread's
     * [OutputThreadInfo.sinkPortIds] (`AudioDeviceInfo.getId() == audio_port_handle_t`). A thread
     * whose sink port id matches no enumerated device — or a DUPLICATING / unpatched thread with no
     * sink port id at all — renders WITHOUT a resolved device (honest omission), never a guessed one.
     *
     * When the facade is absent ([threads] empty) we fall back to the phase-1 usage-based view: one
     * chain per active playback with null thread info, so the source still renders from the playback
     * config. Usage labelling is display-only and never selects a thread.
     */
    private fun buildRoutes(
        threads: List<OutputThreadInfo>,
        playbacks: List<ActivePlayback>,
        outputDevices: List<AudioDevice>,
    ): List<AudioRoute> {
        if (threads.isEmpty()) return buildFallbackRoutes(playbacks, outputDevices)

        // Display-only usage attribution: map each enumerated device id to the usage label(s) routed
        // to it, queried live. This never selects a thread — it only labels a thread that already has
        // a resolved sink. A device with no routed playback (or an omitted sink) reads "Audio".
        val usageByDeviceId = usageLabelsByDeviceId(playbacks, outputDevices)

        return threads.map { thread ->
            // The thread's own sink device(s): every enumerated device whose id the thread's patch
            // names. Multi-sink is "any" — a thread serving several ports matches several devices; we
            // render the chain against the first matched enumerated device for the device block while
            // keeping the match itself port-id-exact. No match → no device (omit identity).
            val device =
                outputDevices.firstOrNull { dev -> thread.sinkPortIds.any { it == dev.id } }
            val usageLabel = device?.let { usageByDeviceId[it.id] } ?: "Audio"
            // "Any active source resamples" — but only a meaningful boolean when there ARE sources.
            // With no observed sources, leave it null ("unknown") rather than a vacuous false, so the
            // renderer omits the Resampling row instead of asserting "No" for a thread with nothing to
            // resample. Per-source truth is always in [sources].
            val anyResampling =
                if (thread.sources.isEmpty()) null else thread.sources.any { it.resampling }
            // Truth of liveness: a chain is playing only when a stream is actually on the thread. For
            // a playback thread that is ≥1 active external source (the facade already filtered to
            // active client tracks); for an MMAP thread it is mmapActive. A standing-but-idle thread
            // (PRIMARY/telephony mixer patched to the speaker with nothing playing) is alive but NOT
            // playing — the UI then de-emphasizes it and drops its (non-existent) Source stage.
            val isPlaying = thread.sources.isNotEmpty() || thread.mmapActive == true
            AudioRoute(
                usageLabel = usageLabel,
                sources = thread.sources,
                isPlaying = isPlaying,
                // Single representative source only when the thread has exactly one — otherwise null,
                // so a renderer drawing one Source stage never implies a picked track. The per-source
                // truth is always in [sources]. The facade contributed the source, so mark it Hybrid.
                sourceFormat = thread.sources.singleOrNull()?.format,
                sourceFromFacade = thread.sources.isNotEmpty(),
                mixFormat = thread.mixFormat,
                afInternalFormat = thread.afInternalFormat,
                hardwareFormat = thread.hardwareFormat,
                outputFlags = thread.outputFlags,
                // Route-level "any source resampling"; per-source truth lives in each AudioSource.
                resampling = anyResampling,
                effectChain = thread.effectChain,
                latencyMillis = thread.latencyMillis,
                pathTypeLabel = thread.pathTypeLabel,
                hasMixerStage = thread.hasMixerStage,
                bitPerfect = thread.bitPerfect,
                activeTrackCount = thread.activeTrackCount,
                mmapActive = thread.mmapActive,
                bitPerfectReasons = thread.bitPerfectReasons,
                outputDevice = device,
                bluetoothCodec = device?.let { readBluetoothCodec(it) },
            )
        }
    }

    /**
     * Phase-1 / facade-absent fallback: one chain per active playback, usage-driven, with null
     * thread info. No thread data is available to attach, so this is the only place usage drives a
     * chain — and only because there is no thread set to enumerate. Mirrors the original behaviour.
     */
    private fun buildFallbackRoutes(
        playbacks: List<ActivePlayback>,
        outputDevices: List<AudioDevice>,
    ): List<AudioRoute> {
        if (playbacks.isEmpty()) return emptyList()
        return playbacks.map { pb ->
            val device = routedDeviceFor(pb.usage, outputDevices)
            AudioRoute(
                usageLabel = pb.usageLabel,
                sources = emptyList(),
                // The fallback builds one chain per ACTIVE playback, so it is playing by construction
                // (its source rides sourceFormat below, not the per-track sources list).
                isPlaying = true,
                // Source comes from the playback config (rate + channels, no bit depth); not from the
                // facade, so the Source stage stays framework-provenance.
                sourceFormat = pb.sourceFormat,
                sourceFromFacade = false,
                mixFormat = null,
                afInternalFormat = null,
                hardwareFormat = null,
                outputFlags = null,
                resampling = null,
                effectChain = null,
                latencyMillis = null,
                pathTypeLabel = null,
                hasMixerStage = null,
                bitPerfect = null,
                activeTrackCount = null,
                mmapActive = null,
                bitPerfectReasons = null,
                outputDevice = device,
                bluetoothCodec = device?.let { readBluetoothCodec(it) },
            )
        }
    }

    /**
     * Display-only usage labelling: device id → joined usage label(s) of the playbacks routed there.
     * Built from the live routing query so it reflects the real route; used purely to label a thread
     * that already has a resolved sink, never to select one. A device serving several usages joins
     * them ("Media · Game"); a device with no routed playback is simply absent from the map.
     */
    private fun usageLabelsByDeviceId(
        playbacks: List<ActivePlayback>,
        outputDevices: List<AudioDevice>,
    ): Map<Int, String> =
        playbacks
            .mapNotNull { pb -> routedDeviceFor(pb.usage, outputDevices)?.let { it.id to pb.usageLabel } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, labels) -> labels.distinct().joinToString(" · ") }

    /**
     * Reads the per-thread AudioFlinger snapshot via the injected [outputThreadProvider]. Empty in
     * phase 1 (no provider) or when the facade is absent, so [buildRoutes] degrades to the
     * usage-based fallback.
     */
    private fun readOutputThreadSnapshot(): List<OutputThreadInfo> {
        val provider = outputThreadProvider ?: return emptyList()
        return runCatching { provider.outputThreadSnapshot() }.getOrElse { emptyList() }
    }

    /**
     * Reads genuinely-active playbacks. Only [AudioPlaybackConfiguration.PLAYER_STATE_STARTED]
     * configs count as a stream — idle/paused/stopped players (e.g. preloaded SoundPool effects)
     * are dropped so an empty result correctly reads as "no active streams" rather than rendering
     * a hollow, format-less route. Tolerates the @SystemApi format getters being unavailable.
     */
    private fun readActivePlaybackConfigurations(): List<ActivePlayback> =
        runCatching {
            audioManager.getActivePlaybackConfigurations()
                .filter { it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED }
                .map { it.toActivePlayback() }
        }.getOrElse {
            Log.w(TAG, "getActivePlaybackConfigurations failed", it)
            emptyList()
        }

    private fun AudioPlaybackConfiguration.toActivePlayback(): ActivePlayback {
        // getSampleRate()/getChannelMask() are @SystemApi (MODIFY_AUDIO_ROUTING); guard them.
        val format =
            runCatching {
                val rate = sampleRate.takeIf { it > 0 }
                val mask = channelMask
                val channels =
                    if (mask != AudioFormat.CHANNEL_INVALID)
                        AudioFormat.channelCountFromOutChannelMask(mask).takeIf { it > 0 }
                    else null
                if (rate == null && channels == null) null
                else
                    AudioFormatSummary(
                        sampleRateHz = rate,
                        bitDepth = null, // encoding not exposed per-config; left to phase 2 mix info
                        channelCount = channels,
                        encodingLabel = null,
                    )
            }.getOrNull()

        val usage = audioAttributes.usage
        return ActivePlayback(
            usage = usage,
            usageLabel = usageLabel(usage),
            sourceFormat = format,
        )
    }

    /**
     * Resolves the device a usage is *currently* routed to, queried live from the audio policy
     * rather than read off the playback config's cached device list. The config's device ids are
     * only refreshed on player lifecycle events (STARTED / UPDATE_DEVICE_ID) and go stale during
     * gapless track advance — querying the policy on every recompute reflects the real route. The
     * result is matched back to an enumerated [AudioDevice] by type + address.
     */
    private fun routedDeviceFor(usage: Int, outputDevices: List<AudioDevice>): AudioDevice? =
        runCatching {
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            val routed = audioManager.getDevicesForAttributes(attributes).firstOrNull()
                ?: return null
            val routedAddress = routed.address.takeIf { it.isNotBlank() }
            outputDevices.firstOrNull { dev ->
                dev.type == routed.type && dev.address == routedAddress
            }
                // Builtin devices report an empty address; fall back to type-only match.
                ?: outputDevices.firstOrNull { it.type == routed.type }
        }.getOrElse {
            Log.w(TAG, "getDevicesForAttributes failed for usage $usage", it)
            null
        }

    private fun readMicrophones(): List<MicrophoneInfoSummary> =
        runCatching {
            audioManager.microphones.map { it.toSummary() }
        }.getOrElse {
            Log.w(TAG, "getMicrophones failed", it)
            emptyList()
        }

    private fun MicrophoneInfo.toSummary(): MicrophoneInfoSummary =
        MicrophoneInfoSummary(
            description = description,
            address = address.takeIf { it.isNotBlank() },
            locationLabel = AudioStateLabels.micLocationLabel(location),
            directionalityLabel = AudioStateLabels.micDirectionalityLabel(directionality),
        )

    private fun readBluetoothCodec(device: AudioDevice): BluetoothCodecSummary? {
        val provider = codecProvider ?: return null
        val isBtSink =
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        if (!isBtSink) return null
        return runCatching { provider.activeCodecFor(device) }.getOrNull()
    }

    private fun usageLabel(usage: Int): String =
        when (usage) {
            android.media.AudioAttributes.USAGE_MEDIA -> "Media"
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "Call"
            android.media.AudioAttributes.USAGE_ALARM -> "Alarm"
            android.media.AudioAttributes.USAGE_NOTIFICATION -> "Notification"
            android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "Ringtone"
            android.media.AudioAttributes.USAGE_GAME -> "Game"
            android.media.AudioAttributes.USAGE_ASSISTANT -> "Assistant"
            else -> "Audio"
        }

    /** Internal carrier for a mapped active playback before route assembly. */
    private data class ActivePlayback(
        val usage: Int,
        val usageLabel: String,
        val sourceFormat: AudioFormatSummary?,
    )

    /**
     * Optional supplier of the active Bluetooth codec for a sink. Implemented by the host using its
     * Bluetooth stack (e.g. LocalBluetoothManager + BluetoothA2dp.getCodecStatus) so this library
     * keeps no hard Bluetooth dependency.
     */
    fun interface BluetoothCodecProvider {
        fun activeCodecFor(device: AudioDevice): BluetoothCodecSummary?
    }

    /**
     * Optional supplier of an accessory's battery level (0–100) for a Bluetooth device address,
     * implemented by the host using its Bluetooth stack. Returns null when the device reports none.
     * Never the phone's battery. Kept as a hook so this library has no Bluetooth dependency.
     */
    fun interface DeviceBatteryProvider {
        fun batteryPercentFor(address: String): Int?
    }

    /**
     * Optional supplier of AudioFlinger output-thread internals, filled in phase 2 by the @hide
     * audioserver facade. Returns an empty list in phase 1 / when the facade is absent.
     *
     * Per-thread, not per-device: the snapshot lists *every* active output thread, each described
     * from its own state and carrying its own [OutputThreadInfo.sinkPortIds]. The repository renders
     * one chain per thread and matches an enumerated [AudioDevice] to a thread by port id — there is
     * no device→thread lookup and no first()/primary selection.
     */
    fun interface OutputThreadInfoProvider {
        /** Every active output thread for this snapshot, each tagged with its own sink port id(s). */
        fun outputThreadSnapshot(): List<OutputThreadInfo>
    }

    /** Phase-2 payload: AudioFlinger internals for one active output thread (one rendered chain). */
    data class OutputThreadInfo(
        /**
         * Every active external client track feeding this thread, each with its real source format
         * and per-track resampling. The thread's "source" is all of these, never a picked one. Read
         * inside the facade — the only place the real per-track bit depth is observable. Empty when
         * the facade found no external track. Order is pointer-address (not signal order); treated
         * as unordered.
         */
        val sources: List<AudioSource>,
        /**
         * The AudioFlinger stage's *output* (sink) format leaving the mixer to the HAL — read from
         * the mix thread's mFormat. Also the rate/channels carrier for the AF stage. The mixer's
         * internal float accumulation format is carried separately in [afInternalFormat]; the two are
         * kept distinct so the AF stage can render the input → internal → output bit-depth round-trip
         * without dropping either end.
         */
        val mixFormat: AudioFormatSummary?,
        /**
         * The mixer's internal accumulation format (mMixerBufferFormat, typically 32-bit PCM float) —
         * the middle of the AF bit-depth round-trip. Null when the facade reported none
         * (AUDIO_FORMAT_INVALID / older facade); the UI then omits the round-trip line.
         */
        val afInternalFormat: AudioFormatSummary?,
        /** The actual HAL/DAC-side format the stream leaves through; drives the resample arrow. */
        val hardwareFormat: AudioFormatSummary?,
        val outputFlags: List<String>,
        val effectChain: List<AudioEffectSummary>,
        val latencyMillis: Int?,
        /** Neutral path-type label (e.g. "Mixed", "Direct", "Offload"). */
        val pathTypeLabel: String?,
        /** False for bypass paths (direct/offload/mmap) that have no mixer stage. */
        val hasMixerStage: Boolean,
        /**
         * Whether the output is delivering bit-exact samples: a BIT_PERFECT thread with exactly one
         * active bit-perfect track and all other active tracks muted. Computed in the facade.
         */
        val bitPerfect: Boolean,
        /** Number of active, audible (non-muted) tracks on the path. */
        val activeTrackCount: Int,
        /**
         * Discrete reasons the verdict is No, in signal order (e.g. "Mixed path (float re-mix)",
         * "Mixing 2 active tracks", "Resampling 48 → 44 kHz"). Empty when [bitPerfect] is true.
         */
        val bitPerfectReasons: List<String>,
        /**
         * The `audio_port_handle_t` of each of this thread's sinks (== `AudioDeviceInfo.getId()`).
         * The authoritative device-match key: an enumerated [AudioDevice] belongs to this thread when
         * its id is in this list. Empty when the thread has no patch yet, its sinks are unresolvable,
         * or it is a DUPLICATING thread (no single sink) — the chain then renders without a device.
         */
        val sinkPortIds: List<Int>,
        /**
         * MMAP liveness tri-state. `null` when this is NOT an MMAP thread (a mixer/direct/offload
         * path — the UI then uses the mixer track-count signal, never the MMAP "active / not"
         * wording). For an MMAP thread it is the boolean-ish active signal (`true` when the thread
         * carries a track), since the native MMAP path lacks the playback active-set check and so
         * cannot report a precise mixer track count. Must NOT be `false` for a non-MMAP path — that
         * would make the UI render "Not active" on a live mixer chain.
         */
        val mmapActive: Boolean?,
    )

    private companion object {
        const val TAG = "AudioStateRepository"
    }
}
