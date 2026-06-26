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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Add
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
            // chainStages already decides the structure: the summed-source stages (when ≥2 sources)
            // are split out into [ChainRender.group] and the rest into [ChainRender.stages], so this
            // render loop does no topology computation — it just lays the pieces out in order.
            val (groupStages, stages) = chainRender(route)
            // The faint rounded outline wraps the ≥2 source stages plus their outgoing arrow into the
            // mixer, so the set reads as one combined signal entering AudioFlinger. The group, when
            // present, always sits at the head of the chain (the mixer/device follows it), so it is
            // never the chain's last element and always carries a trailing gap.
            if (groupStages.isNotEmpty()) {
                SourceGroup {
                    groupStages.forEach { stage ->
                        // The last grouped stage is never the chain's last stage (the mixer/device
                        // follows), so its outgoing arrow is drawn inside the group and the outline
                        // encloses it.
                        ChainStage(stage = stage, isLast = false)
                    }
                }
                // The group owns no external margin (matching every other composable here); the gap
                // below it before the next stage is supplied here, like the stage-to-stage gaps.
                Spacer(Modifier.height(8.dp))
            }
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
 * arrow below the AudioFlinger stage carries a path pill ("Mixed" / "Direct" / "MMAP · AAudio")
 * showing how the stream leaves — the path type is the AF thread's property, so the pill rides the
 * AF stage's arrow, never the Source's. Provenance pills mark each stage's data source (framework vs
 * the audioserver facade). The output device carries its accessory battery as a chip on its title row.
 *
 * A thread can mix several client tracks, and each track is its own piece of the path: when there are
 * ≥2 sources [addSourceStages] emits one Source stage *per source* (not one stage listing them all),
 * joined by '+' connectors (unordered — the native side reads them in pointer-address order, which is
 * not a signal order; see the design doc's "Track ordering" gap). A thread with no resolved sink device
 * (unmatched port id, unpatched thread, or a DUPLICATING thread whose true sink is not determinable)
 * renders an honest terminal stage with no device identity instead of guessing one.
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

/**
 * A chain split into its render pieces: the summed-source stages that belong inside the faint outline
 * ([group], the ≥2 [Stage.grouped] stages — empty when there are 0 or 1 sources) and everything else
 * ([stages], rendered as plain stages). The grouped stages are always a contiguous run at the head of
 * the chain (the mixer/device always follows), so the partition fully describes the layout and the
 * render loop needs no topology computation of its own.
 */
private data class ChainRender(val group: List<Stage>, val stages: List<Stage>)

/** Builds [chainStages] and partitions it into the bordered source group and the plain stages, so the
 *  layout is fully structured before it reaches the [AudioStateTree] composable. */
private fun chainRender(route: AudioRoute): ChainRender {
    val (group, rest) = chainStages(route).partition { it.grouped }
    return ChainRender(group, rest)
}

private fun MutableList<Stage>.addChainStages(route: AudioRoute) {
    // The Source stage(s) exist only when a stream is actually flowing. A standing-but-idle thread has
    // no source, so showing a "Source / Not active" stage would imply a flow that is not happening —
    // we omit it entirely and let the chain begin at the AudioFlinger stage.
    if (route.isPlaying) {
        addSourceStages(route)
    }
    // The AudioFlinger stage renders for mixer-bearing paths and for a bit-perfect path (which has
    // no mixer stage but still carries the verdict, whether Yes or No). A pure bypass path
    // (direct/offload/mmap) with nothing to show skips straight to the device, explained by the path
    // pill. The gate distinguishes a BIT_PERFECT thread (its own path type — hasMixerStage=false but
    // the verdict is its business) from direct/offload/mmap by the path-type label, NOT by
    // hasMixerStage alone: every bypass thread carries a (false) bit-perfect verdict, so gating on
    // `bitPerfect != null` would wrongly pull direct/offload/mmap into this stage and mislabel them
    // "Bit-perfect". The only hasMixerStage==false path that reaches here is therefore the BIT_PERFECT
    // thread, so the subtitle below reads "Bit-perfect" exactly for it.
    val isBitPerfectThread = route.pathTypeLabel == "Bit-perfect"
    if ((route.hasMixerStage == true || isBitPerfectThread) && route.hasOutputInfo()) {
        add(
            Stage(
                icon = Icons.Outlined.Tune,
                title = "AudioFlinger",
                subtitle = if (isBitPerfectThread) "Bit-perfect" else "Mixer",
                lines = mixerLines(route),
                provenance = Provenance.AUDIOSERVER,
                // The path type (Mixed / Direct / Offload / MMAP) is an AudioFlinger-thread property,
                // so its pill rides the arrow leaving THIS stage — never the source's. This is the
                // home of the former "[Mixed]" leak's value, on the stage that actually owns it.
                pathPill = pathPill(route),
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
        // No [outputDevice] — but the three causes are DIFFERENT truths and must not be conflated.
        // [hasSinkPortId] tells which: false = the thread has NO sink (we'd be LYING to say "could not
        // be resolved", which presupposes a device exists); true = a sink exists but matched no
        // enumerated device (genuinely unidentified); null = no facade info (unknown). Say exactly what
        // is true, never assert a device that isn't there.
        val (deviceSubtitle, deviceLine) =
            when (route.hasSinkPortId) {
                false -> "No output device" to "This thread is not routed to any output device"
                true -> "Unidentified" to "Routed to a device that could not be identified"
                null -> "Unknown" to "Output device unknown"
            }
        add(
            Stage(
                icon = Icons.Outlined.Speaker,
                title = "Output device",
                subtitle = deviceSubtitle,
                lines = listOf(ReadoutLine.Mono(deviceLine)),
                provenance = Provenance.AUDIOSERVER,
            )
        )
    }
}

/**
 * Emits the Source section of a flowing chain. A mixer thread can sum several active client tracks,
 * and each track is a distinct piece of the audio path — so when there are ≥2 sources we emit one
 * Source stage *per source*, each carrying that track's own real format and its own resampling. The
 * sources are unordered (the native side reads tracks in pointer-address order, not a signal order —
 * see the design doc's "Track ordering" gap), so the connector *between* two sibling sources is a
 * commutative '+' (these are summed), never a sequencing arrow or a number. Only the connector after
 * the last source carries the normal downward arrow (with the path pill) into the mixer, and the whole
 * source set is wrapped in a faint outline (see [ChainStage] / [AudioStateTree]) so it reads as one
 * combined signal entering AudioFlinger.
 *
 * A lone source (size == 1) renders a single plain Source stage — no '+', no outline, no bullet. When
 * the facade gave us no per-track sources at all, we fall back to the single representative format on
 * one plain stage.
 */
private fun MutableList<Stage>.addSourceStages(route: AudioRoute) {
    // Hybrid when the facade contributed the source(s) (bit depth/format), framework-only otherwise.
    val provenance =
        if (route.sourceFromFacade == true) Provenance.HYBRID else Provenance.FRAMEWORK
    // The Source stage is leak-proof BY CONSTRUCTION: it is built ONLY from source/track-owned reads
    // (this track's own format + its negotiated flags). It never receives the mixer rate, the
    // hardware format, or the thread path type — the source→mix arrow and the path pill are the AF
    // stage's business and live there. Nothing here can read a neighbour's value because nothing
    // here is handed one.
    if (route.sources.size > 1) {
        val lastIndex = route.sources.lastIndex
        route.sources.forEachIndexed { index, source ->
            add(
                Stage(
                    icon = Icons.Outlined.MusicNote,
                    title = "Source",
                    subtitle = route.usageLabel,
                    // This stage shows ONLY its own track's format and negotiated flags — both
                    // per-track source-owned reads. The mixed-track count / MMAP liveness is an
                    // AudioFlinger-thread aggregate (it iterates the thread's active tracks), so it
                    // lives on the AF stage, never here: a Source stage must not render a thread-level
                    // value. The number of summed sources is already visible structurally as the count
                    // of these grouped stages.
                    lines = buildList {
                        add(ReadoutLine.Mono(sourceLine(source)))
                        sourceFlagsLine(source)?.let { add(it) }
                    },
                    provenance = provenance,
                    // '+' between siblings (summed, order-neutral); a plain downward arrow (no path
                    // pill — the path type is the AF stage's value) only after the last source.
                    connector = if (index == lastIndex) Connector.ARROW else Connector.PLUS,
                    // The faint outline groups the summed-source set plus its outgoing arrow.
                    grouped = true,
                )
            )
        }
    } else {
        // A lone source (or the no-per-track fallback) is a single plain Source stage.
        add(
            Stage(
                icon = Icons.Outlined.MusicNote,
                title = "Source",
                subtitle = route.usageLabel,
                lines = sourceLines(route),
                provenance = provenance,
                // No path pill: the path type is the AF thread's property, rendered on the AF stage.
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

/** The glyph drawn in the connector gap *below* a stage: a downward signal arrow (the default flow
 *  direction), or a '+' joining unordered sibling sources that are summed together. */
private enum class Connector {
    ARROW,
    PLUS,
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
    /** Glyph drawn in this stage's connector gap. ARROW (default) is the normal downward flow; PLUS
     *  joins two sibling source stages that are summed (commutative, order-neutral). */
    val connector: Connector = Connector.ARROW,
    /** True for the summed-source stages: they are wrapped together (plus their outgoing arrow into
     *  the mixer) in a faint rounded outline so the set reads as one combined signal. Only set when
     *  there are ≥2 sources; a lone source is never grouped. [chainRender] partitions on this flag so
     *  the composable receives the group already split out and computes no topology itself. */
    val grouped: Boolean = false,
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
                // The gap glyph: the normal downward flow arrow, or a '+' joining two sibling sources
                // that are summed together (order-neutral — these are unordered, not a sequence).
                Icon(
                    when (stage.connector) {
                        Connector.ARROW -> Icons.Outlined.ArrowDownward
                        Connector.PLUS -> Icons.Outlined.Add
                    },
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

/**
 * Faint rounded outline grouping the summed-source set — the ≥2 Source stages plus their outgoing
 * arrow into the mixer (drawn inside the last grouped stage's rail). The box visually "outputs" one
 * combined signal into AudioFlinger, which sits OUTSIDE/below the outline. The outline tone matches
 * [ChainDivider]'s faint style (low-alpha outline). Drawn only when there are ≥2 sources. Owns no
 * external margin — the gap to the following stage is supplied by the caller, like every other
 * stage-to-stage gap in the chain.
 */
@Composable
private fun SourceGroup(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        content()
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
    return listOf(ReadoutLine.Mono(formatReadout(format)))
}

/**
 * The Source-stage one-line readout: "rate · depth · channels · family" with the bit depth and the
 * encoding family as SEPARATE tokens — e.g. "44.1 kHz · 16 bit · Stereo · PCM", never the fused
 * "PCM 16-bit". The depth comes from [AudioFormatSummary.bitDepth] and the family (depth stripped)
 * from [AudioFormatSummary.encodingFamily]. When the family was not computed (the phase-1
 * AudioPlaybackConfiguration fallback sets only the fused label), fall back to the fused
 * [encodingLabel] so the precision still appears exactly once. Source stage only — the AF and Output
 * device stages keep their own (labeled-row) readouts.
 */
private fun formatReadout(format: AudioFormatSummary): String {
    // Captured into locals because AudioFormatSummary lives in a different module, so its nullable
    // properties cannot be smart-cast in place.
    val depth = format.bitDepth
    val family = format.encodingFamily
    val encoding = format.encodingLabel
    val parts = buildList {
        format.sampleRateHz?.let { add(AudioStateLabels.formatRateKHz(it)) }
        if (family != null) {
            // Decomposed: depth as its own token, family without the depth. Float-ness is a
            // sample-representation specialization of the depth ("32 bit float"), so it rides the depth
            // token — the family stays "PCM". No (24e): a float source carries float's inherent
            // significand, not a reduction this path imposed (proven on the direct path).
            if (depth != null) add(if (format.isFloat) "$depth bit float" else "$depth bit")
            format.channelCount?.let { add(channelLabel(it)) }
            add(family)
        } else {
            // Phase-1 fallback (no family): keep the fused label, or the bare depth token, once.
            format.channelCount?.let { add(channelLabel(it)) }
            when {
                encoding != null -> add(encoding)
                depth != null -> add("$depth bit")
            }
        }
    }
    return if (parts.isEmpty()) "—" else parts.joinToString("  ·  ")
}

/**
 * The AudioFlinger stage's mixed-track-count line, or null when there is nothing to state. The count
 * is an AudioFlinger-THREAD aggregate owned by this stage — [AudioRoute.activeTrackCount] is computed
 * by iterating the thread's active tracks — so it belongs on the AF stage, never on a Source stage (a
 * per-track source has no thread count). Reports "Mixing N active tracks" from the real per-thread
 * count, only when N > 1 (a single track needs no line). This is only reached for a mixer-bearing /
 * bit-perfect thread (the only paths that render an AF stage); a pure MMAP bypass renders no AF stage,
 * and its liveness is already expressed by the chain being shown at all (an inactive MMAP thread is
 * idle and carries no Source stage), so there is no "Active / Not active" wording to host here.
 */
private fun mixerLivenessLine(route: AudioRoute): String? =
    route.activeTrackCount?.takeIf { it > 1 }?.let { "Mixing $it active tracks" }

/**
 * The Source-stage readout for the single-source / fallback path (the ≥2-source case emits one stage
 * per source via [addSourceStages] and never calls this). Shows either that one track's own format
 * line (with its own resampling arrow) or, when the facade gave us no per-track source at all, the
 * single representative [AudioRoute.sourceFormat]. No liveness/track-count header here: that count is
 * an AudioFlinger-thread aggregate and is rendered on the AF stage, never on the Source.
 */
private fun sourceLines(route: AudioRoute): List<ReadoutLine> = buildList {
    val source = route.sources.singleOrNull()
    if (source != null) {
        // ONLY this track's own emitted format — no mix rate, no arrow. The source→mix conversion is
        // the AF stage's value; the source shows just what it emits (e.g. "44.1 kHz · Stereo · …").
        add(ReadoutLine.Mono(sourceLine(source)))
        sourceFlagsLine(source)?.let { add(it) }
    } else {
        // No per-track truth from the facade — show the single representative/display format.
        addAll(formatLines(route.sourceFormat))
    }
}

/**
 * One source track's one-line readout: ONLY this track's own emitted format — rate · channels ·
 * encoding. It shows the track's own rate standing alone (e.g. "44.1 kHz"); it never draws a
 * "→ mix rate" arrow, because the mixer's output rate is the AudioFlinger stage's value, not the
 * source's (the source→mix conversion lives on the AF stage). The Source object does not even hold
 * the mix rate, so the leak is impossible here, not merely avoided.
 *
 * De-dup (#2): the bit count appears ONCE — carried by the encoding label ("PCM 16-bit"), so the
 * standalone "N bit" token is dropped. Rendered plain — each source is its own stage (or the lone
 * source), so no leading bullet is needed.
 */
private fun sourceLine(source: AudioSource): String = formatReadout(source.format)

/**
 * The Source stage's negotiated-flags line: how this track is *configured* after AudioFlinger
 * negotiation (e.g. "Track: FAST", "Track: FAST DIRECT"), read per-track from the facade. Labelled
 * "Track:" honestly — it is the NEGOTIATED/effective per-track flags, NOT the raw app request (which
 * is unreadable). Null when the facade reported no flags, so the line is simply omitted.
 */
private fun sourceFlagsLine(source: AudioSource): ReadoutLine? =
    source.outputFlags.takeIf { it.isNotEmpty() }?.let {
        ReadoutLine.Mono("Track: ${it.joinToString(" ")}")
    }

/** Readout for the AudioFlinger mixer stage. Effects are their own chain stages, not listed here. */
private fun mixerLines(route: AudioRoute): List<ReadoutLine> = buildList {
    // The thread's liveness / mixed-track count — an AudioFlinger-thread aggregate, so it is rendered
    // here on the stage that owns it (never on a Source stage, which sees only its own track).
    mixerLivenessLine(route)?.let { add(ReadoutLine.Mono(it)) }
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
    // The AudioFlinger bit-depth round-trip, segments labelled by ROLE for legibility (#4 clarity):
    // "in 16 → mix 32 float → out 32". Each segment is a real read (sourceFormat, afInternalFormat,
    // mixFormat) and shown ONLY when genuinely known. The internal and output formats are thread-
    // configuration truths — present on a mixer thread whether or not a stream is flowing — so an idle
    // chain (no active source) still truthfully shows its "mix 32 float → out 16"; only the input end
    // is dropped because there is no source. We render the flow when at least TWO segments are real
    // (a single value is not a transition); nothing is fabricated or partially invented.
    val input = route.sourceFormat
    val internal = route.afInternalFormat
    val output = route.mixFormat
    // The (24e) effective-precision cap on the INPUT segment: when the source enters a FLOAT
    // accumulation buffer, effective precision is min(entered container bits, float significand=24).
    // We render "(24e)" only on an actual reduction (min < entered container bits) — both inputs are
    // real reads (the source format's bit depth + the internal format being float), zero inference.
    // PCM_16 source → plain "16" (16 ≤ 24). A bit-perfect/direct path with no float accumulation
    // (internal not float / absent) → full depth, no cap.
    val internalIsFloat = internal?.let { isFloatFormat(it) } == true
    val flow = buildList {
        input?.takeIf { it.bitDepth != null }?.let { add("in ${inputDepthToken(it, internalIsFloat)}") }
        internal?.takeIf { it.bitDepth != null }?.let { add("mix ${depthToken(it)}") }
        output?.takeIf { it.bitDepth != null }?.let { add("out ${depthToken(it)}") }
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
 * The path pill shown on the arrow leaving the AudioFlinger stage: the proven output path type
 * (an AF-thread property), plus an "· AAudio" suffix only for an MMAP-exclusive path (the one case
 * where AAudio is a safe inference, since an exclusive/MMAP output is effectively always AAudio).
 * Never claims AAudio otherwise.
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
    // The HAL/DAC-side format the device RECEIVES — read ONLY from this stage's own hardwareFormat
    // (thread->format()/sampleRate(), the device-facing values). The device stage owns just its
    // received format: it shows that, and OMITS the emitted codec/wire format (not independently
    // readable today — never borrowed from the mixer or the source). No feed→hw arrow either: that
    // would read the AF stage's emitted rate, a cross-stage value the device stage must not touch.
    route.hardwareFormat?.let { hw ->
        hw.sampleRateHz?.let { add(ReadoutLine.Pair("Sample rate", AudioStateLabels.formatRateKHz(it))) }
        // De-dup (#2): the encoding label carries the bit depth for PCM, so the standalone "Bit depth"
        // row is shown only when there is no encoding label — the depth then appears exactly once.
        // Captured into locals because AudioFormatSummary lives in a different module, so its nullable
        // properties cannot be smart-cast in place.
        val encoding = hw.encodingLabel
        val depth = hw.bitDepth
        when {
            encoding != null -> add(ReadoutLine.Pair("Format", encoding))
            depth != null -> add(ReadoutLine.Pair("Bit depth", "$depth bit"))
        }
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
 * assumed, so a non-float format renders as a plain depth. Used for the internal (mix) and output
 * segments so each reflects its own real precision. Caller guarantees [format].bitDepth is non-null.
 */
private fun depthToken(format: AudioFormatSummary): String {
    val depth = "${format.bitDepth}"
    return if (isFloatFormat(format)) "$depth float" else depth
}

/**
 * The INPUT segment of the AF round-trip, with the optional (24e) effective-precision cap. The
 * container depth is the source format's read bit depth. When [throughFloat] (the accumulation buffer
 * is genuinely PCM_FLOAT) and that container exceeds the float significand of 24 bits, the effective
 * precision is capped at 24 and we annotate "(24e)" — an actual reduction, computed from two real
 * reads (the source bit depth + the internal format being float) plus the definitional 24-bit float
 * significand, never inferred. A ≤24-bit source (PCM_16/PCM_24) is lossless through the float buffer
 * and renders plain; a path with no float accumulation renders the full container depth uncapped.
 * Caller guarantees [format].bitDepth is non-null.
 */
private fun inputDepthToken(format: AudioFormatSummary, throughFloat: Boolean): String {
    val containerBits = format.bitDepth!!
    val token = depthToken(format)
    // Float significand is a 24-bit mantissa; effective = min(containerBits, 24). Annotate only on an
    // actual reduction (the source's own float buffer would not cap itself further than its depth).
    return if (throughFloat && !isFloatFormat(format) && FLOAT_SIGNIFICAND_BITS < containerBits)
        "$token ($FLOAT_SIGNIFICAND_BITS" + "e)"
    else token
}

/** True when a format's own encoding label says PCM float (read, never assumed). */
private fun isFloatFormat(format: AudioFormatSummary): Boolean =
    format.encodingLabel?.contains("float", ignoreCase = true) == true

/** IEEE-754 single-precision significand: 24-bit mantissa. The mixer's float accumulation cannot
 *  carry more than this many effective bits, so a >24-bit integer source is precision-capped here. */
private const val FLOAT_SIGNIFICAND_BITS = 24

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
