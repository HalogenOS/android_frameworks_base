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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.audiostate.AudioDevice
import com.android.settingslib.audiostate.AudioFormatSummary
import com.android.settingslib.audiostate.AudioInputRoute
import com.android.settingslib.audiostate.AudioRoute
import com.android.settingslib.audiostate.AudioSource
import com.android.settingslib.audiostate.AudioStateLabels
import com.android.settingslib.audiostate.AudioStateSnapshot
import com.android.settingslib.audiostate.MicrophoneInfoSummary
import com.android.settingslib.audiostate.isHostEndpoint

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
 * @param panelGutter the per-side horizontal gutter the HOST wraps this component in (its surrounding
 *   Column padding). The idle cards break out of this gutter to sit closer to the screen edge, so the
 *   value MUST match the host's actual padding. Passed in (not a baked library constant) so the two
 *   cannot drift across the module boundary; defaults to [DEFAULT_PANEL_GUTTER] for callers (the
 *   dialog) that render no broken-out cards.
 */
@Composable
fun AudioStateTree(
    snapshot: AudioStateSnapshot,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    panelGutter: Dp = DEFAULT_PANEL_GUTTER,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // snapshot.routes is per-active-thread, not per app/usage: each entry is a complete,
        // independent chain described from one thread's own state. Render every one of them stacked
        // (the multi-output "case B": N active threads → N chains). Nothing here is "primary" — we
        // never render only routes.first().
        val routes = snapshot.routes

        // (1) ACTIVE-FIRST STABLE PARTITION. A stream that is actually flowing should always read at
        // the top, with standing-but-idle threads below it. partition() keyed on isPlaying gives
        // exactly playing-before-idle while PRESERVING each group's original enumeration order — it is
        // not a full sort, because there is no proven secondary signal/priority order among the
        // playing chains (nor among the idle ones), and inventing one would violate the truth-only bar.
        val (playing, idle) = routes.partition { it.isPlaying }

        // (2) DIALOG vs SETTINGS DIVERGENCE — the first place the two surfaces differ. The compact QS
        // dialog shows ONLY what is actually playing (idle chains are not rendered at all); the full
        // Settings page shows ALL chains, playing first then idle. This is the single visible-route
        // list both the empty-state guard and the render loop key off — so a dialog whose chains were
        // all filtered out still falls back to EmptyState() rather than rendering blank, and Settings
        // keeps showing idle chains (collapsed, see (3)).
        val visibleRoutes = if (full) playing + idle else playing

        // The empty-state guard keys off the VISIBLE list, not raw routes: in the dialog, "nothing is
        // playing" is empty even when idle threads exist (they are filtered out above). Settings is
        // never "empty" the way the dialog is — its DeviceSection/MicrophoneSection blocks below always
        // render — so the EmptyState row is the dialog's nothing-playing case ONLY, gated on !full so
        // Settings never stacks an empty row above its always-present device sections.
        if (visibleRoutes.isEmpty() && !full) {
            EmptyState()
        }

        visibleRoutes.forEachIndexed { routeIndex, route ->
            // Each iteration is wrapped in key(routeIndex) so Compose scopes the iteration's slot-table
            // state to a STABLE identity rather than to call-site position alone. This matters for (3):
            // an idle chain owns a remembered collapsed/expanded flag, and Compose tracks remembered
            // state by slot position. When the playing/idle composition of visibleRoutes shifts (a
            // chain starts/stops playing), the dispatch at a given position can switch between IdleChain
            // and FullChain; without a key the slot would be reused for a different chain and could
            // briefly carry the wrong chain's expanded state. key(routeIndex) gives each position a
            // discrete identity so state stays attached to its own iteration. AudioRoute carries no
            // stable thread id (verified against the model — no id/key/threadId field), so the visible-
            // list index is the only stable identity available; if the set is reordered the flag resets
            // to collapsed, the one place a true thread id would improve continuity.
            key(routeIndex) {
                // A collapsed idle chain renders as a self-contained rounded CARD (see [IdleChain]); its
                // card edge + the gap below is its own separator. Playing/expanded chains instead read as
                // one continuous stacked flow and are separated by a faint divider. So the divider is drawn
                // only BETWEEN two non-card chains: skip it for the first chain, and skip it whenever this
                // chain OR the previous one is a collapsed-idle card (a card never wants a rule jammed
                // against its edge). A card supplies its own top gap below.
                val isCard = full && !route.isPlaying
                val prevIsCard = routeIndex != 0 && full && !visibleRoutes[routeIndex - 1].isPlaying
                if (routeIndex != 0) {
                    if (isCard || prevIsCard) Spacer(Modifier.height(8.dp)) else ChainDivider()
                }
                // (3) IDLE-CHAIN COLLAPSE, SETTINGS ONLY. An idle chain in Settings renders collapsed by
                // default — a single truthful summary row with its "Idle" chip and an expand affordance —
                // and expands into the exact same stacked stages a playing chain shows. Playing chains are
                // never collapsed. The dialog never reaches here for an idle chain (filtered out in (2)),
                // so collapse/expand is guarded behind `full` AND `!route.isPlaying`; everything else takes
                // the unchanged full-chain path. The collapsed summary is sourced from this chain's OWN
                // stages (chainRender), never from a neighbour — including the 3b case of an idle thread
                // with no sink, which is still rendered collapsed (no device-presence filtering).
                if (full && !route.isPlaying) {
                    IdleChain(route, panelGutter)
                } else {
                    FullChain(route)
                }
            }
        }

        if (full) {
            snapshot.inputRoute?.let { InputRouteSection(it) }
            DeviceSection("Output devices", snapshot.outputDevices)
            DeviceSection("Input devices", snapshot.inputDevices)
            MicrophoneSection(snapshot.microphones)
        }
    }
}

/**
 * The active capture/input route (Settings page only), shown when something is recording. Every value
 * is a real read from one [android.media.AudioRecordingConfiguration]: the capture source, the device
 * recording format, and the input device — nothing is borrowed or fabricated. Rendered as a compact
 * Source → Input-device pair, mirroring the output chain's vocabulary. Omitted entirely when nothing
 * is recording (the caller guards on a non-null inputRoute).
 */
@Composable
private fun InputRouteSection(input: AudioInputRoute) {
    SectionHeader("Capture")
    val stages = buildList {
        add(
            Stage(
                icon = Icons.Outlined.Mic,
                title = "Source",
                subtitle = input.usageLabel,
                lines = formatLines(input.captureFormat),
                provenance = Provenance.FRAMEWORK,
            )
        )
        val dev = input.inputDevice
        add(
            Stage(
                // The input glyph is the mic — iconForDevice() only knows OUTPUT device glyphs (it
                // maps a built-in mic to the speaker glyph), so use Mic for every capture device; the
                // precise interface is named in the Interface line / subtitle below.
                icon = Icons.Outlined.Mic,
                title = "Input device",
                // Host endpoints (built-in mic, telephony) carry the host's product name; show
                // "This device" by structural type. A null device honestly reads "Unknown input
                // device" — never a guessed identity.
                subtitle =
                    when {
                        dev == null -> "Unknown input device"
                        dev.isHostEndpoint -> HOST_LABEL
                        else -> dev.name
                    },
                hostLabel = dev?.isHostEndpoint == true,
                lines = dev?.let { listOf(ReadoutLine.Pair("Interface", it.typeLabel)) }.orEmpty(),
                provenance = Provenance.FRAMEWORK,
            )
        )
    }
    stages.forEachIndexed { index, stage ->
        ChainStage(stage = stage, isLast = index == stages.lastIndex)
    }
}

/**
 * Renders one chain's full stacked layout: the bordered ≥2-source group (when present) followed by the
 * plain stages, top-to-bottom. This is the unchanged 2.1 chain render, extracted verbatim so a playing
 * chain and an EXPANDED idle chain reach the identical stages — an expanded idle chain is exactly the
 * full chain a playing one would show, sourced from the same [chainRender] partition (no topology
 * computed here; the group is already split out).
 */
@Composable
private fun FullChain(route: AudioRoute) {
    // chainStages already decides the structure: the summed-source stages (when ≥2 sources) are split
    // out into [ChainRender.group] and the rest into [ChainRender.stages], so this render does no
    // topology computation — it just lays the pieces out in order.
    val (group, stages) = chainRender(route)
    // The faint rounded outline wraps the ≥2 source stages plus their outgoing arrow into the mixer, so
    // the set reads as one combined signal entering AudioFlinger. The group, when present, always sits
    // at the head of the chain (the mixer/device follows it), so it is never the chain's last element
    // and always carries a trailing gap.
    if (group.isNotEmpty()) {
        SourceGroup {
            group.forEach { stage ->
                // The last grouped stage is never the chain's last stage (the mixer/device follows), so
                // its outgoing arrow is drawn inside the group and the outline encloses it.
                ChainStage(stage = stage, isLast = false)
            }
        }
        // The group owns no external margin (matching every other composable here); the gap below it
        // before the next stage is supplied here, like the stage-to-stage gaps.
        Spacer(Modifier.height(8.dp))
    }
    stages.forEachIndexed { index, stage ->
        ChainStage(stage = stage, isLast = index == stages.lastIndex)
    }
}

/**
 * An idle chain in Settings: collapsed by default to a single truthful summary row (the idle chain's
 * essential identity + its "Idle" chip + an expand affordance), expanding into its full stacked chain
 * via [FullChain]. Settings-only — the dialog filters idle chains out entirely, so the caller only
 * reaches here when `full && !route.isPlaying`. Per 3b, an idle chain with no sink is still rendered
 * collapsed like any other (no device-presence suppression); its summary honestly reads "No output
 * device".
 */
@Composable
private fun IdleChain(route: AudioRoute, panelGutter: Dp) {
    // Per-idle-chain collapsed/expanded UI state. The caller wraps each chain in key(routeIndex), so
    // this remembered flag is already scoped to the chain's stable identity (the visible-list index —
    // AudioRoute carries no thread id; see the caller). A plain remember therefore suffices: the
    // key{} wrapper, not a remember(key) argument, is what keeps the flag attached to its own chain
    // across recomposition. If the route set is reordered the identity changes and the flag resets to
    // collapsed — acceptable absent a stable id, the one place a true thread id would improve continuity.
    var expanded by remember { mutableStateOf(false) }
    // ONE persistent card for this chain in BOTH states: collapsed shows the summary row; expanded keeps
    // the SAME card and reveals the full chain inside it (the card is never removed/replaced on expand,
    // so the rounded edge + breakout geometry stay put — no jump). The card edge (a faint outline +
    // distinct fill, reusing [SourceGroup]'s rounded vocabulary) is what separates one idle chain from the
    // next, so the caller draws no divider line around a card — just a small gap. The card owns the inner
    // padding, the breakout, AND the tap/toggle: the WHOLE card is the clickable surface (see the modifier
    // order below) so the press/hover state layer covers the entire painted card, not just the header band.
    Box(
        modifier =
            Modifier.fillMaxWidth()
                // BREAK OUT of the panel's global horizontal gutter. Everything AudioStateTree emits sits
                // inside the Settings caller's Column(padding(horizontal = 24.dp)) — the "Output devices"
                // headline, the device rows, and these cards all share that 24dp inset. Padding the card
                // would only ADD to that 24dp (pushing it further from the edge, narrower). Instead this
                // modifier widens the card past the gutter and shifts it left so it lands ~8dp from the
                // TRUE screen edge — wider than the text block, less side space than before. See
                // [breakoutHorizontal] for the edge math.
                .breakoutHorizontal(gutter = panelGutter, inset = CARD_EDGE_INSET)
                .clip(RoundedCornerShape(12.dp))
                // MODIFIER ORDER IS LOAD-BEARING for the press/hover highlight. clickable is placed AFTER
                // breakoutHorizontal (so its interaction bounds are the WIDENED, broken-out card box, not
                // the narrower pre-breakout gutter width) and AFTER clip (so the ripple/state layer is
                // clipped to the 12dp rounded card shape). The previous layout put the click on the inner
                // header Row, inset 12dp from each card edge and header-height only, so the highlight read
                // as a middle band; now the indication bounds equal the painted card bounds exactly. It
                // sits BEFORE background/border/padding so the state layer also covers the inner padding
                // region — the whole card lights up. Tapping anywhere on the card toggles expand.
                .clickable { expanded = !expanded }
                // Card fill (HEADER / card base tone): a translucent neutral surfaceVariant tint that
                // separates the card as its own quiet panel against the page background above it, reusing
                // the same surfaceVariant vocabulary the chips and pills in this file already lean on. This
                // is the card's BASE tone — the header band always shows it, and a collapsed card shows ONLY
                // it (single tone). The expanded content below paints a DARKER recess over this base (see the
                // recessed-background Column further down), so the header must stay at this lighter base —
                // never darker. The 0.4f alpha keeps it a soft tint rather than a hard opaque slab.
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                )
                // NO inner padding on the OUTER card box — neither horizontal NOR vertical. Both axes are
                // handled on the inner nodes for the SAME reason:
                //  - HORIZONTAL: the expanded content's recessed background must span the FULL card width
                //    (edge to edge inside the rounded area); a horizontal pad here would inset that background
                //    and leave a header-color band on the left/right (the original band bug).
                //  - VERTICAL: the recessed background must ALSO reach the BOTTOM edge of the content region;
                //    a vertical pad here would leave a header-color band below the darker fill (the bottom
                //    band bug). The recess is the LAST child of the inner Column, so with no outer vertical
                //    pad it reaches the card's bottom edge; its breathing room lives INSIDE the darker fill.
                // The single shared header block re-applies the card's compact symmetric vertical = 4.dp
                // (the same in both states); its own bottom half of that pad is the (header-toned) separation
                // between the header content and the recess appended below — the recess then owns all of its
                // own breathing room INSIDE the darker fill, so the gap around the stages is the darker recess
                // color on every side, never the header tone.
    ) {
        // ONE invariant header, BOTH states. The card stacks its children vertically: the header block
        // always, then — only when expanded — the recess appended BELOW it. The header is built by the
        // EXACT SAME construction regardless of expanded: same wrapper Box, same padding(horizontal = 12.dp,
        // vertical = 4.dp). Expanding the card is literally "the collapsed card, plus
        // content added beneath it" — never a separately-laid-out header with expanded-specific geometry.
        // A bare Column (no wrapping padding) keeps the header block and the recess as direct, FLUSH siblings:
        // no lighter-toned spacer/pad WRAPS the recess (any such space would sit OUTSIDE the darker fill and
        // re-introduce a header-color band; the recess owns all of its own breathing room internally).
        Column {
            // HEADER BLOCK — rendered IDENTICALLY in both states (this is THE invariant). It re-applies the
            // card's 12dp horizontal inset (the outer box supplies none) AND the compact symmetric
            // vertical = 4.dp; the Row takes NO vertical pad of its own (the wrapper Box owns it). Because
            // this construction is the SAME whether collapsed or expanded, the icon, title, pills, "Idle"
            // chip and chevron sit at the identical vertical offset in both states — no between-state drift.
            //
            // The header's OWN bottom pad (the lower half of vertical = 4.dp) is what separates it from the
            // recess appended below: it is NOT a special gap, it is the collapsed header's natural symmetric
            // spacing. The recess is simply appended beneath this already-correctly-padded header — no
            // expanded-specific top padding, no removed bottom pad, no invented separator. The only state
            // difference is the chevron direction, carried by the `expanded` flag (ExpandLess vs ExpandMore).
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                IdleChainHeader(route = route, expanded = expanded)
            }
            if (expanded) {
                // RECESSED CONTENT BACKGROUND (expanded only): the revealed chain body sits on a background
                // that is CLEARLY DARKER than the card's surfaceVariant@0.4f header base, so the content
                // reads as a recess carved into the card. This is a TINT, NOT a nested card: no rounded
                // clip, no border — purely a darker fill behind the body.
                //
                // WHY A SCRIM (BLACK) OVERLAY, A CLEAR ONE STEP: the recess must read DARKER than the header
                // in the dark-theme primary case. The scrim role is black in BOTH the light and dark schemes,
                // so painting it over the card fill is a genuine DARKENING in both themes — never an
                // inversion. 0.18f is a visibly-clear step (not the rejected faint 0.05f, and not an
                // onSurface overlay, which would LIGHTEN in dark theme — the exact wrong direction), while
                // staying short of a hard second panel. It is a colorScheme role, so it tracks the theme.
                //
                // EDGE-TO-EDGE BACKGROUND, INSET CONTENT: this Column carries ONLY fillMaxWidth + background
                // and NO padding of its own, so the darker fill spans the full card WIDTH (left/right edges)
                // AND — as the LAST child reaching the card's bottom edge — the bottom edge too. The stages
                // stay inset via the INNER Column's uniform padding(12.dp), so the breathing room around the
                // stages is the darker recess color on every side — the gap is the recess, never the header
                // tone. The recess sits directly below the header block, whose own bottom pad provides the
                // (header-toned) separation between the header content and the top of the darker fill.
                //
                // WHY A Column, NOT a Box (for BOTH levels): FullChain emits a FLAT LIST of siblings (the
                // source group, a spacer, and the per-stage rows) with NO container of its own — it relies
                // on its parent to stack them vertically, exactly as the top-level Column and a playing
                // chain do. A Box would z-stack those siblings at a common origin, painting each stage on
                // top of the last (the prior overlap bug). A Column reproduces the playing-chain stacking.
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f))
                ) {
                    // Uniform inner inset (12.dp on ALL sides) keeps the stages inset while the background
                    // reaches every edge. Because this padding is INSIDE the darker Column, the inset region
                    // is painted with the recess color — so the gap above the first stage and below the last
                    // stage (and left/right) is the darker tone, giving consistent recess-colored breathing
                    // room all around. Column (NOT Box) so FullChain's flat sibling stages stack vertically.
                    Column(modifier = Modifier.padding(12.dp)) { FullChain(route) }
                }
            }
        }
    }
}

/**
 * Cancels the panel's global horizontal [gutter] and re-applies a smaller [inset] so the element lands
 * [inset] from the TRUE screen edge instead of [gutter] from it — used to let an idle card "break out" of
 * the Settings page's Column(padding(horizontal = gutter)) and sit closer to the edge (wider) than the
 * gutter-bound text around it.
 *
 * Why a layout modifier and not padding/offset:
 *  - `Modifier.padding(horizontal = -x)` is illegal (Compose rejects negative dp).
 *  - `Modifier.offset(x = -x)` only TRANSLATES — it would move the left edge in but leave the right edge
 *    short by the same amount (asymmetric), because it does not widen the measured width.
 * This modifier WIDENS the measured width by `2 * (gutter - inset)` and PLACES the content at
 * `x = -(gutter - inset)`, so both edges move outward symmetrically by the same breakout. With the live
 * 24dp gutter and an 8dp inset the breakout is 16dp per side: left edge 24 - 16 = 8dp from the screen,
 * right edge (gutter) + 16 = 8dp from the screen. Net side space drops 24dp → 8dp (the opposite of the
 * prior "added 8dp" regression).
 *
 * It reports the ORIGINAL (un-widened) width back to the parent Column so the card's overflow does not
 * stretch the Column or push the gutter-bound siblings (device sections) — only the card visually spills
 * past the gutter; everything else stays at [gutter].
 */
private fun Modifier.breakoutHorizontal(gutter: Dp, inset: Dp): Modifier = layout { measurable, constraints ->
    val breakoutPx = (gutter - inset).roundToPx()
    val extra = breakoutPx * 2
    // Widen the available width by the breakout on both sides. The card uses fillMaxWidth(), so this
    // larger max is what it fills to — giving it the wider, closer-to-edge box.
    val widened =
        constraints.copy(
            maxWidth = constraints.maxWidth + extra,
            // Preserve a fixed-width request (fillMaxWidth pins minWidth == maxWidth) so the card still
            // fills the widened box rather than shrink-wrapping its content.
            minWidth =
                if (constraints.hasFixedWidth) constraints.minWidth + extra else constraints.minWidth,
        )
    val placeable = measurable.measure(widened)
    // Report the ORIGINAL (un-widened) max width to the parent — not placeable.width, which is the wider
    // box. This is what keeps the card from stretching the parent Column or pushing the gutter-bound
    // siblings (device sections): the card occupies its normal cell in layout, and only its painted
    // content (placed shifted left below) spills symmetrically past the gutter to land [inset] from each
    // screen edge. The card always fillMaxWidth()s into the widened box, so placeable.width is always
    // larger than this; reporting the original width is the whole point of the breakout, not a clamp.
    layout(constraints.maxWidth, placeable.height) { placeable.place(-breakoutPx, 0) }
}

/**
 * The collapsed/expanded summary row for an idle chain: an icon, the chain's truthful one-line identity
 * (its output INTERFACE TYPE — see [idleChainSummary]), the AF-exit format pills (rate + exit bit depth,
 * read from this thread's own mixFormat — see [idleExitPills]), its "Idle" chip, and an expand/collapse
 * chevron. The row is DISPLAY-ONLY: the enclosing [IdleChain] card owns the tap/toggle affordance so the
 * press/hover state layer spans the whole card, not just this row; the chevron here is purely a visual
 * cue of the current state. Every value shown is a real read from THIS chain's own model — never
 * fabricated, never borrowed from another chain. The device MODEL string ([AudioDevice.name], e.g.
 * "Custom Android SDK built for x86_64") is deliberately NOT shown: it is a product name, identical
 * across threads, and useless as an audio-path label — the neutral TYPE label ([AudioDevice.typeLabel])
 * is the distinguishing identity instead.
 */
@Composable
private fun IdleChainHeader(
    route: AudioRoute,
    expanded: Boolean,
) {
    Row(
        // The row takes NO vertical pad of its own — the enclosing Box (the sole caller) owns the row's
        // vertical offset (symmetric 4dp), identical across states because it is ONE shared wrapper. (A
        // former insideCard=false standalone-fallback branch was dead — the only call site is inside the
        // card — so it has been removed; this header is always card-hosted.)
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // The output-device glyph when this idle thread is patched to a known device, else the
            // generic speaker glyph — the SAME selection the device stage uses (routeDeviceIcon), so
            // the collapsed header and the expanded device stage cannot drift to different icons.
            imageVector = routeDeviceIcon(route),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = idleChainSummary(route),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            // Idle → grayed, consistent with the de-emphasis applied to an idle chain's stages.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // The AF-exit format pills (rate, exit bit depth), each shown ONLY when its value is a real read
        // from this thread's own mixFormat (omit-don't-fake — see [idleExitPills]). They STACK vertically
        // in one narrow column rather than running side-by-side: a wrapping title (e.g. "Disconnected mixer
        // thread") already makes the row tall, so stacking the pills consumes that existing height instead
        // of stealing horizontal width from the title — keeping the row's height unchanged while letting the
        // title wrap less. Reuses the SAME StatusChip primitive as the "Idle" chip for one visual treatment.
        // When idleExitPills is empty (non-mixer/bypass idle thread, no mixFormat) the column has no children
        // and occupies no space — the row then shows just the title and the "Idle" chip.
        //
        // SIZING — why the chip background actually PAINTS here (the prior attempt rendered the pills as
        // bare text with no fill): a StatusChip is a Box whose paint order is clip(rounded) → background →
        // padding → Text, so its fill spans the Box's measured size. The fill only vanishes if the Box is
        // measured to zero width. To guarantee a NONZERO box, every chip is measured with minWidth = 0 so
        // it shrink-wraps its text + 8dp side padding (a real, nonzero width the fill paints into):
        //  - The "Idle" chip is a bare Row child, so the Row already hands it minWidth = 0 → it wraps.
        //  - The pills sit one level deeper, inside this stacking Column. wrapContentWidth(End) on each
        //    pill forces the SAME minWidth = 0 / shrink-wrap that the bare "Idle" chip gets, so the nested
        //    chip cannot be stretched (or pinned to a degenerate width) by any min constraint the Column
        //    might propagate — its fill box is the chip's own content width, exactly like the bare path.
        //  - wrapContentWidth(End) on the Column itself sizes the column to its widest pill and right-aligns
        //    it, so the column never forces a child wide and the pills stay flush to the row's right edge.
        // As a NON-weighted Row child a plain Column would already receive minWidth = 0, so this is partly
        // belt-and-suspenders — but it makes the pills' sizing path provably identical to the proven-
        // painting bare "Idle" chip, removing the structural divergence the prior non-painting attempt had.
        val pills = idleExitPills(route)
        if (pills.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.wrapContentWidth(Alignment.End),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pills.forEach { StatusChip(it, modifier = Modifier.wrapContentWidth(Alignment.End)) }
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusChip("Idle")
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The collapsed idle-chain TITLE: this chain's truthful one-line identity, sourced ONLY from this chain's
 * own model.
 *
 *  - When the chain HAS an output device, the title is the device's neutral INTERFACE TYPE label
 *    ([AudioDevice.typeLabel]: "Remote submix", "Speaker", "Telephony", "USB", "Bluetooth A2DP", …).
 *    NOT [AudioDevice.name] — that is the product/model string ("Custom Android SDK built for x86_64")
 *    which repeats identically across threads and does not name the audio path; the type label is the
 *    distinguishing, audio-meaningful identity.
 *  - When the chain has NO output device but IS a mixer thread (hasMixerStage == true), the title is the
 *    literal "Disconnected mixer thread" — an honest description of a standing mixer thread that is not
 *    currently patched to any sink.
 *  - When there is no device AND it is not a mixer thread (hasMixerStage != true), calling it a "mixer
 *    thread" would be a lie, so fall back to the existing honest hasSinkPortId-driven sink phrasing
 *    ([sinkSubtitle]: "No output device" / "Unidentified output device" / "Output device unknown"),
 *    which never asserts "mixer".
 *
 * The "Idle" word-label and the format pills are NOT folded into this string — they are carried by the
 * chips on the same row (see [IdleChainHeader]) — so nothing here is duplicated.
 */
private fun idleChainSummary(route: AudioRoute): String =
    route.outputDevice?.typeLabel
        ?: if (route.hasMixerStage == true) "Disconnected mixer thread" else sinkSubtitle(route)

/**
 * The AF-exit format pills for the collapsed idle row, in render order: [rate, exit-depth]. BOTH read
 * from [AudioRoute.mixFormat] — the AF mixer's OUTPUT/sink format (mFormat), i.e. what EXITS AudioFlinger
 * to the HAL — NOT [AudioRoute.afInternalFormat] (the internal float accumulation). This mirrors the "out"
 * (sink) side of the expanded view's AF round-trip ("mix 32 float → out 16"), so the SAME idle thread
 * reads [32 float] when its sink format is float and [16 bit] when it is integer.
 *
 * OMIT-DON'T-FAKE (the truth-only bar): every pill is a real read or it is not shown — never a placeholder.
 *  - mixFormat == null (a non-mixer/bypass idle thread, or facade absent): there is no "exits
 *    AudioFlinger" format at all → emit NO pills (the row shows the title + "Idle" chip alone).
 *  - mixFormat.sampleRateHz == null → omit the rate pill specifically.
 *  - exit depth pill: float-ness comes from mixFormat.isFloat (read from the real format constant). When
 *    isFloat is true the depth token reads "<bitDepth> float"; otherwise "<bitDepth> bit". Either way the
 *    pill needs a real bitDepth — when mixFormat.bitDepth == null the depth pill is omitted. The pills are
 *    independent, so a known rate with an unknown depth still shows the rate pill, and vice versa.
 *
 * The bit-depth decomposition (isFloat → "float" suffix, else "bit") differs from the AF round-trip's
 * [depthToken] in ONE way: this standalone chip reads "16 bit" on the integer branch (self-contained),
 * whereas [depthToken] emits a bare "16" because it is joined into the "in → mix → out" flow — so
 * [depthToken] cannot be reused verbatim. Float-ness is identical at the source: BOTH read the real
 * [AudioFormatSummary.isFloat] field now (the provider sets it on every summary), so the pill and the
 * expanded round-trip can no longer disagree. (The earlier divergence — this pill on the field, the
 * round-trip on an encoding-label substring probe — is resolved; the label probe has been removed.)
 */
private fun idleExitPills(route: AudioRoute): List<String> {
    // Captured into a local because AudioFormatSummary lives in a different module, so its nullable
    // properties cannot be smart-cast through the route reference in place.
    val mix = route.mixFormat ?: return emptyList()
    return buildList {
        // Pill 1 (rate): "<kHz> kHz" — only when the real sink sample rate is known.
        mix.sampleRateHz?.let { add(AudioStateLabels.formatRateKHz(it)) }
        // Pill 2 (exit bit depth): the depth that EXITS AudioFlinger — "<depth> float" when the sink
        // format is float, else "<depth> bit". Only when the real exit bit depth is known. Float-ness is
        // read from mixFormat.isFloat (the real format constant, never inferred) — the SAME field the
        // expanded round-trip's depthToken now reads, so the two can no longer disagree.
        mix.bitDepth?.let { depth -> add(if (mix.isFloat) "$depth float" else "$depth bit") }
    }
}

/**
 * The output-device glyph for a chain: the device-typed icon when this chain is patched to a known
 * device, else the generic speaker glyph — the SINGLE source of the chain's device icon, shared by the
 * collapsed idle header and the device stage's no-device fallback so the two cannot drift to different
 * glyphs. (The device stage's WITH-device branch already calls [iconForDevice] on its own dev.type;
 * this helper expresses the same selection plus the no-device fallback in one place.)
 */
private fun routeDeviceIcon(route: AudioRoute): ImageVector =
    route.outputDevice?.let { iconForDevice(it.type) } ?: Icons.Outlined.Speaker

/**
 * The standalone NO-DEVICE phrasing for a chain, used by the collapsed idle summary when the chain
 * has no resolved output device: the hasSinkPortId-driven wording ("No output device" when the thread
 * genuinely has no sink, per 3b; "Unidentified output device" when a sink exists but matched no
 * enumerated device; "Output device unknown" when the facade gave no sink info). This is reached ONLY
 * from [idleChainSummary]'s no-device fallback (its elvis already handled the with-device case via the
 * device's TYPE label), so it never needs — and never shows — the device's product name (which would
 * resurface the deliberately-hidden host model string). Reads the same hasSinkPortId field the device
 * stage reads, so the collapsed summary and the expanded device stage always agree on WHICH case holds.
 */
private fun sinkSubtitle(route: AudioRoute): String =
    when (route.hasSinkPortId) {
        false -> "No output device"
        true -> "Unidentified output device"
        null -> "Output device unknown"
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
    // thread, so the subtitle below reads "Bit-perfect" exactly for it. STRUCTURAL test on the raw
    // pathType int (AudioStateLabels.isBitPerfectPathType), never a display-string compare.
    val isBitPerfectThread = route.pathType?.let { AudioStateLabels.isBitPerfectPathType(it) } == true
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
    val devices = route.outputDevices
    if (devices.isNotEmpty()) {
        // One Output-device stage PER matched sink. A non-duplicating thread with a multi-sink HAL
        // patch routes to several devices at once; we render every one truthfully instead of dropping
        // all but the first. Each stage reads only its OWN device's fields — nothing is borrowed.
        devices.forEach { dev ->
            add(
                Stage(
                    icon = iconForDevice(dev.type),
                    title = "Output device",
                    // Host endpoints (built-in speaker, telephony, etc.) all carry the host's own
                    // product name, which is noise; show "This device" instead, by structural type —
                    // never a name comparison. The flag tells [ChainStage] to render it in accent even
                    // when idle.
                    subtitle = if (dev.isHostEndpoint) HOST_LABEL else dev.name,
                    hostLabel = dev.isHostEndpoint,
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
        }
    } else {
        // No [outputDevice] — but the three causes are DIFFERENT truths and must not be conflated.
        // [hasSinkPortId] tells which: false = the thread has NO sink (we'd be LYING to say "could not
        // be resolved", which presupposes a device exists); true = a sink exists but matched no
        // enumerated device (genuinely unidentified); null = no facade info (unknown). Say exactly what
        // is true, never assert a device that isn't there. The subtitle here is the terse stage label
        // paired with a descriptive line; the collapsed idle summary phrases the SAME hasSinkPortId
        // truth standalone via [sinkSubtitle] — both read the same field, so they cannot disagree about
        // which of the three cases holds, only about presentation length.
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
        if (route.sourceFromFacade) Provenance.HYBRID else Provenance.FRAMEWORK
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
    /** True when this stage's [subtitle] is the "This device" host label (an on-device endpoint, by
     *  structural type — see [isHostEndpoint]). [ChainStage] then renders the subtitle in the accent
     *  color even on an idle chain, so a host endpoint always reads as "This device" in accent.
     *  Additive (default false): every existing stage renders unchanged. */
    val hostLabel: Boolean = false,
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
                        // Accent is the default subtitle color; the ONLY exception is an idle,
                        // non-host subtitle, which grays out to read as inactive. A host label
                        // ("This device") identifies the host rather than a flow, so it stays accent
                        // even on an idle chain — hence the `&& !stage.hostLabel` carve-out. (Folded
                        // from the equivalent three-branch form whose host and flowing arms both
                        // resolved to accent, which read as a tautology.)
                        color =
                            if (stage.idle && !stage.hostLabel)
                                MaterialTheme.colorScheme.onSurfaceVariant
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
                    // Host endpoints all repeat the host's product name; show "This device" in accent
                    // instead. Structural type test (see [isHostEndpoint]) — never a name comparison.
                    // This composable serves both the Output and Input sections, so the input host
                    // rows (built-in mic, telephony, remote submix) are covered here too.
                    text = if (device.isHostEndpoint) HOST_LABEL else device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (device.isHostEndpoint) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
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
private fun MicrophoneSection(microphones: List<MicrophoneInfoSummary>) {
    if (microphones.isEmpty()) return
    SectionHeader("Microphones")
    microphones.forEachIndexed { index, mic ->
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
        if (index != microphones.lastIndex) Spacer(Modifier.height(6.dp))
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
private fun StatusChip(label: String, modifier: Modifier = Modifier) {
    Box(
        // [modifier] is applied OUTSIDE clip/background so a caller can pin the chip's sizing (e.g.
        // wrapContentWidth from inside the stacked pills column) without disturbing the rounded fill.
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                // FULLY OPAQUE surfaceVariant so the pill SHAPE reads at a glance. History: 0.5f sat as a
                // faint tint over the card's own surfaceVariant@0.4f fill and barely registered; 0.9f helped
                // but the chip still washed out — over the expanded recess (the darker scrim) a 0.9f
                // surfaceVariant lets the dark recess bleed through and mute it. Dropping the alpha entirely
                // (plain surfaceVariant, alpha = 1.0) gives a solid, clearly-visible neutral chip that no
                // longer takes on the surface beneath it, while staying a quiet surfaceVariant (not the loud
                // secondaryContainer PathPill uses) — full contrast against both the header base and the
                // recess, never disappearing into either.
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
        pathType != null ||
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

/**
 * The DISTINCT source sample rates feeding the AF stage, for the rate-conversion readouts. Prefers
 * the per-source set ([AudioRoute.sourceSampleRatesHz], every track's real rate — the multi-source
 * truth); falls back to the single representative [AudioRoute.sourceFormat] rate for the facade-absent
 * fallback route (which carries no per-track sources but does carry one playback-config rate). Never
 * fabricates a rate: empty when none is known.
 */
private fun afFeedingRates(route: AudioRoute): List<Int> =
    route.sourceSampleRatesHz.ifEmpty { listOfNotNull(route.sourceFormat?.sampleRateHz) }

/** Joins several feeding rates as "44.1 / 48 kHz" (each exact, no rounding); a single rate renders
 *  as the plain "48 kHz". Used so a multi-source thread shows every real feeding rate, none picked. */
private fun formatRateList(ratesHz: List<Int>): String =
    ratesHz.joinToString(" / ") { AudioStateLabels.formatRateKHz(it) }

/** Readout for the AudioFlinger mixer stage. Effects are their own chain stages, not listed here. */
private fun mixerLines(route: AudioRoute): List<ReadoutLine> = buildList {
    // The thread's liveness / mixed-track count — an AudioFlinger-thread aggregate, so it is rendered
    // here on the stage that owns it (never on a Source stage, which sees only its own track).
    mixerLivenessLine(route)?.let { add(ReadoutLine.Mono(it)) }
    // Rate/channels line for the AF stage (e.g. "48 kHz · Stereo"), with the source→mix rate arrow
    // when the mixer resamples. Bit depth is intentionally NOT folded in here — it is shown as the
    // round-trip line below so the mixer's internal precision change is explicit, not conflated.
    route.mixFormat?.let { mix ->
        val mixRate = mix.sampleRateHz
        // The feeding (source) rate(s) → mix arrow. Uses the DISTINCT source rates of ALL tracks
        // (route.sourceSampleRatesHz), not a single picked source — so a multi-source thread keeps its
        // rate context instead of dropping the arrow. The arrow is drawn only when the feeding rate(s)
        // genuinely differ from the mix rate (a real conversion); rates equal to the mix rate are not
        // shown as a transition.
        val feedingRates = afFeedingRates(route)
        val convertingRates =
            if (mixRate != null) feedingRates.filter { it != mixRate } else emptyList()
        val rate =
            if (convertingRates.isNotEmpty() && mixRate != null)
                "${formatRateList(convertingRates)} → ${AudioStateLabels.formatRateKHz(mixRate)}"
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
    val internalIsFloat = internal?.isFloat == true
    val flow = buildList {
        input?.takeIf { it.bitDepth != null }?.let { add("in ${inputDepthToken(it, internalIsFloat)}") }
        internal?.takeIf { it.bitDepth != null }?.let { add("mix ${depthToken(it)}") }
        output?.takeIf { it.bitDepth != null }?.let { add("out ${depthToken(it)}") }
    }
    if (flow.size >= 2) {
        add(ReadoutLine.Pair("Bit depth", flow.joinToString(" → ")))
    }
    route.resampling?.let { resampling ->
        // When resampling, show the rate conversion the mixer performs — the feeding rate(s) → mix
        // rate. Uses ALL distinct source rates (afFeedingRates), not a single picked source, so a
        // multi-source thread shows "Yes (44.1 / 48 → 48 kHz)" instead of a contextless bare "Yes".
        // Only the rates that actually convert (differ from the mix rate) are listed. When no rate
        // context is available at all (rates unknown) it degrades honestly to "Yes".
        val mixRate = route.mixFormat?.sampleRateHz
        val convertingRates =
            if (mixRate != null) afFeedingRates(route).filter { it != mixRate } else emptyList()
        val value =
            if (resampling && convertingRates.isNotEmpty() && mixRate != null)
                "Yes (${formatRateList(convertingRates)} → ${AudioStateLabels.formatRateKHz(mixRate)})"
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
    // STRUCTURAL gate: the "· AAudio" suffix rides an MMAP-exclusive path, decided from the raw
    // pathType int (AudioStateLabels.isMmapPathType), never a substring of the display label. When the
    // structural int is absent (phase-1 fallback) we cannot assert MMAP, so we show the plain label.
    val isMmap = route.pathType?.let { AudioStateLabels.isMmapPathType(it) } == true
    return if (isMmap) "$label · AAudio" else label
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
    // No Bluetooth-codec row: there is no truthful source for the active codec (the AIDL carries no
    // codec field and no host wires one), so the panel does not advertise data it cannot show.
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
 * depth; the "float" suffix is read from [AudioFormatSummary.isFloat] — the real format constant
 * (the provider now sets it on every summary), never an encoding-label substring probe — so a
 * non-float format renders as a plain depth. Used for the internal (mix) and output segments so each
 * reflects its own real precision. Caller guarantees [format].bitDepth is non-null.
 */
private fun depthToken(format: AudioFormatSummary): String {
    val depth = "${format.bitDepth}"
    return if (format.isFloat) "$depth float" else depth
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
    // Guarded read instead of !!: every current caller already gates on bitDepth != null, but a safe
    // default keeps a future caller from crashing — the token degrades to "—" rather than throwing.
    val containerBits = format.bitDepth ?: return "—"
    val token = depthToken(format)
    // Float significand is a 24-bit mantissa; effective = min(containerBits, 24). Annotate only on an
    // actual reduction (the source's own float buffer would not cap itself further than its depth).
    // float-ness read from the real isFloat field (same source as depthToken), never a label probe.
    return if (throughFloat && !format.isFloat && FLOAT_SIGNIFICAND_BITS < containerBits)
        "$token (${FLOAT_SIGNIFICAND_BITS}e)"
    else token
}

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

/** The label shown in place of the host's product name for the host's own endpoints. This is pure
 *  *output* text — it is never compared against anything (host detection is the structural
 *  [isHostEndpoint] type test), so it does not constitute a name comparison. Hoisted here so all
 *  sites use one literal and cannot drift. */
private const val HOST_LABEL = "This device"

/** Width of the icon/connector rail; the connector line is centered within it. NOTE: this happens to
 *  equal [DEFAULT_PANEL_GUTTER] (both 24.dp) but is an UNRELATED quantity — the rail width, not a
 *  page gutter. The two must not be merged; their equal value is coincidental. */
private val RAIL_WIDTH = 24.dp

/**
 * Default for the [AudioStateTree] `panelGutter` parameter — the per-side horizontal gutter the host
 * wraps the component in. The Settings page passes its REAL gutter explicitly (see
 * AudioInformationPageProvider); this default exists only for callers that render no broken-out idle
 * cards (the QS dialog passes full=false and never reaches an idle card), so the value is never the
 * load-bearing one for them. Coincidentally equals [RAIL_WIDTH] (both 24.dp) but is unrelated to it.
 */
private val DEFAULT_PANEL_GUTTER = 24.dp

/** How far an idle card should sit from the TRUE screen edge after breaking out of the panel gutter. */
private val CARD_EDGE_INSET = 8.dp
