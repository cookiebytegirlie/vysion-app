package com.vysion.xr.platform.ui

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vysion.xr.AppGraph
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.Speaker
import com.vysion.xr.platform.CameraFrameSource
import com.vysion.xr.platform.ReadingFrame
import com.vysion.xr.platform.ui.PhoneLogic.ReadFrame
import com.vysion.xr.platform.ui.PhoneLogic.ShutterFace
import com.vysion.xr.platform.ui.components.GlyphDisc
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.ThinkingLines
import com.vysion.xr.platform.ui.components.Waveform
import com.vysion.xr.platform.ui.components.phoneGlass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Aurora
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.PhoneType
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.Target
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.rememberReduceMotion
import com.vysion.xr.platform.ui.theme.vysionSpring
import kotlinx.coroutines.launch

/**
 * The phone app, "Phone v6 · End-to-end (iOS style, gesture + camera + audio first)" (Michelle Bui's designs, Figma
 * page 6): the live camera fills the screen and the whole screen is the button. Glass controls float over the camera
 * (settings, the current mode, stop), a camera-style mode switcher sits under the shutter, and answers arrive in a
 * bottom sheet whose spoken sentence turns yellow. Every state has a sound, a spoken line and a haptic, so the screen
 * is optional. Same graph and commands as the headset (ADR-0006); Navigation on the phone is reported unavailable by
 * the controller (`Cues.navigationUnavailable`).
 *
 * Gestures (designs' gesture guide): tap anywhere runs the current mode; press and hold talks (release sends); swipe
 * left or right changes mode; swipe up repeats (more detail in Describe, swipe down for less); sliding away while holding
 * cancels the mic; two-finger double-tap stops speech and closes the mic. With TalkBack on the gesture layer steps
 * aside and the shutter, the mode switcher, the top controls and the sheet's pills carry the same actions.
 *
 * The camera behind everything is a `PreviewView` fed by `CameraFrameSource` (FR-N2); under fakes the ground is dark.
 * [diagnostics] is R5's overlay slot (ADR-0005), switched on from Settings (FR-G5).
 */
@Composable
fun FlatShell(graph: AppGraph, diagnostics: @Composable () -> Unit, onboarding: OnboardingHost) {
    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        if (!onboarding.done) {
            PhoneOnboardingFlow(graph, onboarding)
        } else {
            PhoneApp(graph, diagnostics)
        }
    }
}

@Composable
private fun PhoneApp(graph: AppGraph, diagnostics: @Composable () -> Unit) {
    val shell = rememberShellState(graph)
    val talkBack = rememberTouchExploration()
    var selected by rememberSaveable { mutableStateOf(Mode.TEXT_READER) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val preview = LocalDesignPreview.current
    var diagnosticsShown by rememberSaveable { mutableStateOf(preview == PreviewScreen.DIAGNOSTICS) }
    var hapticsOn by rememberSaveable { mutableStateOf(true) }
    val haptics = rememberPhoneHaptics(hapticsOn)
    val commands = graph.commandBus
    val scope = rememberCoroutineScope()
    val screen = shell.screen
    val face = PhoneLogic.shutter(screen, shell.state, shell.listening)

    // The controller's mode follows voice too; the switcher follows it.
    LaunchedEffect(shell.mode) { shell.mode?.let { selected = it } }
    // "You feel" (designs legend): what the app does on its own, felt in the hand (PhoneFeedback, unit-tested).
    PhoneFeedbackEffect(shell, haptics)
    // Ready is silent, but the mode is said once when the app opens, so an eyes-closed user knows where they are.
    var announcedOnOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!announcedOnOpen) {
            announcedOnOpen = true
            graph.speaker.say(graph.phrasebook.modeAnnounced(selected))
        }
    }

    fun inject(command: Command) = commands.inject(command, CommandSource.PANEL)
    fun run() {
        haptics.tap()
        inject(selected.command())
    }
    fun select(mode: Mode) {
        if (mode == selected) return
        selected = mode
        // "I say each one as you land on it": a selection tick and the mode's name (FR-G3), like a picker wheel.
        haptics.tick()
        scope.launch { graph.speaker.say(graph.phrasebook.modeAnnounced(mode), interrupt = true) }
    }
    fun stop() {
        // The double tick on stop, from any screen; it also closes the microphone, like the headset.
        haptics.stop()
        commands.cancelListening()
        inject(Command.Stop)
    }
    // Swipe up: repeat (Read, Navigate) or more detail (Describe, FR-D3); swipe down in Describe: less detail.
    fun swipeUp() {
        haptics.tap()
        if (selected == Mode.AUDIO_DESCRIPTION) {
            inject(Command.Detailed)
            inject(Command.Describe)
        } else {
            inject(Command.Repeat)
        }
    }
    fun swipeDown() {
        if (selected != Mode.AUDIO_DESCRIPTION) return
        haptics.tick()
        inject(Command.Brief)
        inject(Command.Describe)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraBackdrop(graph.frameSource)
        Scrim()
        StateTint(screen, obstacle = shell.obstacle != null)
        shell.overlay?.let {
            OverlayCanvas(
                renderer = it,
                modifier = Modifier.fillMaxSize(),
                style = OverlayStyle.forScreen(screen, stringResource(R.string.overlay_door), stringResource(R.string.overlay_last_seen))
                    .copy(labelSize = PhoneType.chip.fontSize),
            )
        }
        ReadFrameOverlay(PhoneLogic.readFrame(screen, shell.state, selected))

        // The whole screen is the button (skipped under TalkBack, where the controls below carry the actions).
        if (!talkBack && !settingsOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .gestureFirst(
                        onTap = ::run,
                        onHoldStart = commands::startListening, // the thump comes from PhoneFeedback once LISTENING
                        onHoldEnd = {
                            haptics.send()
                            commands.stopListening()
                        },
                        onHoldCancel = {
                            haptics.reject()
                            commands.cancelListening()
                        },
                        onSwipe = { dx -> select(PhoneLogic.shifted(selected, if (dx < 0) 1 else -1)) },
                        onSwipeUp = ::swipeUp,
                        onSwipeDown = ::swipeDown,
                        onTwoFingerDoubleTap = ::stop,
                    ),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.m, vertical = Space.s),
        ) {
            TopChrome(
                selected = selected,
                stopActive = face == ShutterFace.STOP || face == ShutterFace.BUSY,
                onSettings = { settingsOpen = true },
                onNextMode = { select(PhoneLogic.shifted(selected, 1)) },
                onStop = ::stop,
            )
            if (diagnosticsShown) Box(Modifier.padding(top = Space.s)) { diagnostics() } // R5's panel is its own glass window
            Spacer(Modifier.weight(1f))
            Sheet(shell = shell, selected = selected, onRun = ::run, inject = ::inject)
            Spacer(Modifier.height(Space.m))
            ModeSwitcher(selected = selected, onSelect = ::select)
            Spacer(Modifier.height(Space.s))
            Shutter(face = face, selected = selected, commands = commands, onRun = ::run, onStop = ::stop, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        val reduceMotion = LocalReduceMotion.current
        AnimatedVisibility(
            visible = settingsOpen,
            enter = if (reduceMotion) fadeIn() else fadeIn(vysionSpring()) + slideInVertically(vysionSpring(response = 0.4f)) { it / 8 },
            exit = if (reduceMotion) fadeOut() else fadeOut(vysionSpring(response = 0.25f)) + slideOutVertically(vysionSpring(response = 0.35f)) { it / 8 },
        ) {
            SettingsScreen(
                graph = graph,
                hapticsOn = hapticsOn,
                onHaptics = { hapticsOn = it },
                diagnosticsShown = diagnosticsShown,
                onDiagnostics = { diagnosticsShown = it },
                inject = ::inject,
                onDone = { settingsOpen = false },
            )
        }
    }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
}

// ── Ground: camera, scrim, state tints ───────────────────────────────────────────────────────────────────────────────

/** The live camera (FR-N2) behind everything, or a dark ground under fakes (`FakeFrameSource` has no stream to show). */
@Composable
private fun CameraBackdrop(frames: FrameSource) {
    val camera = frames as? CameraFrameSource
    if (camera == null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Glass.graphite, Aurora.ground))))
        return
    }
    AndroidView(
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                camera.setPreviewSurface(surfaceProvider) // once; `update` would re-set it on every recomposition
            }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { camera.setPreviewSurface(null) },
    )
}

/** Darkens the top and bottom edges so the controls read over any room (designs: 55 % → clear → 80 %). */
@Composable
private fun Scrim() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Glass.scrimTop, 0.18f to Color.Transparent, 0.45f to Color.Transparent, 1f to Glass.scrimBottom)),
    )
}

/** Orange over everything for an obstacle, green when arrived, a blue glow and rings while listening. */
@Composable
private fun StateTint(screen: HeadsetScreen, obstacle: Boolean) {
    val target = when {
        obstacle -> Accent.orange.copy(alpha = 0.42f)
        screen is HeadsetScreen.Arrived -> Accent.green.copy(alpha = 0.38f)
        else -> Color.Transparent
    }
    val tint by animateColorAsState(target, vysionSpring(response = 0.3f), label = "tint")
    Box(Modifier.fillMaxSize().background(tint))
    if (screen == HeadsetScreen.Listening) ListeningGlow()
}

@Composable
private fun ListeningGlow() {
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "listen")
        val value by transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing), RepeatMode.Reverse),
            label = "listenPulse",
        )
        value
    }
    // The blue edge: a thick border blurred into a glow (designs: 26 px border, 17 px blur).
    Box(Modifier.fillMaxSize().blur(24.dp).border(16.dp, Accent.blue.copy(alpha = 0.75f)))
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height * 0.36f)
        drawCircle(Accent.blue.copy(alpha = 0.16f), radius = 100.dp.toPx() * pulse, center = c)
        drawCircle(Accent.blue.copy(alpha = 0.26f), radius = 65.dp.toPx() * pulse, center = c)
        drawCircle(Color.White.copy(alpha = 0.92f), radius = 36.dp.toPx(), center = c)
    }
}

/**
 * The reading frame over the camera while Read works (designs R2–R4). It outlines the part of the still that OCR reads
 * first (`ReadingFrame.stillBox`, the middle of the square center crop), mapped through `PreviewView`'s FILL_CENTER of
 * the 4:3 stream, so the frame shows what is actually read rather than a decorative rectangle.
 */
@Composable
private fun ReadFrameOverlay(kind: ReadFrame) {
    if (kind == ReadFrame.NONE) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The still's square side on screen: FILL_CENTER fits the 3:4 stream by height in portrait, by width otherwise.
        val square = if (maxWidth / maxHeight < STREAM_ASPECT) maxHeight * STREAM_ASPECT else maxWidth
        val box = ReadingFrame.stillBox
        val w = square * (box.right - box.left)
        val h = square * (box.bottom - box.top)
        val left = (maxWidth - w) / 2
        val top = (maxHeight - h) / 2
        val color = if (kind == ReadFrame.DASHED) Accent.purple else Accent.yellow
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val rect = Offset(left.toPx(), top.toPx()) to Size(w.toPx(), h.toPx())
            val radius = CornerRadius(Radius.frame.toPx())
            when (kind) {
                ReadFrame.BRACKETS -> drawBrackets(rect.first, rect.second, color, stroke, 28.dp.toPx())
                ReadFrame.DASHED -> drawRoundRect(
                    color = color,
                    topLeft = rect.first,
                    size = rect.second,
                    cornerRadius = radius,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
                ReadFrame.SOLID -> drawRoundRect(color = color, topLeft = rect.first, size = rect.second, cornerRadius = radius, style = Stroke(width = stroke))
                ReadFrame.NONE -> Unit
            }
        }
        if (kind == ReadFrame.SOLID) {
            Text(
                stringResource(R.string.phone_callout_reading),
                color = Ink.onLight,
                style = PhoneType.label,
                modifier = Modifier
                    .offset(x = left, y = top - 36.dp)
                    .background(Accent.yellow, CircleShape)
                    .padding(horizontal = Space.s, vertical = 6.dp),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBrackets(topLeft: Offset, size: Size, color: Color, stroke: Float, arm: Float) {
    val l = topLeft.x
    val t = topLeft.y
    val r = l + size.width
    val b = t + size.height
    val cap = StrokeCap.Round
    drawLine(color, Offset(l, t + arm), Offset(l, t), stroke, cap)
    drawLine(color, Offset(l, t), Offset(l + arm, t), stroke, cap)
    drawLine(color, Offset(r - arm, t), Offset(r, t), stroke, cap)
    drawLine(color, Offset(r, t), Offset(r, t + arm), stroke, cap)
    drawLine(color, Offset(l, b - arm), Offset(l, b), stroke, cap)
    drawLine(color, Offset(l, b), Offset(l + arm, b), stroke, cap)
    drawLine(color, Offset(r - arm, b), Offset(r, b), stroke, cap)
    drawLine(color, Offset(r, b), Offset(r, b - arm), stroke, cap)
}

/** Width over height of the upright camera stream (`CameraFrameSource`: a 4:3 sensor, portrait on a phone). */
private const val STREAM_ASPECT = 0.75f

// ── Top chrome: settings · mode · stop ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopChrome(selected: Mode, stopActive: Boolean, onSettings: () -> Unit, onNextMode: () -> Unit, onStop: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        GlassIconButton(label = stringResource(R.string.action_settings), icon = VysionIcons.Sliders, onClick = onSettings)
        Spacer(Modifier.weight(1f))
        val modeLabel = selected.label()
        val pillLabel = stringResource(R.string.a11y_mode_pill, modeLabel)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier
                .heightIn(min = Target.phone)
                .phoneGlass(CircleShape)
                .clickable(role = Role.Button, onClick = onNextMode)
                .semantics { contentDescription = pillLabel }
                .padding(start = 18.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
        ) {
            ModeIcon(selected, Ink.primary)
            Text(modeLabel, color = Ink.primary, style = PhoneType.label)
        }
        Spacer(Modifier.weight(1f))
        GlassIconButton(
            label = stringResource(R.string.a11y_stop_button),
            icon = VysionIcons.Stop,
            tint = if (stopActive) Accent.red else Ink.primary,
            onClick = onStop,
        )
    }
}

@Composable
private fun GlassIconButton(label: String, icon: ImageVector, onClick: () -> Unit, tint: Color = Ink.primary) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Target.phone)
            .phoneGlass(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
}

/** The command each mode runs. */
private fun Mode.command(): Command = when (this) {
    Mode.TEXT_READER -> Command.Read
    Mode.AUDIO_DESCRIPTION -> Command.Describe
    Mode.NAVIGATION -> Command.FindDoor
}

@Composable
private fun Mode.label(): String = stringResource(
    when (this) {
        Mode.TEXT_READER -> R.string.mode_read
        Mode.AUDIO_DESCRIPTION -> R.string.mode_describe
        Mode.NAVIGATION -> R.string.mode_navigate
    },
)

@Composable
private fun ModeIcon(mode: Mode, tint: Color) {
    when (mode) {
        Mode.TEXT_READER -> Text("Aa", color = tint, style = PhoneType.label)
        Mode.AUDIO_DESCRIPTION -> Icon(VysionIcons.Eye, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Mode.NAVIGATION -> Icon(VysionIcons.Navigate, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ── The sheet ────────────────────────────────────────────────────────────────────────────────────────────────────────

/** What sits above the mode switcher: a hint while idle, the target picker in Navigate, otherwise the answer sheet. */
@Composable
private fun Sheet(shell: ShellState, selected: Mode, onRun: () -> Unit, inject: (Command) -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val obstacle = shell.obstacle
    val screen = shell.screen
    AnimatedContent(
        targetState = screen,
        contentKey = { s -> if (obstacle != null) "obstacle" else "${s::class.simpleName}-$selected" },
        transitionSpec = {
            if (reduceMotion) {
                fadeIn() togetherWith fadeOut()
            } else {
                (fadeIn(vysionSpring()) + slideInVertically(vysionSpring(response = 0.4f)) { it / 5 }) togetherWith fadeOut(vysionSpring(response = 0.2f))
            }
        },
        label = "sheet",
    ) { shown ->
        when {
            obstacle != null -> SheetCard(Accent.orange, stringResource(R.string.phone_kicker_stop)) {
                Headline(stringResource(R.string.status_obstacle))
                Sub(stringResource(R.string.phone_obstacle_body, metersText(obstacle.distanceM)))
                Pills { SheetPill(stringResource(R.string.action_ok), VysionIcons.Check) { shell.dismissHazard() } }
            }
            shown == HeadsetScreen.Ready -> when (selected) {
                Mode.NAVIGATION -> WhereTo(onDoor = { inject(Command.FindDoor) })
                Mode.TEXT_READER -> HintPill(stringResource(R.string.phone_hint_read), onRun)
                Mode.AUDIO_DESCRIPTION -> HintPill(stringResource(R.string.phone_hint_describe), onRun)
            }
            shown == HeadsetScreen.Listening -> SheetCard(Accent.blue, stringResource(R.string.phone_kicker_listening)) {
                Waveform(color = Accent.blue, barWidth = 6.dp, height = 40.dp, bars = 15)
            }
            shown is HeadsetScreen.Thinking -> SheetCard(Accent.purple, stringResource(R.string.phone_kicker_thinking)) {
                ThinkingLines(lineHeight = 18.dp)
                val hint = when (shown.asked) {
                    Command.Describe, is Command.FollowUp -> R.string.phone_thinking_describe
                    Command.WhereAmI -> R.string.phone_thinking_where
                    else -> R.string.phone_thinking_read
                }
                Foot(stringResource(hint))
            }
            shown == HeadsetScreen.LookingForDoor -> SheetCard(Accent.purple, stringResource(R.string.phone_kicker_looking)) {
                Headline(stringResource(R.string.status_looking))
                Sub(stringResource(R.string.phone_looking_body))
            }
            shown is HeadsetScreen.Answer -> {
                val kicker = when (shown.mode) {
                    Mode.TEXT_READER -> R.string.status_reading
                    Mode.AUDIO_DESCRIPTION -> R.string.status_describing
                    Mode.NAVIGATION -> R.string.world_door // the phone's door glance (ADR-0006)
                }
                SheetCard(if (shown.mode == Mode.NAVIGATION) Accent.cyan else Accent.yellow, stringResource(kicker)) {
                    SheetAnswer(shown.text, shell.spokenRange(shown.text))
                    Pills {
                        if (shown.mode == Mode.NAVIGATION) {
                            SheetPill(stringResource(R.string.action_repeat), VysionIcons.Repeat) { inject(Command.Repeat) }
                            SheetPill(stringResource(R.string.action_find_another_door), VysionIcons.Door) { inject(Command.FindDoor) }
                        } else if (shown.mode == Mode.TEXT_READER) {
                            SheetPill(stringResource(R.string.action_repeat), VysionIcons.Repeat) { inject(Command.Repeat) }
                            SheetPill(stringResource(R.string.action_next), VysionIcons.Next) { inject(Command.Next) }
                        } else {
                            // FR-D3: the detailed variant, then describe again from a fresh frame.
                            SheetPill(stringResource(R.string.action_more_detail), VysionIcons.Plus) {
                                inject(Command.Detailed)
                                inject(Command.Describe)
                            }
                            SheetPill(stringResource(R.string.action_repeat), VysionIcons.Repeat) { inject(Command.Repeat) }
                        }
                    }
                }
            }
            shown is HeadsetScreen.Place -> SheetCard(Accent.cyan, stringResource(R.string.status_where_am_i)) {
                SheetAnswer(shown.text, shell.spokenRange(shown.text))
                // FR-N9: the map's credit wherever its data is shown.
                if (shown.mapCredit) Foot(stringResource(R.string.map_attribution))
                Pills { SheetPill(stringResource(R.string.action_repeat), VysionIcons.Repeat) { inject(Command.Repeat) } }
            }
            shown == HeadsetScreen.NoText -> SheetCard(Accent.yellow, stringResource(R.string.status_no_text)) {
                Headline(stringResource(R.string.status_no_text_body))
                Pills { SheetPill(stringResource(R.string.action_try_again), VysionIcons.Repeat) { onRun() } }
            }
            shown is HeadsetScreen.Guiding -> SheetCard(Accent.cyan, stringResource(R.string.phone_kicker_guiding)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(metersText(shown.distanceM), color = Ink.primary, style = PhoneType.distance, modifier = Modifier.weight(1f))
                    HeadingDisc(shown.clock)
                }
                val clock = clockText(shown.clock)
                Foot(stringResource(if (shown.inView) R.string.phone_guiding_foot else R.string.phone_guiding_out_of_view, clock))
                Pills {
                    SheetPill(stringResource(R.string.action_repeat), VysionIcons.Repeat) { inject(Command.Repeat) }
                    SheetPill(stringResource(R.string.action_stop), VysionIcons.Stop, stop = true) { inject(Command.Stop) }
                }
            }
            shown is HeadsetScreen.Arrived -> SheetCard(Accent.green, stringResource(R.string.status_arrived)) {
                Headline(stringResource(R.string.status_arrived_body))
                shown.detail?.let { Sub(it) }
                Pills {
                    SheetPill(stringResource(R.string.action_describe_it), VysionIcons.Eye) { inject(Command.Describe) }
                    SheetPill(stringResource(R.string.action_find_another_door), VysionIcons.Door) { inject(Command.FindDoor) }
                }
            }
            shown is HeadsetScreen.Lost -> SheetCard(Accent.cyan, stringResource(R.string.status_lost)) {
                Headline(shown.lastClock?.let { stringResource(R.string.status_lost_body, clockText(it)) } ?: stringResource(R.string.status_lost_body_unknown))
                Pills { SheetPill(stringResource(R.string.action_find_another_door), VysionIcons.Door) { inject(Command.FindDoor) } }
            }
            else -> SheetCard(Accent.red, stringResource(R.string.status_error)) { // HeadsetScreen.Error
                Headline(stringResource(R.string.status_error_body))
                Pills { SheetPill(stringResource(R.string.action_try_again), VysionIcons.Repeat) { onRun() } }
            }
        }
    }
}

/** The bottom sheet: a grabber, a colored dot with a small-caps kicker, then the content (designs "Answer sheet"). */
@Composable
private fun SheetCard(dot: Color, kicker: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier
            .fillMaxWidth()
            .phoneGlass(RoundedCornerShape(Radius.sheet), fill = Glass.sheet, edge = Glass.sheetEdge)
            .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 20.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(5.dp).background(Glass.grabber, CircleShape))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(kicker.uppercase(), color = Ink.secondary, style = PhoneType.chip, modifier = Modifier.semantics { heading() })
        }
        content()
    }
}

@Composable
private fun Headline(text: String) = Text(text, color = Ink.primary, style = PhoneType.sheet)

@Composable
private fun Sub(text: String) = Text(text, color = Ink.secondary, style = PhoneType.body)

@Composable
private fun Foot(text: String) = Text(text, color = Ink.tertiary, style = PhoneType.body)

@Composable
private fun Pills(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = Space.xs)) { content() }
}

/** A glass pill inside the sheet (Repeat, Next, More detail, Try again); [stop] makes it the red Stop. */
@Composable
private fun SheetPill(label: String, icon: ImageVector, stop: Boolean = false, onClick: () -> Unit) {
    val fill = if (stop) Accent.red else Glass.control
    val edge = if (stop) Color.Transparent else Glass.controlEdge
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .heightIn(min = Target.phone)
            .phoneGlass(CircleShape, fill = fill, edge = edge)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = Space.m, end = 18.dp, top = 11.dp, bottom = 11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Ink.primary, modifier = Modifier.size(18.dp))
        Text(label, color = Ink.primary, style = PhoneType.label)
    }
}

/** The answer with the sentence being spoken in yellow; scrolls to follow the voice (designs R4, D2). */
@Composable
private fun SheetAnswer(text: String, spoken: IntRange?) {
    val scroll = rememberScrollState()
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(spoken?.first, text, scroll.maxValue) {
        val start = spoken?.first ?: return@LaunchedEffect
        if (text.isEmpty() || scroll.maxValue == 0) return@LaunchedEffect
        val target = (scroll.maxValue * (start.toFloat() / text.length)).toInt()
        if (reduceMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target, vysionSpring(response = 0.5f))
    }
    Text(
        text = sheetHighlight(text, spoken),
        style = PhoneType.sheet,
        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(scroll),
    )
}

private fun sheetHighlight(text: String, spoken: IntRange?): AnnotatedString = buildAnnotatedString {
    if (spoken == null) {
        withStyle(SpanStyle(color = Ink.primary)) { append(text) }
        return@buildAnnotatedString
    }
    val first = spoken.first.coerceIn(0, text.length)
    val last = (spoken.last + 1).coerceIn(first, text.length)
    withStyle(SpanStyle(color = Ink.secondary)) { append(text.substring(0, first)) }
    withStyle(SpanStyle(color = Accent.yellow)) { append(text.substring(first, last)) }
    withStyle(SpanStyle(color = Ink.tertiary)) { append(text.substring(last)) }
}

/** The idle hint ("Tap anywhere to read"); tapping it runs the mode too. */
@Composable
private fun HintPill(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Ink.primary,
            style = PhoneType.label,
            modifier = Modifier
                .heightIn(min = Target.phone)
                .phoneGlass(CircleShape, fill = Glass.sheet, edge = Glass.sheetEdge) // dark, so it reads over a bright room
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = Space.m, end = 18.dp, top = 11.dp, bottom = 11.dp),
        )
    }
}

/**
 * Navigate, idle: "Where to?" (designs N1). The only target the engine knows is the door (FR-N3), so that is the
 * only chip; "exit" and "elevator" wait on a detector class and a contract change.
 */
@Composable
private fun WhereTo(onDoor: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier
            .fillMaxWidth()
            .phoneGlass(RoundedCornerShape(Radius.sheet), fill = Glass.sheet, edge = Glass.sheetEdge)
            .padding(horizontal = 22.dp, vertical = Space.m),
    ) {
        Text(stringResource(R.string.phone_where_to), color = Ink.primary, style = PhoneType.windowTitle, modifier = Modifier.semantics { heading() })
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier
                .heightIn(min = Target.phone)
                .background(Accent.yellow, CircleShape)
                .clickable(role = Role.Button, onClick = onDoor)
                .padding(horizontal = Space.m, vertical = Space.xs),
        ) {
            Icon(VysionIcons.Door, contentDescription = null, tint = Ink.onLight, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.phone_target_door), color = Ink.onLight, style = PhoneType.label)
        }
        Text(stringResource(R.string.phone_where_to_hint), color = Ink.tertiary, style = PhoneType.footnote, textAlign = TextAlign.Center)
    }
}

/** The guidance disc: a cyan circle with the arrow turned to the door's clock hour (designs N3). */
@Composable
private fun HeadingDisc(clock: Int) {
    val reduceMotion = LocalReduceMotion.current
    val angle by animateFloatAsState(
        targetValue = (clock % 12) * 30f,
        animationSpec = if (reduceMotion) tween(0) else vysionSpring(response = 0.5f),
        label = "heading",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).background(Accent.cyan, CircleShape)) {
        Icon(VysionIcons.NavigationArrow, contentDescription = null, tint = Ink.onLight, modifier = Modifier.size(28.dp).rotate(angle))
    }
}

// ── Mode switcher and shutter ────────────────────────────────────────────────────────────────────────────────────────

/**
 * READ · DESCRIBE · NAVIGATE as a camera-style picker: the row slides so the current mode sits centered under the
 * shutter (designs "Mode switcher"). Each label is a tab for TalkBack; the slide is a spring, a jump with Remove
 * animations.
 */
@Composable
private fun ModeSwitcher(selected: Mode, onSelect: (Mode) -> Unit) {
    val modes = Mode.entries
    val reduceMotion = LocalReduceMotion.current
    val position by animateFloatAsState(
        targetValue = modes.indexOf(selected).toFloat(),
        animationSpec = if (reduceMotion) tween(0) else vysionSpring(response = 0.35f),
        label = "switcher",
    )
    val groupLabel = stringResource(R.string.a11y_mode_switcher)
    Layout(
        content = {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val color by animateColorAsState(if (isSelected) Accent.yellow else Ink.secondary, vysionSpring(response = 0.3f), label = "switcherColor")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = Target.phone)
                        .widthIn(min = Target.phone)
                        .clip(CircleShape)
                        .clickable(role = Role.Tab, onClick = { onSelect(mode) })
                        .semantics { this.selected = isSelected }
                        .padding(horizontal = 4.dp),
                ) {
                    // A soft shadow keeps the label legible when the scrim sits over a bright room.
                    Text(mode.label().uppercase(), color = color, style = PhoneType.switcher.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 8f)))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(Target.phone)
            .clip(RoundedCornerShape(0.dp))
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val gap = 30.dp.roundToPx()
        val starts = IntArray(placeables.size)
        val widths = IntArray(placeables.size)
        var x = 0
        placeables.forEachIndexed { i, p ->
            starts[i] = x
            widths[i] = p.width
            x += p.width + gap
        }
        val shift = PhoneLogic.switcherShift(starts, widths, constraints.maxWidth, position)
        val height = constraints.maxHeight
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p -> p.placeRelative(IntOffset(starts[i] + shift, (height - p.height) / 2)) }
        }
    }
}

/**
 * The shutter (designs R1–N5): tap runs the current mode, hold talks; while Vysion works it shows the busy ring, while
 * it speaks a red Stop, when the door is reached a green check. Its TalkBack label says what a double-tap does now.
 */
@Composable
private fun Shutter(face: ShutterFace, selected: Mode, commands: CommandBus, onRun: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val runLabel = stringResource(R.string.a11y_shutter_run, selected.label())
    val stopLabel = stringResource(R.string.a11y_shutter_stop)
    val talkLabel = stringResource(R.string.a11y_shutter_talk)
    val label = when (face) {
        ShutterFace.RUN, ShutterFace.DONE -> runLabel
        ShutterFace.TALK -> talkLabel
        ShutterFace.BUSY, ShutterFace.STOP -> stopLabel
    }
    val tap: () -> Unit = when (face) {
        ShutterFace.RUN, ShutterFace.DONE -> onRun
        ShutterFace.TALK -> commands::stopListening
        ShutterFace.BUSY, ShutterFace.STOP -> onStop
    }
    val currentTap by rememberUpdatedState(tap)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SHUTTER)
            // Keyed on Unit on purpose: a long press flips `face` to TALK while the finger is still down, and a re-keyed
            // pointerInput would cancel the press and lose the release that sends (review 2026-10-18).
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentTap() },
                    onLongPress = { commands.startListening() },
                    onPress = {
                        tryAwaitRelease()
                        if (commands.state.value == ListeningState.LISTENING) commands.stopListening()
                    },
                )
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    tap()
                    true
                }
                onLongClick {
                    if (face == ShutterFace.TALK) commands.stopListening() else commands.startListening()
                    true
                }
            },
    ) {
        if (face != ShutterFace.BUSY) Box(Modifier.size(SHUTTER).border(4.dp, Color.White, CircleShape))
        if (face == ShutterFace.BUSY) BusyRing()
        val disc by animateColorAsState(
            when (face) {
                ShutterFace.RUN -> Color.White
                ShutterFace.TALK -> Accent.blue
                ShutterFace.DONE -> Accent.green
                ShutterFace.BUSY, ShutterFace.STOP -> Color.Transparent
            },
            vysionSpring(response = 0.25f),
            label = "disc",
        )
        Box(Modifier.size(68.dp).background(disc, CircleShape))
        when (face) {
            ShutterFace.TALK -> Icon(VysionIcons.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            ShutterFace.DONE -> Icon(VysionIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            ShutterFace.BUSY -> Box(Modifier.size(30.dp).background(Color.White, RoundedCornerShape(8.dp)))
            ShutterFace.STOP -> Box(Modifier.size(34.dp).background(Accent.red, RoundedCornerShape(9.dp)))
            ShutterFace.RUN -> Unit
        }
    }
}

/** The shutter's diameter (designs: 84, the ring; the disc inside is 68). */
private val SHUTTER = 84.dp

/** A purple arc sweeping around the shutter while Vysion works; still with Remove animations. */
@Composable
private fun BusyRing() {
    val reduceMotion = LocalReduceMotion.current
    val start = if (reduceMotion) {
        -90f
    } else {
        val transition = rememberInfiniteTransition(label = "busy")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing)),
            label = "busyAngle",
        )
        value
    }
    Canvas(Modifier.size(SHUTTER)) {
        val stroke = 4.dp.toPx()
        drawArc(
            color = Accent.purple,
            startAngle = start,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Settings (designs V5): speaking speed and language go to the `Speaker` (the same calls "slower" and "in Spanish"
 * make), answer detail to the controller (FR-D3), haptics and the measured-numbers overlay (FR-G5) stay in the shell.
 * Every row is a labeled control for TalkBack. Nothing here is spoken; nothing is stored beyond this screen's state.
 */
@Composable
private fun SettingsScreen(
    graph: AppGraph,
    hapticsOn: Boolean,
    onHaptics: (Boolean) -> Unit,
    diagnosticsShown: Boolean,
    onDiagnostics: (Boolean) -> Unit,
    inject: (Command) -> Unit,
    onDone: () -> Unit,
) {
    var rate by rememberSaveable { mutableFloatStateOf(1f) }
    var fullDetail by rememberSaveable { mutableStateOf(false) }
    val locale by graph.speaker.locale.collectAsStateWithLifecycle()
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.m),
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) } // swallow taps so nothing under the screen reacts
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_title), color = Ink.primary, style = PhoneType.display, modifier = Modifier.weight(1f).semantics { heading() })
            PillButton(stringResource(R.string.action_done), onDone, PillStyle.PRIMARY, PhoneType.label, Target.phone)
        }
        SettingsSection(stringResource(R.string.settings_voice)) {
            val speedLabel = stringResource(R.string.settings_speed)
            SettingsRow(speedLabel) {
                Slider(
                    value = rate,
                    onValueChange = {
                        rate = it
                        graph.speaker.setRate(it)
                    },
                    valueRange = Speaker.SLOWER_RATE..FASTEST_RATE,
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent.yellow, inactiveTrackColor = Glass.pill),
                    modifier = Modifier.width(160.dp).semantics { contentDescription = speedLabel },
                )
            }
            SettingsRow(stringResource(R.string.settings_language)) {
                Segmented(
                    options = listOf(stringResource(R.string.settings_english), stringResource(R.string.action_spanish)),
                    selected = if (locale.language == Speaker.ES_MX.language) 1 else 0,
                    onSelect = { index ->
                        val chosen = if (index == 1) Speaker.ES_MX else Speaker.EN_US
                        graph.speaker.setLocale(chosen) // FR-G6, the same pair "in Spanish" sets
                        graph.commandBus.setLocale(chosen)
                    },
                )
            }
        }
        SettingsSection(stringResource(R.string.settings_feedback)) {
            SwitchRow(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_sub), hapticsOn, onHaptics)
        }
        SettingsSection(stringResource(R.string.settings_answers)) {
            SettingsRow(stringResource(R.string.settings_detail)) {
                Segmented(
                    options = listOf(stringResource(R.string.settings_short), stringResource(R.string.settings_full)),
                    selected = if (fullDetail) 1 else 0,
                    onSelect = { index ->
                        fullDetail = index == 1
                        inject(if (fullDetail) Command.Detailed else Command.Brief) // FR-D3
                    },
                )
            }
            SwitchRow(stringResource(R.string.settings_measured), stringResource(R.string.settings_measured_sub), diagnosticsShown, onDiagnostics)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            modifier = Modifier.fillMaxWidth().phoneGlass(RoundedCornerShape(Radius.tile)).padding(Space.m),
        ) {
            GlyphDisc(VysionIcons.NoNetwork, Accent.green, Color.White, 30.dp)
            Column {
                Text(stringResource(R.string.settings_privacy_title), color = Ink.primary, style = PhoneType.label)
                Text(stringResource(R.string.settings_privacy_body), color = Ink.secondary, style = PhoneType.footnote)
            }
        }
    }
}

/** The fastest speaking rate the slider offers; 1.0 is normal and [Speaker.SLOWER_RATE] is "slower". */
private const val FASTEST_RATE: Float = 1.3f

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(title.uppercase(), color = Ink.tertiary, style = PhoneType.chip, modifier = Modifier.padding(start = Space.xs).semantics { heading() })
        Column(
            modifier = Modifier.fillMaxWidth().phoneGlass(RoundedCornerShape(Radius.tile), fill = Glass.pill, edge = Color.Transparent),
        ) { content() }
    }
}

@Composable
private fun SettingsRow(label: String, control: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.m, vertical = Space.xs),
    ) {
        Text(label, color = Ink.primary, style = PhoneType.body, modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Switch, onClick = { onChange(!checked) })
            .padding(horizontal = Space.m, vertical = Space.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink.primary, style = PhoneType.body)
            Text(detail, color = Ink.secondary, style = PhoneType.footnote)
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // the row is the control
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent.green,
                uncheckedThumbColor = Ink.secondary,
                uncheckedTrackColor = Glass.pillPressed,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Two or three labeled choices in one pill, the chosen one white (designs: "Short | Full"). */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.phoneGlass(CircleShape, fill = Glass.graphite, edge = Color.Transparent).padding(3.dp).selectableGroup()) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .heightIn(min = Target.phone - 6.dp) // 48 dp with the pill's 3 dp padding
                    .clip(CircleShape)
                    .background(if (isSelected) Color.White else Color.Transparent, CircleShape)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = Space.m),
            ) { Text(option, color = if (isSelected) Ink.onLight else Ink.primary, style = PhoneType.label) }
        }
    }
}
