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

package com.android.settingslib.audiostate.compose

import android.media.AudioDeviceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headset
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.audiostate.AudioDevice
import com.android.settingslib.audiostate.AudioFormatSummary
import com.android.settingslib.audiostate.AudioRoute
import com.android.settingslib.audiostate.AudioStateLabels
import com.android.settingslib.audiostate.AudioStateSnapshot

/**
 * Stateless rendering of an [AudioStateSnapshot] as a vertical signal-flow chain (Source → Output →
 * Output device), styled after a hi-fi player's audio-path readout. Pure function of [snapshot];
 * no system reads. Shared verbatim by the Quick Settings dialog (compact) and the Settings page
 * (full), so the two surfaces stay visually identical.
 *
 * Phase-2 fields (mix format, flags, resampling, effects) render only when the snapshot carries
 * them, so the same component lights up automatically once the AudioFlinger accessors are wired.
 *
 * @param full when true (the Settings page), also render the exhaustive output/input/microphone
 *   capability matrix beneath the active chain. When false (the compact dialog), render just the
 *   active output chain, or a "no active streams" state (the dialog hosts the "More details"
 *   button that opens this same page in full).
 */
@Composable
fun AudioStateTree(
    snapshot: AudioStateSnapshot,
    modifier: Modifier = Modifier,
    full: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val primary = snapshot.routes.firstOrNull()

        // Assemble the chain stages so the icon rail can draw one continuous line through all of
        // them (Source → Output → Output device), with the connector arrow centered. The output
        // device carries its accessory battery as a chip on its title row, not as a separate stage.
        val stages = buildList {
            if (primary != null) {
                add(
                    Stage(Icons.Outlined.MusicNote, "Source", primary.usageLabel,
                        formatLines(primary.sourceFormat))
                )
                if (primary.hasOutputInfo()) {
                    add(Stage(Icons.Outlined.Tune, "Output", null, outputLines(primary)))
                }
                primary.outputDevice?.let { dev ->
                    add(Stage(iconForDevice(dev.type), "Output device", dev.name,
                        outputDeviceLines(dev, primary), batteryPercent = dev.batteryPercent))
                }
            }
        }

        if (primary == null) {
            EmptyState()
        }

        stages.forEachIndexed { index, stage ->
            ChainStage(stage = stage, isLast = index == stages.lastIndex)
        }

        if (full) {
            DeviceSection("Output devices", snapshot.outputDevices)
            DeviceSection("Input devices", snapshot.inputDevices)
            MicrophoneSection(snapshot)
        }
    }
}

/** One stage's data, assembled before rendering so the rail knows which icon is last. */
private data class Stage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    val lines: List<ReadoutLine>,
    val batteryPercent: Int? = null,
)

// -------------------------------------------------------------------------------------------------
// Chain
// -------------------------------------------------------------------------------------------------

/**
 * One stage of the signal-flow chain. The icon rail draws a continuous vertical line down through
 * every stage (with a centered arrow) so the whole chain reads as one connected flow; the line is
 * omitted only on the last stage. The content column holds a bold title, an optional emphasized
 * subtitle, and the grouped technical readout.
 */
@Composable
private fun ChainStage(stage: Stage, isLast: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        // Icon rail. The stage icon sits at the top; below it the connector is drawn as two
        // line segments with the arrow in the gap between them, so the arrow genuinely interrupts
        // the line (a real geometric break — no color masking that can mismatch the background).
        Column(
            modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = stage.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            if (!isLast) {
                ConnectorSegment(Modifier.weight(1f))
                Spacer(Modifier.height(2.dp))
                Icon(
                    Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.height(2.dp))
                ConnectorSegment(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = if (isLast) 0.dp else 20.dp)) {
            Text(
                text = stage.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            stage.subtitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    stage.batteryPercent?.let { pct ->
                        Spacer(Modifier.width(8.dp))
                        BatteryChip(pct)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            stage.lines.forEach { ReadoutRow(it) }
        }
    }
}

/** One vertical line segment of the connector rail. [modifier] supplies the weighted height. */
@Composable
private fun ConnectorSegment(modifier: Modifier) {
    Box(
        modifier
            .width(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun ReadoutRow(line: ReadoutLine) {
    when (line) {
        is ReadoutLine.Mono ->
            Text(
                text = line.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        is ReadoutLine.Pair ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = line.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
            }
    }
}

@Composable
private fun EmptyState() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = "No active streams",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Nothing is playing audio right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Device / input / microphone matrix (full mode)
// -------------------------------------------------------------------------------------------------

@Composable
private fun DeviceSection(title: String, devices: List<AudioDevice>) {
    if (devices.isEmpty()) return
    SectionHeader(title)
    devices.forEachIndexed { index, device ->
        DeviceCard(device)
        if (index != devices.lastIndex) Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DeviceCard(device: AudioDevice) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(
            imageVector = iconForDevice(device.type),
            contentDescription = null,
            tint =
                if (device.isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (device.isActive) {
                    Spacer(Modifier.width(8.dp))
                    ActiveBadge()
                }
                device.batteryPercent?.let {
                    Spacer(Modifier.width(8.dp))
                    BatteryChip(it)
                }
            }
            Spacer(Modifier.height(2.dp))
            deviceLines(device).forEach { ReadoutRow(it) }
        }
    }
}

@Composable
private fun MicrophoneSection(snapshot: AudioStateSnapshot) {
    if (snapshot.microphones.isEmpty()) return
    SectionHeader("Microphones")
    snapshot.microphones.forEachIndexed { index, mic ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = mic.description,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                buildList {
                    mic.locationLabel?.let { add(ReadoutLine.Pair("Location", it)) }
                    mic.directionalityLabel?.let { add(ReadoutLine.Pair("Directionality", it)) }
                    mic.address?.let { add(ReadoutLine.Pair("Address", it)) }
                }.forEach { ReadoutRow(it) }
            }
        }
        if (index != snapshot.microphones.lastIndex) Spacer(Modifier.height(6.dp))
    }
}

// -------------------------------------------------------------------------------------------------
// Small presentational primitives
// -------------------------------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.5.sp,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ActiveBadge() {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "ACTIVE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Subtle accessory-battery chip: a small battery glyph + percentage. */
@Composable
private fun BatteryChip(percent: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = batteryIconFor(percent),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Readout model + builders (pure)
// -------------------------------------------------------------------------------------------------

/** A line in a stage's readout: either a standalone emphasized value, or a label/value pair. */
private sealed interface ReadoutLine {
    data class Mono(val value: String) : ReadoutLine
    data class Pair(val label: String, val value: String) : ReadoutLine
}

private fun AudioRoute.hasOutputInfo(): Boolean =
    mixFormat != null || outputFlags != null || effectChain != null || latencyMillis != null

private fun formatLines(format: AudioFormatSummary?): List<ReadoutLine> {
    if (format == null) return listOf(ReadoutLine.Mono("Format unavailable"))
    val parts = buildList {
        format.bitDepth?.let { add("$it bit") }
        format.sampleRateHz?.let { add(AudioStateLabels.formatRateKHz(it)) }
        format.channelCount?.let { add(channelLabel(it)) }
        format.encodingLabel?.let { add(it) }
    }
    return if (parts.isEmpty()) listOf(ReadoutLine.Mono("—"))
    else listOf(ReadoutLine.Mono(parts.joinToString("  ·  ")))
}

private fun outputLines(route: AudioRoute): List<ReadoutLine> = buildList {
    route.mixFormat?.let { mix ->
        val src = route.sourceFormat?.sampleRateHz
        val mixRate = mix.sampleRateHz
        val rate =
            if (src != null && mixRate != null && src != mixRate)
                "${AudioStateLabels.formatRateKHz(src)} → ${AudioStateLabels.formatRateKHz(mixRate)}"
            else mixRate?.let { AudioStateLabels.formatRateKHz(it) }
        val parts = buildList {
            mix.bitDepth?.let { add("$it bit") }
            rate?.let { add(it) }
            mix.channelCount?.let { add(channelLabel(it)) }
        }
        if (parts.isNotEmpty()) add(ReadoutLine.Mono(parts.joinToString("  ·  ")))
    }
    route.resampling?.let { add(ReadoutLine.Pair("Resampling", if (it) "Yes" else "No")) }
    route.outputFlags?.takeIf { it.isNotEmpty() }?.let {
        add(ReadoutLine.Pair("Flags", it.joinToString(" ")))
    }
    route.latencyMillis?.let { add(ReadoutLine.Pair("Latency", "${it} ms")) }
    route.effectChain?.takeIf { it.isNotEmpty() }?.let { effects ->
        add(ReadoutLine.Pair("Effects", effects.joinToString(", ") {
            it.name + if (!it.enabled) " (off)" else ""
        }))
    }
}

private fun outputDeviceLines(device: AudioDevice, route: AudioRoute): List<ReadoutLine> = buildList {
    add(ReadoutLine.Pair("Interface", device.typeLabel))
    device.address?.let { add(ReadoutLine.Pair("Address", it)) }
    route.bluetoothCodec?.let { c ->
        val parts = buildList {
            add(c.codecName)
            c.bitDepth?.let { add("$it bit") }
            c.sampleRateHz?.let { add(AudioStateLabels.formatRateKHz(it)) }
        }
        add(ReadoutLine.Pair("Codec", parts.joinToString(" ")))
    }
}

private fun deviceLines(device: AudioDevice): List<ReadoutLine> = buildList {
    add(ReadoutLine.Pair("Interface", device.typeLabel))
    device.address?.let { add(ReadoutLine.Pair("Address", it)) }
    if (device.sampleRates.isNotEmpty()) {
        add(ReadoutLine.Pair("Sample rates",
            device.sampleRates.joinToString(", ") { AudioStateLabels.formatRateKHz(it) }))
    }
    if (device.channelCounts.isNotEmpty()) {
        add(ReadoutLine.Pair("Channels",
            device.channelCounts.joinToString(", ") { channelLabel(it) }))
    }
    if (device.encodings.isNotEmpty()) {
        add(ReadoutLine.Pair("Formats", device.encodings.joinToString(", ")))
    }
}

private fun batteryIconFor(percent: Int): ImageVector =
    when {
        percent >= 90 -> Icons.Outlined.BatteryFull
        percent >= 20 -> Icons.Outlined.BatteryStd
        else -> Icons.Outlined.BatteryAlert
    }

private fun channelLabel(count: Int): String =
    when (count) {
        1 -> "Mono"
        2 -> "Stereo"
        else -> "$count channels"
    }

private fun iconForDevice(type: Int): ImageVector =
    when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> Icons.Outlined.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> Icons.Outlined.Bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> Icons.Outlined.Headset
        else -> Icons.Outlined.Speaker
    }

/** Width of the icon/connector rail; the connector line is centered within it. */
private val RAIL_WIDTH = 24.dp
