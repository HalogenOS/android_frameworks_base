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
import android.media.AudioRecordingConfiguration
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
 * @param outputThreadProvider optional hook supplying the per-thread AudioFlinger snapshot (sources,
 *   mix format, flags, effects, sink port ids). Null in phase 1; wired to the @hide facade later.
 */
class AudioStateRepository(
    private val context: Context,
    private val handler: Handler,
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
        // falsely light up an idle speaker. Instead resolve the live route(s) for each active playback
        // (the same policy query usage labelling uses): no active playback → nothing routed → no
        // ACTIVE pill. A usage that resolves to a duplicated path lights up ALL of its devices.
        val activeOutputDeviceIds =
            activePlaybacks.flatMap { routedDevicesFor(it.usage, baseOutputDevices) }
                .map { it.id }
                .toSet()
        val outputDevices =
            baseOutputDevices.map { it.copy(isActive = activeOutputDeviceIds.contains(it.id)) }

        // Capture side: read the live recording configs (public API), so an input device is ACTIVE
        // only when it is actually backing an in-progress recording — never hard-coded false.
        val recordingConfigs = readActiveRecordingConfigurations()
        val baseInputDevices = inputs.map { it.toAudioDevice(isActive = false) }
        val activeInputDeviceIds =
            recordingConfigs.mapNotNull { it.deviceId }.toSet()
        val inputDevices =
            baseInputDevices.map { it.copy(isActive = activeInputDeviceIds.contains(it.id)) }

        return AudioStateSnapshot(
            routes = routes,
            // The capture/input route, if anything is recording. Built entirely from the public
            // recording-config API (device + device recording format + capture source) — the
            // AudioFlinger facade excludes capture, so nothing here comes from it.
            inputRoute = buildInputRoute(recordingConfigs, baseInputDevices),
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

            // Capture-side trigger: a recording starting/stopping/re-routing must recompute the
            // snapshot so the input route and input-device ACTIVE state stay live.
            val recordingCallback =
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(
                        configs: MutableList<AudioRecordingConfiguration>?
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
            audioManager.registerAudioRecordingCallback(recordingCallback, handler)
            context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )

            awaitClose {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioRecordingCallback(recordingCallback)
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
        // Battery membership includes SCO: a classic headset connected over SCO still reports a
        // battery level even though it carries no A2DP codec.
        if (type !in BATTERY_BT_SINK_TYPES || address == null) return null
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

        // The live-routed device(s) for the active playbacks, resolved on EVERY recompute from the
        // audio policy (getDevicesForAttributes) rather than any event-cached device id. This is the
        // auto-advance safety net: during a gapless track advance a thread's patch (sinkPortIds) can
        // go momentarily empty, which would otherwise drop the output device from the chain until the
        // next play. The live query reflects the real route through that gap. We use it only when it is
        // UNAMBIGUOUS (all active playbacks route to a single device) so the fallback resolves an exact
        // device, never a guess; an ambiguous multi-device moment falls through to an honest omission.
        // singleOrNull() here is the wrong-device-bleed guard (verified safe): the fallback is non-null
        // ONLY when every active playback resolves to exactly ONE device — two devices make it null
        // (an honest omission), so it can never assign a playing thread to the wrong sink.
        val liveRoutedFallback =
            playbacks
                .flatMap { routedDevicesFor(it.usage, outputDevices) }
                .distinctBy { it.id }
                .singleOrNull()

        return threads.map { thread ->
            // Truth of liveness: a chain is playing only when a stream is actually on the thread. For
            // a playback thread that is ≥1 active external source (the facade already filtered to
            // active client tracks); for an MMAP thread it is mmapActive. A standing-but-idle thread
            // (PRIMARY/telephony mixer patched to the speaker with nothing playing) is alive but NOT
            // playing — the UI then de-emphasizes it and drops its (non-existent) Source stage.
            val isPlaying = thread.sources.isNotEmpty() || thread.mmapActive == true
            // The thread's own sink device(s): EVERY enumerated device whose id the thread's patch
            // names. A non-duplicating thread can carry a multi-sink HAL patch (mPatch.num_sinks > 1),
            // so the truth is the whole matched set — we never first()/pick one. When the patch is
            // momentarily empty (gapless advance) the port-id match yields nothing — fall back to the
            // single live-routed device, but only for a PLAYING thread, so the chain keeps its sink
            // identity instead of flickering to "Not resolved". No port-id match and (idle, or no live
            // route) → no device (honest omission, empty list).
            val matchedDevices =
                outputDevices.filter { dev -> thread.sinkPortIds.any { it == dev.id } }
            val devices =
                matchedDevices.ifEmpty {
                    liveRoutedFallback?.takeIf { isPlaying }?.let { listOf(it) }.orEmpty()
                }
            // Usage label is display-only; key it off the first matched device (the usage map is keyed
            // by device id and every sink of a duplicated path carries the same routed usage).
            val usageLabel = devices.firstOrNull()?.let { usageByDeviceId[it.id] } ?: "Audio"
            // "Any active source resamples" — but only a meaningful boolean when there ARE sources.
            // With no observed sources, leave it null ("unknown") rather than a vacuous false, so the
            // renderer omits the Resampling row instead of asserting "No" for a thread with nothing to
            // resample. Per-source truth is always in [sources].
            val anyResampling =
                if (thread.sources.isEmpty()) null else thread.sources.any { it.resampling }
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
                // The DISTINCT source rates feeding the AF stage, gathered from every source (not a
                // picked one). Keeps the AF-stage rate readout truthful for a multi-source thread,
                // where sourceFormat is intentionally null. Preserves order of first appearance.
                sourceSampleRatesHz =
                    thread.sources.mapNotNull { it.format.sampleRateHz }.distinct(),
                effectChain = thread.effectChain,
                latencyMillis = thread.latencyMillis,
                // Structural path-type int (renderer branches on this); label is display-only.
                pathType = thread.pathType,
                pathTypeLabel = thread.pathTypeLabel,
                hasMixerStage = thread.hasMixerStage,
                bitPerfect = thread.bitPerfect,
                activeTrackCount = thread.activeTrackCount,
                mmapActive = thread.mmapActive,
                bitPerfectReasons = thread.bitPerfectReasons,
                // The whole matched sink set (one Output-device stage per entry); outputDevice
                // derives its single-device convenience from the head of this list.
                outputDevices = devices,
                // Whether the thread is patched to ANY sink at all. False = no sink (empty device
                // types) → the renderer says "No output device" (truthfully none), not "could not be
                // resolved" (which would falsely imply a device exists).
                hasSinkPortId = thread.sinkPortIds.isNotEmpty(),
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
            // The live route can be a duplicated path (≥2 devices); carry the whole set, not a pick.
            val devices = routedDevicesFor(pb.usage, outputDevices)
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
                pathType = null,
                pathTypeLabel = null,
                hasMixerStage = null,
                bitPerfect = null,
                activeTrackCount = null,
                mmapActive = null,
                bitPerfectReasons = null,
                outputDevices = devices,
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
            // A playback routed to a duplicated path contributes its label to EVERY device it routes
            // to (flatMap over the full device list), not just the first.
            .flatMap { pb -> routedDevicesFor(pb.usage, outputDevices).map { it.id to pb.usageLabel } }
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
     * result is an [AudioDeviceAttributes] (type + address only — it carries NO port id / stable
     * handle, unlike the per-thread sink-port-id used for the patched-device match), so it is matched
     * back to an enumerated [AudioDevice] by type + address, with a type-only fallback for builtin
     * devices that report an empty address. This is the only key the policy query exposes here.
     */
    private fun routedDevicesFor(usage: Int, outputDevices: List<AudioDevice>): List<AudioDevice> =
        runCatching {
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            // getDevicesForAttributes() returns EVERY device the usage is routed to — more than one
            // for a duplicated path. Resolve each to an enumerated device (never first()-pick), so a
            // duplicated route lights up / labels all of its devices truthfully.
            audioManager.getDevicesForAttributes(attributes).mapNotNull { routed ->
                val routedAddress = routed.address.takeIf { it.isNotBlank() }
                outputDevices.firstOrNull { dev ->
                    dev.type == routed.type && dev.address == routedAddress
                }
                    // Builtin devices report an empty address; fall back to type-only match.
                    ?: outputDevices.firstOrNull { it.type == routed.type }
            }.distinctBy { it.id }
        }.getOrElse {
            Log.w(TAG, "getDevicesForAttributes failed for usage $usage", it)
            emptyList()
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

    /**
     * Reads the live recording configurations (public [AudioManager.getActiveRecordingConfigurations]).
     * Each is mapped to an [ActiveRecording] carrying the real capture device, the DEVICE recording
     * format, and the capture source — all real reads, none from the AudioFlinger facade (which
     * excludes capture). Empty (and a degrade to "nothing recording") on any failure.
     */
    private fun readActiveRecordingConfigurations(): List<ActiveRecording> =
        runCatching {
            audioManager.activeRecordingConfigurations.map { it.toActiveRecording() }
        }.getOrElse {
            Log.w(TAG, "getActiveRecordingConfigurations failed", it)
            emptyList()
        }

    private fun AudioRecordingConfiguration.toActiveRecording(): ActiveRecording =
        ActiveRecording(
            // getClientAudioSource() is the MediaRecorder.AudioSource being captured — a real read.
            sourceLabel = AudioStateLabels.captureSourceLabel(clientAudioSource),
            // getFormat() is the DEVICE recording format (the format audio is actually captured at on
            // this device). Its encoding is the Java AudioFormat.ENCODING_* space, so it is mapped with
            // encodingLabel()/bitDepthForEncoding(), NOT the native audio_format_t mappers.
            captureFormat = format.toCaptureFormatSummary(),
            // getAudioDevice() is the real input device, or null when not retrievable. Matched to an
            // enumerated input AudioDevice by stable id so we reuse its full capability matrix.
            deviceId = runCatching { audioDevice?.id }.getOrNull(),
        )

    /** Maps a capture [AudioFormat] (Java ENCODING_* space) to a summary, or null when it carries no
     *  usable rate/depth/channels. */
    private fun AudioFormat.toCaptureFormatSummary(): AudioFormatSummary? {
        val rate = sampleRate.takeIf { it > 0 }
        val channels = channelCount.takeIf { it > 0 }
        val enc = encoding
        val depth = AudioStateLabels.bitDepthForEncoding(enc)
        val label = AudioStateLabels.encodingLabel(enc)
        if (rate == null && channels == null && depth == null) return null
        return AudioFormatSummary(
            sampleRateHz = rate,
            bitDepth = depth,
            channelCount = channels,
            encodingLabel = label,
            // Float-ness read from the real Java ENCODING_* constant (same invariant as the output-side
            // summaries), so a PCM_FLOAT capture format carries a truthful isFloat, not a defaulted false.
            isFloat = AudioStateLabels.isFloatEncoding(enc),
        )
    }

    /**
     * Builds the single active [AudioInputRoute] from the live recording configs, or null when nothing
     * is recording. When several recordings are in progress the route is honestly the FIRST config's
     * device/format/source (the model carries one input route; the input-device section already marks
     * EVERY actively-recording device ACTIVE, so no device is hidden). Nothing is fabricated: a config
     * with no resolvable device renders the route with a null device, never a guessed one.
     */
    private fun buildInputRoute(
        recordings: List<ActiveRecording>,
        inputDevices: List<AudioDevice>,
    ): AudioInputRoute? {
        val recording = recordings.firstOrNull() ?: return null
        val device = recording.deviceId?.let { id -> inputDevices.firstOrNull { it.id == id } }
        return AudioInputRoute(
            usageLabel = recording.sourceLabel,
            captureFormat = recording.captureFormat,
            inputDevice = device,
        )
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

    /** Internal carrier for a mapped active recording before input-route assembly. */
    private data class ActiveRecording(
        val sourceLabel: String,
        val captureFormat: AudioFormatSummary?,
        val deviceId: Int?,
    )

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
        /** Raw structural path-type int (AudioPathInfo.pathType); the renderer branches on this. Null
         *  when unknown. The label below is the display rendering of the same int. */
        val pathType: Int?,
        /** Neutral path-type label (e.g. "Mixed", "Direct", "Offload"). Display only. */
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

        /**
         * Bluetooth SINK device types that can back an accessory BATTERY reading. Includes SCO because
         * a classic headset on SCO still reports a battery level. This is the battery membership set;
         * it deliberately differs from a (now-removed) A2DP/BLE-only codec set — the SCO inclusion is
         * the explicit difference, kept as ONE shared predicate so the two cannot silently drift.
         */
        val BATTERY_BT_SINK_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
            )
    }
}
