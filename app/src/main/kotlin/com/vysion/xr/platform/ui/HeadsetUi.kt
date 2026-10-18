package com.vysion.xr.platform.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Distances
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.platform.ui.components.Badge
import com.vysion.xr.platform.ui.components.ClockDial
import com.vysion.xr.platform.ui.components.TalkButton
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.StatusBadge
import com.vysion.xr.platform.ui.components.ThinkingLines
import com.vysion.xr.platform.ui.components.Waveform
import com.vysion.xr.platform.ui.components.glass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.VysionType
import com.vysion.xr.platform.ui.theme.vysionSpring
import java.util.Locale

/*
 * The headset's one glass window (designs: Headset 1-12): a status header (badge, title, subtitle), the content
 * of the current [HeadsetScreen] at reading size, and, outside it, the mode rail on the left edge and the
 * contextual toolbar on the bottom edge (SpatialShell places those two as orbiters). Every visual state has its
 * audio twin elsewhere: the controller speaks, the earcons tick; this window only shows.
 */

/** The status window: the glass card and its animated content. Width is set by the caller. */
@Composable
fun StatusWindow(shell: ShellState, type: VysionType, modifier: Modifier = Modifier, compact: Boolean = false) {
    val reduceMotion = LocalReduceMotion.current
    val label = stringResource(R.string.a11y_status_window)
    Box(
        modifier = modifier
            .glass(RoundedCornerShape(Radius.window))
            .semantics { contentDescription = label }
            .padding(horizontal = if (compact) 36.dp else 44.dp, vertical = if (compact) 28.dp else 40.dp),
    ) {
        // Screens of the same kind (two answers, two bearings) update in place; a change of kind materializes.
        AnimatedContent(
            targetState = shell.screen,
            contentKey = { ScreenKey.of(it) },
            transitionSpec = { screenTransition(reduceMotion) },
            label = "status",
        ) { screen -> ScreenContent(shell, screen, type, compact) }
    }
}

/** The kind of a screen: [AnimatedContent] animates only when this changes. */
private enum class ScreenKey {
    READY, LISTENING, THINKING, LOOKING, ANSWER, PLACE, NO_TEXT, GUIDING, ARRIVED, LOST, ERROR;

    companion object {
        fun of(screen: HeadsetScreen): ScreenKey = when (screen) {
            HeadsetScreen.Ready -> READY
            HeadsetScreen.Listening -> LISTENING
            is HeadsetScreen.Thinking -> THINKING
            HeadsetScreen.LookingForDoor -> LOOKING
            is HeadsetScreen.Answer -> ANSWER
            is HeadsetScreen.Place -> PLACE
            HeadsetScreen.NoText -> NO_TEXT
            is HeadsetScreen.Guiding -> GUIDING
            is HeadsetScreen.Arrived -> ARRIVED
            is HeadsetScreen.Lost -> LOST
            HeadsetScreen.Error -> ERROR
        }
    }
}

/** A soft materialize: fade with a slight rise in scale; a plain cross-fade with Remove animations on. */
private fun screenTransition(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        fadeIn(vysionSpring(response = 0.25f)) togetherWith fadeOut(vysionSpring(response = 0.2f))
    } else {
        (fadeIn(vysionSpring(response = 0.35f)) + scaleIn(vysionSpring(response = 0.35f), initialScale = 0.98f)) togetherWith
            fadeOut(vysionSpring(response = 0.2f))
    }

@Composable
private fun ScreenContent(shell: ShellState, screen: HeadsetScreen, type: VysionType, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        when (screen) {
            HeadsetScreen.Ready -> {
                Header(Badge.ORB, stringResource(R.string.status_ready), stringResource(R.string.status_ready_sub), type)
                // Compact (the headset HUD): the commands as a line of body text, not a headline, so Ready stays small.
                Text(stringResource(R.string.status_ready_body), color = Ink.primary, style = if (compact) type.body else type.prompt)
                Footnote(stringResource(R.string.status_ready_foot), type)
            }
            HeadsetScreen.Listening -> {
                Header(Badge.ORB, stringResource(R.string.status_listening), stringResource(R.string.status_listening_sub), type, active = true)
                Waveform(color = Accent.blue, barWidth = 9.dp, height = 72.dp, bars = 13)
            }
            is HeadsetScreen.Thinking -> {
                Header(Badge.ORB, stringResource(R.string.status_thinking), stringResource(R.string.status_thinking_sub), type, active = true)
                screen.asked?.let { asked -> commandWord(asked)?.let { Text(stringResource(R.string.status_thinking_asked, it), color = Ink.secondary, style = type.body) } }
                ThinkingLines(lineHeight = 22.dp)
                Footnote(stringResource(R.string.status_thinking_foot), type)
            }
            HeadsetScreen.LookingForDoor -> {
                Header(Badge.ORB, stringResource(R.string.status_looking), stringResource(R.string.status_looking_sub), type, active = true)
                Text(stringResource(R.string.status_looking_body), color = Ink.primary, style = type.prompt)
                Footnote(stringResource(R.string.status_looking_foot), type)
            }
            is HeadsetScreen.Answer -> {
                val title = when (screen.mode) {
                    Mode.TEXT_READER -> R.string.status_reading
                    Mode.AUDIO_DESCRIPTION -> R.string.status_describing
                    Mode.NAVIGATION -> R.string.world_door
                }
                Header(Badge.ORB, stringResource(title), null, type)
                AnswerText(text = screen.text, spoken = shell.spokenRange(screen.text), type = type)
            }
            is HeadsetScreen.Place -> {
                Header(Badge.ORB, stringResource(R.string.status_where_am_i), null, type)
                AnswerText(text = screen.text, spoken = shell.spokenRange(screen.text), type = type)
                // OpenFreeMap's terms and the ODbL: the map's credit, wherever its data is shown (FR-N9).
                if (screen.mapCredit) Footnote(stringResource(R.string.map_attribution), type)
            }
            HeadsetScreen.NoText -> {
                Header(Badge.TEXT, stringResource(R.string.status_no_text), stringResource(R.string.status_no_text_sub), type)
                Text(stringResource(R.string.status_no_text_body), color = Ink.primary, style = type.prompt)
            }
            is HeadsetScreen.Guiding -> {
                Header(Badge.ORB, stringResource(R.string.status_guiding), stringResource(R.string.status_guiding_sub), type)
                GuidingReadout(screen, type)
                Footnote(stringResource(R.string.status_use_cane), type)
            }
            is HeadsetScreen.Arrived -> {
                Header(Badge.CHECK, stringResource(R.string.status_arrived), stringResource(R.string.status_arrived_sub), type)
                Text(stringResource(R.string.status_arrived_body), color = Ink.primary, style = type.prompt)
                screen.detail?.let { Text(it, color = Ink.secondary, style = type.body) }
            }
            is HeadsetScreen.Lost -> {
                Header(Badge.QUESTION, stringResource(R.string.status_lost), stringResource(R.string.status_lost_sub), type)
                val body = screen.lastClock?.let { stringResource(R.string.status_lost_body, clockText(it)) }
                    ?: stringResource(R.string.status_lost_body_unknown)
                Text(body, color = Ink.primary, style = type.prompt)
                Footnote(stringResource(R.string.status_keep_cane), type)
            }
            HeadsetScreen.Error -> {
                Header(Badge.ERROR, stringResource(R.string.status_error), stringResource(R.string.status_error_sub), type)
                Text(stringResource(R.string.status_error_body), color = Ink.primary, style = type.prompt)
                Footnote(stringResource(R.string.status_error_foot), type)
            }
        }
    }
}

@Composable
private fun Header(badge: Badge, title: String, subtitle: String?, type: VysionType, active: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        StatusBadge(badge, size = 52.dp, active = active)
        Column {
            // A heading for TalkBack navigation. No liveRegion on answers: the app speaks them itself, and TalkBack
            // would read them a second time.
            Text(title, color = Ink.primary, style = type.windowTitle, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, color = Ink.secondary, style = type.windowSubtitle)
        }
    }
}

@Composable
private fun Footnote(text: String, type: VysionType) {
    Text(text, color = Ink.tertiary, style = type.footnote)
}

/**
 * The answer at reading size (≥ 48 sp, SRS §3 rule 4). While Vysion speaks it, the sentence being spoken is
 * yellow and the rest is the secondary ink (designs: "The phrase being spoken is yellow"); otherwise all of it
 * is primary. Long answers scroll, kept in step with the voice.
 */
@Composable
fun AnswerText(text: String, spoken: IntRange?, type: VysionType, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(spoken?.first, text, scroll.maxValue) {
        val start = spoken?.first ?: return@LaunchedEffect
        if (text.isEmpty() || scroll.maxValue == 0) return@LaunchedEffect
        scroll.animateScrollTo((scroll.maxValue * (start.toFloat() / text.length)).toInt(), vysionSpring(response = 0.5f))
    }
    Text(
        text = highlighted(text, spoken),
        style = type.answer,
        modifier = modifier.heightIn(max = 520.dp).verticalScroll(scroll),
    )
}

private fun highlighted(text: String, spoken: IntRange?): AnnotatedString = buildAnnotatedString {
    if (spoken == null) {
        withStyle(SpanStyle(color = Ink.primary)) { append(text) }
        return@buildAnnotatedString
    }
    val first = spoken.first.coerceIn(0, text.length)
    val last = (spoken.last + 1).coerceIn(first, text.length)
    withStyle(SpanStyle(color = Ink.secondary)) { append(text.substring(0, first)) }
    withStyle(SpanStyle(color = Accent.yellow)) { append(text.substring(first, last)) }
    withStyle(SpanStyle(color = Ink.secondary)) { append(text.substring(last)) }
}

@Composable
private fun GuidingReadout(screen: HeadsetScreen.Guiding, type: VysionType) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        ClockDial(clock = screen.clock, color = Accent.cyan, size = 132.dp)
        Column {
            Text(metersText(screen.distanceM), color = Ink.primary, style = type.distance)
            val target = if (screen.inView) R.string.status_guiding_target else R.string.status_guiding_out_of_view
            Text(stringResource(target, clockText(screen.clock)), color = Accent.cyan, style = type.body)
        }
    }
}

/** "3 m" / "2.5 m": the same half-meter rounding the voice uses (Distances, SRS §8 R3). */
@Composable
fun metersText(distanceM: Float): String {
    val rounded = Distances.roundToHalfMeter(distanceM)
    val number = if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(Locale.ROOT, "%.1f", rounded)
    return stringResource(R.string.meters_short, number)
}

@Composable
fun clockText(clock: Int): String = stringResource(R.string.clock_face, clock)

/** The word the wearer said, for "You asked: …". */
@Composable
private fun commandWord(command: Command): String? = when (command) {
    Command.Read -> stringResource(R.string.mode_read)
    Command.Describe, Command.Brief, Command.Detailed -> stringResource(R.string.mode_describe)
    Command.FindDoor, Command.GuideMe -> stringResource(R.string.mode_navigate)
    is Command.FollowUp -> command.question
    Command.WhereAmI -> stringResource(R.string.status_where_am_i)
    else -> null
}

// ── Toolbar (bottom edge) ───────────────────────────────────────────────────────────────────────────────────────

/**
 * The contextual toolbar under the window (designs: one row of pills on the bottom edge, Stop first while
 * Vysion is busy or speaking). Every pill injects the same [Command] the voice would (design rule 2).
 */
@Composable
fun StatusToolbar(shell: ShellState, commands: CommandBus, type: VysionType, height: Dp, modifier: Modifier = Modifier) {
    val busy = shell.state != ModeState.IDLE
    fun send(command: Command) = commands.inject(command, CommandSource.PANEL)
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Glass.tray, CircleShape)
            .border(1.5.dp, Glass.edge, CircleShape)
            .padding(Space.s),
    ) {
        val stop: @Composable () -> Unit = {
            PillButton(stringResource(R.string.action_stop), { send(Command.Stop) }, PillStyle.STOP, type.label, height, icon = VysionIcons.Stop)
        }
        val talk: @Composable () -> Unit = {
            TalkButton(
                label = stringResource(R.string.action_talk),
                doneLabel = stringResource(R.string.action_done),
                listening = shell.listening,
                onStart = commands::startListening,
                onDone = commands::stopListening,
                textStyle = type.label,
                height = height,
            )
        }
        val repeat: @Composable () -> Unit = {
            PillButton(stringResource(R.string.action_repeat), { send(Command.Repeat) }, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Repeat)
        }
        when (val screen = shell.screen) {
            HeadsetScreen.Ready, HeadsetScreen.Error -> talk()
            HeadsetScreen.Listening -> {
                talk() // shows Done while listening
                PillButton(stringResource(R.string.action_cancel), commands::cancelListening, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Close)
            }
            is HeadsetScreen.Thinking, HeadsetScreen.LookingForDoor -> stop()
            is HeadsetScreen.Answer -> {
                if (busy) stop() else talk()
                repeat()
                if (screen.mode == Mode.TEXT_READER) {
                    PillButton(stringResource(R.string.action_next), { send(Command.Next) }, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Next)
                    PillButton(
                        stringResource(R.string.action_spanish), { send(Command.InSpanish) }, PillStyle.SECONDARY, type.label, height,
                        leading = { Text("Es", color = Ink.secondary, style = type.chip) },
                    )
                } else {
                    // "Detailed" alone only changes the next description (FR-D3); the button also describes again
                    // so pressing it visibly does something.
                    PillButton(
                        stringResource(R.string.action_more_detail),
                        { send(Command.Detailed); send(Command.Describe) },
                        PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Plus,
                    )
                    // ADR-0010: the same switch as "cloud on" / "cloud off"; selected while photos may leave.
                    val cloudState = stringResource(if (shell.cloudDescribe) R.string.a11y_cloud_on else R.string.a11y_cloud_off)
                    PillButton(
                        stringResource(R.string.action_cloud), { send(Command.CloudDescribe(!shell.cloudDescribe)) },
                        PillStyle.SECONDARY, type.label, height, selected = shell.cloudDescribe,
                        modifier = Modifier.semantics { stateDescription = cloudState },
                    )
                }
            }
            is HeadsetScreen.Place -> {
                if (busy) stop() else talk()
                repeat()
            }
            HeadsetScreen.NoText ->
                PillButton(stringResource(R.string.action_try_again), { send(Command.Read) }, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Repeat)
            is HeadsetScreen.Guiding -> {
                stop()
                repeat()
                PillButton(stringResource(R.string.action_find_another_door), { send(Command.FindDoor) }, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Plus)
            }
            is HeadsetScreen.Arrived -> {
                talk()
                repeat()
            }
            is HeadsetScreen.Lost -> {
                stop()
                PillButton(stringResource(R.string.action_find_another_door), { send(Command.FindDoor) }, PillStyle.SECONDARY, type.label, height, icon = VysionIcons.Plus)
            }
        }
    }
}

// ── Mode rail (left edge) ───────────────────────────────────────────────────────────────────────────────────────

/** What the rail's fourth button does: the diagnostics window (designs: Headset 13, "…" selected). */
data class RailMore(val selected: Boolean, val onToggle: () -> Unit)

/**
 * The mode rail: an orbiter that floats off the window's left edge. Every item shows its icon AND its label at all
 * times (a gaze-only label was clipped by the rail's glass and made the rail change width while you looked at it),
 * one outlined icon family, a white pill behind the selected mode, and a whole-item hit area of at least
 * [Target.spatial]. Read, Describe and Navigation send the same command the voice would (Navigation: find the door). Three items only: an
 * orbiter is clipped to its window's height, and the shortest window (Listening) is ~300 dp tall, so the rail must
 * fit in that (headset captures 2026-10-17 showed a 4-item rail cut off). Diagnostics is [DiagnosticsToggle].
 */
@Composable
fun ModeRail(mode: Mode?, page: Mode?, commands: CommandBus, itemSize: Dp, labelStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val railLabel = stringResource(R.string.a11y_mode_rail)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .glass(RoundedCornerShape(RAIL_CORNER))
            .semantics { contentDescription = railLabel }
            .padding(vertical = Space.s, horizontal = Space.xs),
    ) {
        RailItem(stringResource(R.string.mode_read), VysionIcons.Read, mode == Mode.TEXT_READER, page == Mode.TEXT_READER, itemSize, labelStyle) {
            commands.inject(Command.Read, CommandSource.PANEL)
        }
        RailItem(stringResource(R.string.mode_describe), VysionIcons.Eye, mode == Mode.AUDIO_DESCRIPTION, page == Mode.AUDIO_DESCRIPTION, itemSize, labelStyle) {
            commands.inject(Command.Describe, CommandSource.PANEL)
        }
        RailItem(stringResource(R.string.mode_navigation), VysionIcons.NavigationArrow, mode == Mode.NAVIGATION, page == Mode.NAVIGATION, itemSize, labelStyle) {
            commands.inject(Command.FindDoor, CommandSource.PANEL)
        }
    }
}

/** The measured-numbers window's switch (FR-G5): a small labeled chip on the window's right edge. */
@Composable
fun DiagnosticsToggle(more: RailMore, height: Dp, labelStyle: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    PillButton(
        label = stringResource(R.string.mode_more),
        onClick = more.onToggle,
        style = PillStyle.SECONDARY,
        textStyle = labelStyle,
        height = height,
        modifier = modifier,
        icon = VysionIcons.Gauge,
        selected = more.selected,
    )
}

/**
 * One rail entry: indicator pill with the icon, the label under it, the whole column clickable. [focused] is the
 * hand-gesture page (`GestureCommands.page`): a ring, so a companion can see what a middle-finger tap will run.
 */
@Composable
private fun RailItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    focused: Boolean,
    size: Dp,
    labelStyle: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pill by animateColorAsState(
        targetValue = when {
            selected -> Glass.white
            hovered -> Color(0x2EFFFFFF)
            else -> Color.Transparent
        },
        animationSpec = vysionSpring(response = 0.25f),
        label = "railPill",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(size * RAIL_ITEM_WIDTH)
            .clip(RoundedCornerShape(Radius.tile))
            .hoverable(interaction)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = size, height = size * RAIL_PILL_HEIGHT)
                .background(pill, CircleShape)
                .border(2.dp, if (focused && !selected) Accent.blue else Color.Transparent, CircleShape),
        ) {
            Icon(icon, contentDescription = null, tint = if (selected) Ink.onLight else Ink.primary, modifier = Modifier.size(size * 0.5f))
        }
        Text(
            label,
            color = if (selected || hovered) Ink.primary else Ink.secondary,
            style = labelStyle,
            maxLines = 1,
        )
    }
}

private val RAIL_CORNER = 36.dp
private const val RAIL_ITEM_WIDTH = 1.65f
private const val RAIL_PILL_HEIGHT = 0.7f

// ── Obstacle (cuts in over everything) ──────────────────────────────────────────────────────────────────────────

/**
 * Headset 10: an orange-edged card with the distance and direction as chips, and OK / Repeat. The shell also
 * dims the room (passthrough) while it shows.
 */
@Composable
fun ObstacleCard(alert: ObstacleAlert, onOk: () -> Unit, onRepeat: () -> Unit, type: VysionType, buttonHeight: Dp, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.l),
        modifier = modifier
            .glass(RoundedCornerShape(Radius.window), edge = Accent.orange)
            .border(3.dp, Accent.orange, RoundedCornerShape(Radius.window))
            .padding(horizontal = 44.dp, vertical = 40.dp),
    ) {
        Header(Badge.WARNING, stringResource(R.string.status_obstacle), stringResource(R.string.status_obstacle_sub), type)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            AccentChip(metersText(alert.distanceM), Accent.orange, type)
            AccentChip(stringResource(R.string.status_obstacle_ahead), Accent.orange, type)
        }
        Text(stringResource(R.string.status_obstacle_body), color = Ink.primary, style = type.prompt)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PillButton(stringResource(R.string.action_ok), onOk, PillStyle.PRIMARY, type.label, buttonHeight, icon = VysionIcons.Stop)
            PillButton(stringResource(R.string.action_repeat), onRepeat, PillStyle.SECONDARY, type.label, buttonHeight, icon = VysionIcons.Repeat)
        }
    }
}

@Composable
fun AccentChip(text: String, color: Color, type: VysionType) {
    Text(
        text,
        color = Ink.onLight,
        style = type.label,
        modifier = Modifier
            .background(color, CircleShape)
            .padding(horizontal = Space.m, vertical = Space.xs),
    )
}
