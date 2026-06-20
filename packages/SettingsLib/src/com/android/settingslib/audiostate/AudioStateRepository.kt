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
 * @param outputThreadProvider optional hook supplying AudioFlinger output-thread internals
 *   (mix format, flags, resampling, effects). Null in phase 1; wired to the @hide accessors later.
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

        // Enumerate devices first (without active state), resolve routes against them via live
        // routing queries, then mark the resolved routed devices active. This keeps the ACTIVE
        // marker consistent with what the chain shows and avoids the stale event-cached device ids.
        val baseOutputDevices = outputs.map { it.toAudioDevice(isActive = false) }
        val routes = buildRoutes(activePlaybacks, baseOutputDevices)

        val activeOutputDeviceIds = routes.mapNotNull { it.outputDevice?.id }.toSet()
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

    private fun buildRoutes(
        playbacks: List<ActivePlayback>,
        outputDevices: List<AudioDevice>,
    ): List<AudioRoute> {
        if (playbacks.isEmpty()) return emptyList()
        return playbacks.map { pb ->
            val device = routedDeviceFor(pb.usage, outputDevices)
            AudioRoute(
                usageLabel = pb.usageLabel,
                sourceFormat = pb.sourceFormat,
                mixFormat = null, // phase 2
                outputFlags = null, // phase 2
                resampling = null, // phase 2
                effectChain = null, // phase 2
                latencyMillis = null, // phase 2
                outputDevice = device,
                bluetoothCodec = device?.let { readBluetoothCodec(it) },
            )
        }
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
     * AudioManager accessor. Returns null in phase 1.
     */
    fun interface OutputThreadInfoProvider {
        /** Returns (mixFormat, flags, resampling, effects, latencyMs) for the given output device. */
        fun outputThreadInfoFor(device: AudioDevice): OutputThreadInfo?
    }

    /** Phase-2 payload: AudioFlinger output-thread internals for one routed output. */
    data class OutputThreadInfo(
        val mixFormat: AudioFormatSummary?,
        val outputFlags: List<String>,
        val resampling: Boolean,
        val effectChain: List<AudioEffectSummary>,
        val latencyMillis: Int?,
    )

    private companion object {
        const val TAG = "AudioStateRepository"
    }
}
