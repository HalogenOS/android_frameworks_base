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
import com.android.settingslib.audiostate.AudioSource
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
        // snapshot.routes is per-active-thread, not per app/usage: each entry is a complete,
        // independent chain described from one thread's own state. Render every one of them stacked
        // (the multi-output "case B": N active threads → N chains). Nothing here is "primary" — we
        // never render only routes.first().
        val routes = snapshot.routes

        if (routes.isEmpty()) {
            EmptyState()
        }

        routes.forEachIndexed { routeIndex, route ->
            // A faint divider separates one thread's chain from the next so stacked chains read as
            // distinct paths rather than one long flow. The first chain needs no divider.
            if (routeIndex != 0) ChainDivider()
            val stages = chainStages(route)
            stages.forEachIndexed { index, stage ->
                ChainStage(stage = stage, isLast = index == stages.lastIndex)
            }
        }

        if (full) {
            DeviceSection("Output devices", snapshot.outputDevices)
            DeviceSection("Input devices", snapshot.inputDevices)
            MicrophoneSection(snapshot)
        }
    }
}

/**
 * Assembles one active thread's chain top-to-bottom: Source(s) → AudioFlinger mixer (mixer/bit-
 * perfect paths only) → each active effect as its own ordered stage → Output device. The connector
 * arrow below the Source carries a path pill ("Mixed" / "Direct" / "MMAP · AAudio") showing how the
 * stream leaves. Provenance pills mark each stage's data source (framework vs the audioserver
 * facade). The output device carries its accessory battery as a chip on its title row.
 *
 * A thread can mix several client tracks, so the Source stage lists *all* of [AudioRoute.sources]
 * (unordered — the native side reads them in pointer-address order, which is not a signal order; see
 * the design doc's "Track ordering" gap). A thread with no resolved sink device (unmatched port id,
 * unpatched thread, or a DUPLICATING thread whose true sink is not determinable) renders an honest
 * terminal stage with no device identity instead of guessing one.
 */
private fun chainStages(route: AudioRoute): List<Stage> {
    val stages = buildList { addChainStages(route) }
    // An alive-but-idle chain (a standing thread with no stream flowing) is real but carries no
    // audio: de-emphasize every stage and label its first stage "Idle" in words. The Source stage is
    // already omitted below when the chain is not playing, so the first stage here is the AudioFlinger
    // (or device) stage — exactly where the idle status belongs.
    if (route.isPlaying || stages.isEmpty()) return stages
    return stages.mapIndexed { index, stage ->
        stage.copy(idle = true, statusChip = if (index == 0) "Idle" else stage.statusChip)
    }
}

private fun MutableList<Stage>.addChainStages(route: AudioRoute) {
    // The Source stage exists only when a stream is actually flowing. A standing-but-idle thread has
    // no source, so showing a "Source / Not active" stage would imply a flow that is not happening —
    // we omit it entirely and let the chain begin at the AudioFlinger stage.
    if (route.isPlaying) {
        add(
            Stage(
                icon = Icons.Outlined.MusicNote,
                title = "Source",
                subtitle = route.usageLabel,
                // List every active source feeding this thread's mixer, not a single picked one. When
                // the facade gave us no per-track source, fall back to the single display format.
                lines = sourceLines(route),
                // Rate/channels come from AudioManager; the bit depth/format come from the audioserver
                // facade when it contributed the source(s) — Hybrid then, framework-only when the facade
                // gave us no source.
                provenance =
                    if (route.sourceFromFacade == true) Provenance.HYBRID
                    else Provenance.FRAMEWORK,
                // The path pill rides the arrow leaving the source.
                pathPill = pathPill(route),
            )
        )
    }
    // The AudioFlinger stage renders for mixer-bearing paths and for a bit-perfect path (which has
    // no mixer stage but still carries the verdict, whether Yes or No). A pure bypass path
    // (direct/offload/mmap) with nothing to show skips straight to the device, explained by the path
    // pill. The subtitle reads "Bit-perfect" when the mixer is bypassed. bitPerfect != null catches
    // both Yes and failing-No cases for a BIT_PERFECT thread (hasMixerStage=false but verdict known).
    if ((route.hasMixerStage != false || route.bitPerfect != null) && route.hasOutputInfo()) {
        add(
            Stage(
                icon = Icons.Outlined.Tune,
                title = "AudioFlinger",
                subtitle = if (route.hasMixerStage == false) "Bit-perfect" else "Mixer",
                lines = mixerLines(route),
                provenance = Provenance.AUDIOSERVER,
            )
        )
    }
    // Each active output effect is its own stage, in signal-flow order.
    route.effectChain?.forEach { effect ->
        add(
            Stage(
                icon = Icons.Outlined.GraphicEq,
                title = effect.name,
                subtitle = if (effect.enabled) null else "Disabled",
                lines = emptyList(),
                provenance = Provenance.AUDIOSERVER,
            )
        )
    }
    val dev = route.outputDevice
    if (dev != null) {
        add(
            Stage(
                icon = iconForDevice(dev.type),
                title = "Output device",
                subtitle = dev.name,
                lines = outputDeviceLines(dev, route),
                // Device identity/capabilities come from AudioManager; the hardware (DAC) format
                // comes from the audioserver facade — Hybrid when both are present, framework-only
                // when the facade gave us no hardware format.
                provenance =
                    if (route.hardwareFormat != null) Provenance.HYBRID
                    else Provenance.FRAMEWORK,
                batteryPercent = dev.batteryPercent,
            )
        )
    } else {
        // No resolved sink device: the thread's sink port id matched no enumerated device, the
        // thread is unpatched, or it is a DUPLICATING thread whose real sink (the union of its
        // downstream threads' devices) is not determinable with the current accessors. These three
        // causes are indistinguishable from the snapshot, so we must NOT claim a specific device or
        // even claim "Duplicated output" (which would assert duplication we cannot confirm). Render
        // an honest unresolved terminal instead of guessing.
        add(
            Stage(
                icon = Icons.Outlined.Speaker,
                title = "Output device",
                subtitle = "Not resolved",
                lines = listOf(ReadoutLine.Mono("Output device could not be identified")),
                provenance = Provenance.AUDIOSERVER,
            )
        )
    }
}

/** Where a stage's data is sourced from; rendered as a small provenance pill on the stage title. */
private enum class Provenance(val label: String) {
    FRAMEWORK("AudioManager"),
    AUDIOSERVER("audioserver"),
    // A stage whose data is drawn from both the audio framework and the audioserver facade
    // (e.g. the output device: identity/capabilities from AudioManager, hardware format from
    // audioserver).
    HYBRID("Hybrid"),
}

/** One stage's data, assembled before rendering so the rail knows which icon is last. */
private data class Stage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    val lines: List<ReadoutLine>,
    val provenance: Provenance,
    val batteryPercent: Int? = null,
    /** Label for the path pill drawn on the connector arrow *below* this stage (e.g. "Mixed",
     *  "Direct", "MMAP · AAudio"); null draws a plain arrow. */
    val pathPill: String? = null,
    /** True when this stage belongs to an alive-but-idle chain (a standing thread with no stream
     *  flowing). Idle stages render de-emphasized (grayed) so the chain reads as inactive. */
    val idle: Boolean = false,
    /** When set, a small neutral chip rendered on the stage's title row (e.g. "Idle") — used to
     *  label an idle chain's first stage in words, not color alone. */
    val statusChip: String? = null,
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
                // Idle chain → grayed icon; a flowing chain keeps the accent color.
                tint =
                    if (stage.idle) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stage.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // Idle chain → grayed title so the inactive state reads in color too.
                    color =
                        if (stage.idle) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    // weight(1f) fills the row so the trailing pills are pushed to the edge — they
                    // then form a clean end-aligned column down the chain.
                    modifier = Modifier.weight(1f),
                )
                // A neutral status chip ("Idle") labels an inactive chain in words, not color alone.
                stage.statusChip?.let {
                    Spacer(Modifier.width(8.dp))
                    StatusChip(it)
                }
                Spacer(Modifier.width(8.dp))
                ProvenancePill(stage.provenance)
            }
            // The path pill rides the arrow leaving this stage.
            stage.pathPill?.let {
                Spacer(Modifier.height(2.dp))
                PathPill(it)
            }
            stage.subtitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        // Idle chain → grayed subtitle, matching the rest of the de-emphasized stage.
                        color =
                            if (stage.idle) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
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

/**
 * Separator drawn between two stacked thread chains (the multi-output "case B"). A short top margin
 * plus a faint full-width rule visually breaks one thread's complete Source→…→Device flow from the
 * next so they read as independent paths, not one continuous chain.
 */
@Composable
private fun ChainDivider() {
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
    Spacer(Modifier.height(16.dp))
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

/** Small muted pill marking which subsystem a stage's data came from. */
@Composable
private fun ProvenancePill(provenance: Provenance) {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = provenance.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Neutral state chip on a stage title (e.g. "Idle") — labels an inactive chain in words so the
 *  de-emphasized color is not the only signal. Muted on purpose; never the accent ACTIVE color. */
@Composable
private fun StatusChip(label: String) {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Pill on the connector arrow naming the output path (e.g. "Mixed", "Direct", "MMAP · AAudio"). */
@Composable
private fun PathPill(label: String) {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            letterSpacing = 0.3.sp,
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
    mixFormat != null ||
        outputFlags != null ||
        effectChain != null ||
        latencyMillis != null ||
        pathTypeLabel != null ||
        hasMixerStage != null ||
        bitPerfect != null

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

/**
 * The Source-stage readout for one thread. A thread can mix several active client tracks, so when
 * the facade contributed per-track sources we list *every* one of them — each as its own format line
 * with its own resampling arrow where it resamples against the thread rate. The list is unordered
 * (the native side reads tracks in pointer-address order, which is not a signal/pipeline order — see
 * the design doc's "Track ordering" gap), so we draw a neutral bullet, never a numbered or arrowed
 * sequence that would imply an order.
 *
 * A liveness header tops the sources: for a mixer (playback) thread it states "Mixing N active
 * tracks" from the real per-thread count; for an MMAP thread [AudioRoute.activeTrackCount] is only a
 * boolean-ish "active / not" (the native MMAP path lacks the playback active-set check — see the
 * doc), so we say "Active" / "Not active" rather than imply an exact mixer count.
 *
 * When the facade gave us no per-track sources (phase-1 fallback / facade absent), we fall back to
 * the single display [AudioRoute.sourceFormat] line.
 */
private fun sourceLines(route: AudioRoute): List<ReadoutLine> = buildList {
    // Liveness / track-count header. MMAP first because its count is not a true mixer count.
    if (route.mmapActive != null) {
        add(ReadoutLine.Mono(if (route.mmapActive == true) "Active" else "Not active"))
    } else {
        route.activeTrackCount?.let { count ->
            if (count > 1) add(ReadoutLine.Mono("Mixing $count active tracks"))
        }
    }
    if (route.sources.isNotEmpty()) {
        // Each track resamples independently, so the source→mix arrow is drawn per source from that
        // track's own rate to the thread's mix rate. The mix rate is the same proven value the AF
        // stage shows; when it is unknown we draw no arrow rather than invent the target.
        val mixRate = route.mixFormat?.sampleRateHz
        // The leading bullet marks each line as one item of an UNORDERED set — meaningful only when
        // there are several sources (so the list never reads as a sequence). A lone source needs no
        // bullet; it would just look like a stray dot.
        val bulleted = route.sources.size > 1
        route.sources.forEach { source ->
            add(ReadoutLine.Mono(sourceLine(source, mixRate, bulleted)))
        }
    } else {
        // No per-track truth from the facade — show the single representative/display format.
        addAll(formatLines(route.sourceFormat))
    }
}

/**
 * One source track's one-line readout: its real format, with a "→ mix rate" suffix only when that
 * track resamples ([AudioSource.resampling]) and both its own rate and the thread mix rate are known
 * (so the arrow is never invented). A leading bullet is drawn only when [bulleted] (several sources),
 * marking the lines as an unordered set; a lone source is rendered plain.
 */
private fun sourceLine(source: AudioSource, mixRateHz: Int?, bulleted: Boolean): String {
    val fmt = source.format
    val parts = buildList {
        fmt.bitDepth?.let { add("$it bit") }
        // When this track resamples and both rates are known, show the source→mix arrow on the rate
        // itself; otherwise the plain source rate.
        val srcRate = fmt.sampleRateHz
        if (srcRate != null) {
            if (source.resampling && mixRateHz != null && srcRate != mixRateHz) {
                add("${AudioStateLabels.formatRateKHz(srcRate)} → ${AudioStateLabels.formatRateKHz(mixRateHz)}")
            } else {
                add(AudioStateLabels.formatRateKHz(srcRate))
            }
        }
        fmt.channelCount?.let { add(channelLabel(it)) }
        fmt.encodingLabel?.let { add(it) }
    }
    val base = if (parts.isEmpty()) "—" else parts.joinToString("  ·  ")
    return if (bulleted) "· $base" else base
}

/** Readout for the AudioFlinger mixer stage. Effects are their own chain stages, not listed here. */
private fun mixerLines(route: AudioRoute): List<ReadoutLine> = buildList {
    // Rate/channels line for the AF stage (e.g. "48 kHz · Stereo"), with the source→mix rate arrow
    // when the mixer resamples. Bit depth is intentionally NOT folded in here — it is shown as the
    // round-trip line below so the mixer's internal precision change is explicit, not conflated.
    route.mixFormat?.let { mix ->
        val src = route.sourceFormat?.sampleRateHz
        val mixRate = mix.sampleRateHz
        val rate =
            if (src != null && mixRate != null && src != mixRate)
                "${AudioStateLabels.formatRateKHz(src)} → ${AudioStateLabels.formatRateKHz(mixRate)}"
            else mixRate?.let { AudioStateLabels.formatRateKHz(it) }
        val parts = buildList {
            rate?.let { add(it) }
            mix.channelCount?.let { add(channelLabel(it)) }
        }
        if (parts.isNotEmpty()) add(ReadoutLine.Mono(parts.joinToString("  ·  ")))
    }
    // The AudioFlinger bit-depth round-trip: input → internal float → output, each segment from a
    // real read value (sourceFormat, afInternalFormat, mixFormat) and shown ONLY when that segment is
    // genuinely known. The internal and output formats are thread-configuration truths — present on a
    // mixer thread whether or not a stream is flowing — so an idle chain (no active source) still
    // truthfully shows its "internal → output" precision (e.g. "32 float → 16"); only the input/source
    // end is dropped because there is no source. A playing chain shows the full three-segment flow.
    // We render the flow when at least TWO segments are real (a single value is not a transition);
    // nothing is ever fabricated or partially invented.
    val input = route.sourceFormat
    val internal = route.afInternalFormat
    val output = route.mixFormat
    // Each segment shows its real depth plus a "float" suffix when that segment's own format is float
    // (from its encoding label, never assumed): a float source reads "32 float → 32 float → 16", an
    // integer source "16 → 32 float → 16", an idle mixer "32 float → 16".
    val flow = buildList {
        input?.takeIf { it.bitDepth != null }?.let { add(depthToken(it)) }
        internal?.takeIf { it.bitDepth != null }?.let { add(depthToken(it)) }
        output?.takeIf { it.bitDepth != null }?.let { add(depthToken(it)) }
    }
    if (flow.size >= 2) {
        add(ReadoutLine.Pair("Bit depth", flow.joinToString(" → ")))
    }
    route.resampling?.let { resampling ->
        // When resampling, show the rate conversion (source → mix) the mixer performs; otherwise
        // a plain "No". The source/mix rates come from the same proven track-vs-device comparison
        // that set the boolean, so the arrow and the flag never disagree.
        val srcRate = route.sourceFormat?.sampleRateHz
        val mixRate = route.mixFormat?.sampleRateHz
        val value =
            if (resampling && srcRate != null && mixRate != null)
                "Yes (${AudioStateLabels.formatRateKHz(srcRate)} → ${AudioStateLabels.formatRateKHz(mixRate)})"
            else if (resampling) "Yes"
            else "No"
        add(ReadoutLine.Pair("Resampling", value))
    }
    // Bit-perfect verdict: a plain Yes/No row, and when No, each proven reason as its own indented
    // sub-line. Null (facade absent / phase 1) omits the row entirely. The Resampling row above is
    // kept as a useful standalone signal even though it is also one of the bit-perfect reasons.
    route.bitPerfect?.let { bitPerfect ->
        add(ReadoutLine.Pair("Bit-perfect", if (bitPerfect) "Yes" else "No"))
        if (!bitPerfect) {
            route.bitPerfectReasons?.forEach { reason ->
                add(ReadoutLine.Mono("· $reason"))
            }
        }
    }
    route.outputFlags?.takeIf { it.isNotEmpty() }?.let {
        add(ReadoutLine.Pair("Flags", it.joinToString(" ")))
    }
    route.latencyMillis?.let { add(ReadoutLine.Pair("Latency", "${it} ms")) }
}

/**
 * The path pill shown on the arrow leaving the source: the proven output path type, plus an
 * "· AAudio" suffix only for an MMAP-exclusive path (the one case where AAudio is a safe inference,
 * since an exclusive/MMAP output is effectively always AAudio). Never claims AAudio otherwise.
 */
private fun pathPill(route: AudioRoute): String? {
    val label = route.pathTypeLabel ?: return null
    return if (label.equals("MMAP", ignoreCase = true) ||
        label.contains("MMAP", ignoreCase = true)
    ) {
        "$label · AAudio"
    } else {
        label
    }
}

private fun outputDeviceLines(device: AudioDevice, route: AudioRoute): List<ReadoutLine> = buildList {
    add(ReadoutLine.Pair("Interface", device.typeLabel))
    device.address?.let { add(ReadoutLine.Pair("Address", it)) }
    // The actual format leaving through the hardware/DAC, with the device-side resample arrow when
    // the rate feeding the HAL (mix rate if mixed, else source rate) differs from the hardware rate.
    route.hardwareFormat?.let { hw ->
        val feedRate = route.mixFormat?.sampleRateHz ?: route.sourceFormat?.sampleRateHz
        val hwRate = hw.sampleRateHz
        if (feedRate != null && hwRate != null && feedRate != hwRate) {
            add(ReadoutLine.Pair("Sample rate",
                "${AudioStateLabels.formatRateKHz(feedRate)} → ${AudioStateLabels.formatRateKHz(hwRate)}"))
        } else {
            hwRate?.let { add(ReadoutLine.Pair("Sample rate", AudioStateLabels.formatRateKHz(it))) }
        }
        hw.bitDepth?.let { add(ReadoutLine.Pair("Bit depth", "$it bit")) }
        hw.encodingLabel?.let { add(ReadoutLine.Pair("Format", it)) }
    }
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
    // An empty capability array means the device advertises a *dynamic* profile (it adapts to
    // whatever the mixer feeds it) rather than a fixed list — common for the built-in speaker.
    // Show "Dynamic" so the row reads as truthful adaptive capability, not missing data.
    add(ReadoutLine.Pair("Sample rates",
        if (device.sampleRates.isEmpty()) "Dynamic"
        else device.sampleRates.joinToString(", ") { AudioStateLabels.formatRateKHz(it) }))
    add(ReadoutLine.Pair("Channels",
        if (device.channelCounts.isEmpty()) "Dynamic"
        else device.channelCounts.joinToString(", ") { channelLabel(it) }))
    add(ReadoutLine.Pair("Formats",
        if (device.encodings.isEmpty()) "Dynamic"
        else device.encodings.joinToString(", ")))
}

private fun batteryIconFor(percent: Int): ImageVector =
    when {
        percent >= 90 -> Icons.Outlined.BatteryFull
        percent >= 20 -> Icons.Outlined.BatteryStd
        else -> Icons.Outlined.BatteryAlert
    }

/**
 * One segment of the AF bit-depth round-trip, e.g. "32 float" or "16". The depth is the read bit
 * depth; the "float" suffix is derived from the format's own encoding label (PCM float) rather than
 * assumed, so a non-float format renders as a plain depth. Used for all three segments (input,
 * internal, output) so each reflects its own real precision. Caller guarantees [format].bitDepth
 * is non-null.
 */
private fun depthToken(format: AudioFormatSummary): String {
    val depth = "${format.bitDepth}"
    val isFloat = format.encodingLabel?.contains("float", ignoreCase = true) == true
    return if (isFloat) "$depth float" else depth
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
