package com.vysion.xr.platform

import android.graphics.Bitmap
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.Detection
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.OverlayRenderer
import com.vysion.xr.core.PlaceSource
import com.vysion.xr.core.PromptSet
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.WhereAmI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The mode state machine (SRS §6; docs/architecture.md "ModeController state machine"; SRS §8 R1
 * "mode state machine", skeleton due Sat 13:00, full P0 Sat 15:00). Owner: R1. Constructor per
 * `AppGraph`: `(frames, engine, commands, speaker, navigation, anchors, headPose, overlay, scope)`,
 * plus the two seams [phrasebook] and [prompts] that default to unwired placeholders.
 *
 * Pure coroutine logic: no Android class is touched except `Bitmap` as a value passed through, so
 * `ModeControllerTest` runs it on the JVM against `FakeInferenceEngine` and in-test fakes.
 *
 * What it does:
 * - [start]: subscribes to the command bus, the listening state, navigation cues and detections;
 *   starts the frame source and warms the engine up; speaks the welcome (FR-G1).
 * - One mode at a time (Isaac, 2026-10-18): Read and Describe end Navigation ([stopNavigation]: door guidance,
 *   pings, outlines and obstacle warnings all stop, the door marker goes) instead of pausing it; "find the door" or
 *   "guide me" starts it again. Only the arrival check, which belongs to Navigation, pauses and resumes it.
 * - One-shot commands (Read FR-T1, Describe FR-D1, FindDoor FR-N3, arrival FR-N6): pause
 *   Navigation (ADR-0004) → THINKING earcon → `captureStill()` → the VLM under the 15 s
 *   watchdog (INV-5) with a THINKING earcon every 1.5 s (designs: never silent > 2 s) →
 *   transcript → `Speaker.say` → resume Navigation. The still is recycled in `finally` (INV-9).
 *   Read, Describe and arrival stream: `describeStreaming` is cut into sentences ([SentenceSplitter]) and
 *   each is spoken as soon as it completes, while the rest generates; the door vote parses whole answers.
 *   Read speaks the OCR lines instead when an OCR model is loaded. Capture → first spoken word is exposed
 *   on [firstWordLatency]. Any failure speaks [ModePhrasebook.inferenceFailed] and returns to IDLE.
 * - Busy policy (plan.md Q3): a non-Stop command while CAPTURING/INFERRING is answered with
 *   [ModePhrasebook.busy] and dropped; while SPEAKING it preempts the speech.
 * - Stop (FR-G4, INV-6): a separate collector that never waits behind a command cancels the
 *   in-flight command `Job` and calls the non-suspending `Speaker.stop()`; nothing inside the
 *   engine's `Mutex` is involved.
 * - Door vote (FR-N3, RK-6): two stills, two `describe(door)` calls, [DoorVote] allowlist parse,
 *   agreement required. With no door, nothing is said (Isaac, 2026-10-18: "only notify if you find a door"): the
 *   controller keeps watching the Navigation detector ([watchForDoor], [lookingForDoor]) and anchors and guides as
 *   soon as a door holds in one third of the view for [DOOR_WATCH_FRAMES] frames; the guidance is the notification.
 * - Read (FR-T1): the answer and the card pinned in the room disappear once the voice has finished reading
 *   (Isaac, 2026-10-18); "repeat" still says it again.
 * - Mode changes are announced (FR-G3) and exposed on [mode] for the panel.
 * - "In Spanish" switches `Speaker` and `CommandBus` to `Speaker.ES_MX` together (FR-G6); nothing switches
 *   back yet (the grammar has no "in English").
 * - A `DoorBearing` with `pinged == false` (the SceneCore spatial ping failed, A-6) plays `Earcon.PING`
 *   through the `Speaker` before its words.
 * - Hazards first (SRS §3, FR-N1/FR-N5): Proximity, WallAhead and FloorEnds run on their own cue lane, so a
 *   hazard never waits behind a guidance utterance ("door, 2 o'clock, 3 meters") before its earcon and its
 *   interrupting words.
 * - A-7 / RK-4: the first time `CommandBus.state` is `UNAVAILABLE` in a run, [ModePhrasebook.voiceCommandsUnavailable]
 *   is spoken once (after the welcome), so a blind user learns that only the panel and pinch work.
 * - FR-G6 (prompt half): every prompt goes through `PromptSet.forLocale(prompt, Speaker.locale)` before
 *   `describe`, so after "in Spanish" the VLM is asked for Spanish output (R2 exempts DOOR).
 * - [stop] then [start] restarts cleanly (AppGraph's reversible onStop/onStart).
 * - FR-N9 "where am I": [PlaceSource] (Wi-Fi fix + OpenFreeMap tiles decoded on the headset), then the cloud words the
 *   facts ([InferenceEngine.cloudWording], prompt from [PromptSet.whereAmI]) and the wearer hears it sentence by
 *   sentence; offline, on a cloud error, or with no sentence after [WORDING_FIRST_SENTENCE_MS], R4's template
 *   ([ModePhrasebook.whereAmI]) answers instead. Precise location is asked for only the first time the command is used:
 *   the explanation is spoken, then [locationPermissionRequests] asks `MainActivity` to show the dialog, and
 *   [onLocationPermissionResult] runs the command again or says how to allow it. Navigation keeps running: nothing here
 *   touches the inference mutex.
 *
 * Clocks: [commandLatencyNanos] uses `System.nanoTime()` only (H-21).
 * Assumptions: A-1 (frames), A-7 (voice; the panel buttons inject the same commands when it fails).
 */
class ModeController(
    private val frames: FrameSource,
    private val engine: InferenceEngine,
    private val commands: CommandBus,
    private val speaker: Speaker,
    private val navigation: NavigationEngine,
    private val anchors: AnchorStore,
    @Suppress("unused") private val headPose: HeadPoseSource, // FR-T5 (P1) "hold still" reads angularSpeedDegPerS
    private val overlay: OverlayRenderer,
    private val scope: CoroutineScope,
    private val phrasebook: ModePhrasebook = ModePhrasebook.Unwired,
    private val prompts: PromptSet = PromptSet.Unwired,
    private val places: PlaceSource = NO_PLACES,
) {

    private val _state = MutableStateFlow(ModeState.IDLE)

    /** SRS §6 states. Navigation keeps running in the background while IDLE. */
    val state: StateFlow<ModeState> = _state

    private val _mode = MutableStateFlow<Mode?>(null)

    /** FR-G3: the active mode, `null` until the first command. Shown on the control panel. */
    val mode: StateFlow<Mode?> = _mode

    private val _transcript = MutableStateFlow<String?>(null)

    private val _worldLabel = MutableStateFlow<WorldLabel?>(null)

    /**
     * Augmented layer: what to pin in the room, at which anchor. The door marker while guiding (FR-N3) and the text
     * that was just read, next to the sign (FR-T1). `null` when there is nothing to pin; cleared on stop.
     */
    val worldLabel: StateFlow<WorldLabel?> = _worldLabel

    /** FR-T2: the last VLM text, for the transcript panel. `null` until the first result. */
    val transcript: StateFlow<String?> = _transcript

    private val _transcriptKind = MutableStateFlow(TranscriptKind.ANSWER)

    /** What [transcript] holds: a Read / Describe answer, or a "where am I" answer (FR-N9, which credits the map). */
    val transcriptKind: StateFlow<TranscriptKind> = _transcriptKind

    private val _locationPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * FR-N9: "where am I" needs precise location and it is not granted. `MainActivity` shows the system dialog (after
     * the controller spoke why) and reports back through [onLocationPermissionResult].
     */
    val locationPermissionRequests: SharedFlow<Unit> = _locationPermissionRequests

    private val _whereAmILatency = MutableStateFlow<WhereAmILatency?>(null)

    /** FR-N9 evidence: command recognized → first spoken word, and who worded it. `MainActivity` logs it. */
    val whereAmILatency: StateFlow<WhereAmILatency?> = _whereAmILatency

    private val _commandLatencyNanos = MutableStateFlow<Long?>(null)

    /** NFR latency "command recognized → action started" (≤ 1.5 s), measured per one-shot; `null` until measured. */
    val commandLatencyNanos: StateFlow<Long?> = _commandLatencyNanos

    private val _firstWordLatency = MutableStateFlow<FirstWordLatency?>(null)

    /**
     * NFR latency "capture → first spoken word" (Read ≤ 6 s FR-T1, Describe ≤ 8 s FR-D1), per Read / Next / Describe;
     * `null` until measured. `MainActivity` logs it under `vysion.latency`.
     */
    val firstWordLatency: StateFlow<FirstWordLatency?> = _firstWordLatency

    private val _lastError = MutableStateFlow<Throwable?>(null)

    /** The last failure the controller recovered from, for `MainActivity` to log under `vysion.reliability`. */
    val lastError: StateFlow<Throwable?> = _lastError

    /** Lives from [start] to [stop]; owns every collector so [stop] cancels them in one call. */
    private var runScope: CoroutineScope? = null

    /** The in-flight one-shot, cancelled by Stop (INV-6). Read from two collectors, hence volatile. */
    @Volatile
    private var active: Job? = null

    /** The last [stop]'s Navigation and camera teardown; the next [start] waits for it before starting the frames. */
    private var teardown: Job? = null

    /** A-7 / RK-4: [ModePhrasebook.voiceCommandsUnavailable] is spoken at most once per [start]. */
    @Volatile
    private var voiceUnavailableAnnounced = false

    /** The welcome one-shot of the current run; the voice-unavailable notice waits for it (welcome first). */
    @Volatile
    private var welcomeJob: Job? = null

    /** FR-D3 (P1): "brief" / "detailed" choose the describe prompt variant for the next Describe. */
    @Volatile
    private var describeVariant: DescribeVariant = DescribeVariant.DEFAULT

    private val _lookingForDoor = MutableStateFlow(false)

    /**
     * "Find the door" found none at once and is watching the Navigation detector for one, silently (the HUD shows
     * "Looking for a door"). Ends when a door is anchored, on Stop, or when Navigation stops.
     */
    val lookingForDoor: StateFlow<Boolean> = _lookingForDoor

    /** The silent door watch ([watchForDoor]); at most one. */
    @Volatile
    private var doorWatch: Job? = null

    /** The pending pin of the text just read ([pinReadText]), cancelled when the text is cleared. */
    @Volatile
    private var readPin: Job? = null

    private val _cloudDescribe = MutableStateFlow(false)

    /**
     * ADR-0010: the wearer's cloud Describe switch ("cloud on" / "cloud off", the Describe page's Cloud pill), passed
     * to [InferenceEngine.describeScene]. Off at every launch and whenever the headset is taken off ([stop]), so the
     * next wearer never sends photos without saying so.
     */
    val cloudDescribe: StateFlow<Boolean> = _cloudDescribe

    /**
     * Idempotent. Subscribes, starts the frame source, warms the engine up, speaks the welcome (FR-G1).
     * Restartable: [stop] then [start] (headset off and on, `AppGraph.stop`/`start`) runs a fresh set of
     * collectors in a new child scope of [scope] and speaks the welcome again. [speakWelcome] is false right
     * after first-launch onboarding, whose last page already said what the welcome says.
     */
    fun start(speakWelcome: Boolean = true) {
        if (runScope != null) return
        val run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        runScope = run
        voiceUnavailableAnnounced = false
        val previousTeardown = teardown

        // One upstream subscription, two consumers: the Stop lane must never wait behind a command (INV-6).
        val events: SharedFlow<CommandEvent> = commands.commands.shareIn(run, SharingStarted.Eagerly)
        run.launch { events.filter { it.command is Command.Stop }.collect { onStop() } }
        run.launch { events.filter { it.command !is Command.Stop }.collect { onCommand(it) } }
        run.launch { commands.state.collect { onListeningState(it) } }
        // Two cue lanes on one upstream subscription: a hazard never waits behind a guidance utterance (SRS §3).
        val cueEvents: SharedFlow<NavigationCue> = navigation.cues.shareIn(run, SharingStarted.Eagerly)
        run.launch { cueEvents.filter { it.isHazard() }.collect { onCue(it) } }
        run.launch { cueEvents.filter { !it.isHazard() }.collect { onCue(it) } }
        run.launch { navigation.detections.collect { overlay.showDetections(it) } } // FR-N2
        run.launch {
            previousTeardown?.join() // a quick off/on must not stop the camera after it was started again
            guarded { frames.start() } // A-1; CameraFrameSource logs the probe and the binding
        }
        run.launch { guarded { engine.warmUp() } } // A-11; a failure is recorded, never a crash at launch

        if (speakWelcome) {
            launchOneShot(ModeState.SPEAKING) { say(phrasebook.welcome) } // FR-G1
            welcomeJob = active
        }
    }

    /** Start-path rule: nothing thrown by a module may crash the skeleton app; cancellation still propagates. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e
        } catch (e: NotImplementedError) {
            _lastError.value = e
        }
    }

    /**
     * Silences, cancels the in-flight command and every collector, stops Navigation and the frames. Never
     * throws. The mode goes back to `null` because Navigation is no longer running, so the first command
     * after a restart announces its mode again (FR-G3).
     */
    fun stop() {
        val run = runScope ?: return
        runScope = null
        _worldLabel.value = null // anchors do not survive the XR session
        active?.cancel() // before silencing, as in [onStop]
        active = null
        speaker.stop()
        run.cancel()
        overlay.clear()
        _state.value = ModeState.IDLE
        _mode.value = null
        _cloudDescribe.value = false // ADR-0010: consent is per wearer; taking the headset off withdraws it
        endDoorWatch()
        // UNDISPATCHED + NonCancellable: completes even if the caller tears the graph down right after.
        teardown = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { navigation.stop() }
                runCatching { frames.stop() }
            }
        }
    }

    /** FR-G1: spoken by `MainActivity` before the permission dialogs (`Cues.permissionExplanation`, R4). */
    fun speakPermissionExplanation(): Job = scope.launch { say(phrasebook.permissionExplanation) }

    /** FR-G1: spoken when camera or microphone is denied (`Cues.permissionDenied`, R4). */
    fun speakPermissionDenied(): Job = scope.launch { say(phrasebook.permissionDenied, interrupt = true) }

    /**
     * FR-N9: the location dialog closed. Granted: "where am I" runs again as if asked; refused: the way to allow it is
     * spoken (precise location only: approximate is fuzzed to kilometers and names no street).
     */
    fun onLocationPermissionResult(granted: Boolean) {
        if (granted) {
            commands.inject(Command.WhereAmI, CommandSource.PANEL)
        } else {
            launchOneShot(ModeState.SPEAKING) { sayNotice(phrasebook.whereAmI(WhereAmI.PermissionDenied)) }
        }
    }

    // ── Command lanes ─────────────────────────────────────────────────────────────────────────────

    /**
     * INV-6 / FR-G4: runs outside any inference; both calls are non-suspending, so this is sub-millisecond. The
     * command is cancelled before the speaker is silenced, so a sentence still queued by a streaming answer cannot
     * reach the speaker after the silence.
     */
    private fun onStop() {
        active?.cancel(CancellationException("Command.Stop"))
        speaker.stop()
        endDoorWatch()
    }

    private suspend fun onCommand(event: CommandEvent) {
        val current = active
        if (current?.isActive == true) {
            when (_state.value) {
                // Busy policy (plan.md Q3): say why, drop the command; Stop stays available on its own lane.
                ModeState.CAPTURING, ModeState.INFERRING -> {
                    say(phrasebook.busy)
                    return
                }
                // SPEAKING / LISTENING: a new command preempts the current utterance (SRS §3 "interruptible").
                else -> {
                    current.cancel() // before silencing, as in [onStop]
                    speaker.stop()
                    current.join()
                }
            }
        }
        when (val command = event.command) {
            Command.Read -> launchOneShot(ModeState.CAPTURING) {
                captureAndDescribe(Mode.TEXT_READER, prompts.read, event) // FR-T1
            }
            Command.Describe -> launchOneShot(ModeState.CAPTURING) {
                captureAndDescribe(Mode.AUDIO_DESCRIPTION, describePrompt(), event) // FR-D1
            }
            Command.GuideMe -> launchOneShot(ModeState.SPEAKING) { guideMe(event) } // FR-N1/FR-N2
            Command.FindDoor -> launchOneShot(ModeState.CAPTURING) { findDoor(event) } // FR-N3
            Command.Repeat -> launchOneShot(ModeState.SPEAKING) { speaker.repeatLast() } // FR-T3
            Command.Slower -> launchOneShot(ModeState.SPEAKING) { speaker.repeatLast(Speaker.SLOWER_RATE) } // FR-T3
            // FR-G6: voice and recognition switch together; the FR-T4 re-prompt needs the retained still (P1)
            Command.InSpanish -> {
                speaker.setLocale(Speaker.ES_MX)
                commands.setLocale(Speaker.ES_MX)
            }
            Command.Brief -> describeVariant = DescribeVariant.BRIEF // FR-D3 (P1)
            Command.Detailed -> describeVariant = DescribeVariant.DETAILED // FR-D3 (P1)
            // TODO(FR-D2): re-run describe on the retained still with prompts.followUp(question) (P1)
            is Command.FollowUp -> Unit
            Command.Next -> launchOneShot(ModeState.CAPTURING) { // FR-T6: the next page or sign, same prompt
                captureAndDescribe(Mode.TEXT_READER, prompts.read, event)
            }
            is Command.SelectDoor -> Unit // TODO(FR-N7): choose among door candidates (P2)
            is Command.CloudDescribe -> launchOneShot(ModeState.SPEAKING) { setCloudDescribe(command.on) } // ADR-0010
            Command.WhereAmI -> launchOneShot(ModeState.INFERRING) { whereAmI(event) } // FR-N9
            Command.Stop -> Unit // handled on its own lane
        }
    }

    /**
     * FR-G2: the panel shows LISTENING while the bus listens, if nothing else is in progress. A-7 / RK-4: the
     * first UNAVAILABLE of a run is spoken once, after the welcome, on its own coroutine so this collector
     * never waits for speech.
     */
    private fun onListeningState(listening: ListeningState) {
        when (listening) {
            ListeningState.LISTENING -> _state.compareAndSet(ModeState.IDLE, ModeState.LISTENING)
            else -> _state.compareAndSet(ModeState.LISTENING, ModeState.IDLE)
        }
        if (listening == ListeningState.UNAVAILABLE && !voiceUnavailableAnnounced) {
            voiceUnavailableAnnounced = true
            runScope?.launch {
                welcomeJob?.join()
                say(phrasebook.voiceCommandsUnavailable)
            }
        }
    }

    // ── One-shots ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Sets [initialState] before the job runs, so the busy policy sees it with no race, and restores IDLE
     * after. Only the current one-shot restores IDLE: a job cancelled by [stop] finishes after a quick
     * restart has already set SPEAKING for the new welcome, and must not overwrite it.
     */
    private fun launchOneShot(initialState: ModeState, block: suspend () -> Unit) {
        val run = runScope ?: return
        _state.value = initialState
        // LAZY so `active` is assigned before the body can run on a multi-threaded dispatcher.
        lateinit var job: Job
        job = run.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                withContext(NonCancellable) { if (active === job) _state.value = ModeState.IDLE }
            }
        }
        active = job
        job.start()
    }

    /**
     * Augmented Read: an anchor where the wearer is looking (R3's `placeAtGaze`: depth along the gaze, else 1 m along
     * it) and the text pinned there, so the words appear by the sign. Runs beside the speech; no anchor (no XR, no
     * tracked head) simply means no card. The no-text sentinel is never pinned.
     */
    private fun pinReadText(text: String?) {
        if (text.isNullOrBlank() || text == prompts.noTextSentinel) return
        readPin = runScope?.launch {
            val anchor = runCatching { anchors.placeAtGaze() }.getOrNull() ?: return@launch
            _worldLabel.value = WorldLabel(anchor, WorldLabel.Kind.TEXT, text)
        }
    }

    /**
     * Read (Isaac, 2026-10-18): once the voice has finished, the text leaves the window and the room, so nothing
     * lingers in the wearer's view. Also after a Stop. "Repeat" still replays it from the Speaker.
     */
    private fun clearReadText() {
        readPin?.cancel()
        readPin = null
        if (_worldLabel.value?.kind == WorldLabel.Kind.TEXT) _worldLabel.value = null
        if (_transcriptKind.value == TranscriptKind.ANSWER) showTranscript(null)
    }

    /**
     * FR-T1 / FR-D1 / FR-N6: the capture → infer → speak flow shared by Read, Describe and the
     * arrival check. Text Reader target: first spoken word ≤ 6 s after capture; Describe ≤ 8 s.
     */
    private suspend fun captureAndDescribe(mode: Mode, prompt: String, event: CommandEvent?) {
        event?.let { markActionStarted(it) } // NFR: the state is already CAPTURING; the announcement is not latency
        if (mode != Mode.NAVIGATION) stopNavigation() // one mode at a time: Read and Describe end Navigation
        announceMode(mode) // FR-G3
        val wasRunning = pauseNavigation() // ADR-0004: only the arrival check still finds it running
        var still: Bitmap? = null
        try {
            _state.value = ModeState.CAPTURING
            speaker.earcon(Earcon.THINKING)
            still = frames.captureStill() // INV-9: this controller owns it from here
            // NFR latency: capture → first spoken word, for Read and Describe; the arrival prompt is not measured.
            val capturedAtNanos = if (event != null) System.nanoTime() else null
            _state.value = ModeState.INFERRING
            // FR-T1: an OCR model reads in well under a second; the VLM reads only when none is loaded.
            val lines = if (mode == Mode.TEXT_READER && prompt == prompts.read) readUnderCrosshair(still) else null
            val ocrText = when {
                lines == null -> null
                lines.isEmpty() -> prompts.noTextSentinel
                else -> lines.joinToString("\n")
            }
            if (ocrText == null) {
                speakWhileGenerating(mode, still, prompt, capturedAtNanos)
                if (mode == Mode.TEXT_READER) pinReadText(_transcript.value)
            } else {
                showTranscript(ocrText) // FR-T2
                _state.value = ModeState.SPEAKING
                pinReadText(ocrText) // in parallel with the speech, never before it
                sayTimed(ocrText, mode, capturedAtNanos)
            }
        } catch (e: CancellationException) {
            throw e // Stop, or shutdown: nothing to say
        } catch (e: Exception) {
            speakFailure(e) // InferenceException, timeout (wrapped), anything else a module threw
        } catch (e: NotImplementedError) {
            speakFailure(e) // skeleton stubs (Kotlin TODO() bodies) must not crash the app
        } finally {
            withContext(NonCancellable) {
                still?.let { release(it) } // INV-9
                if (wasRunning) navigation.resume() // ADR-0004
                if (mode == Mode.TEXT_READER && event != null) clearReadText() // the voice is done: clear the view
            }
        }
    }

    /**
     * Augmented Read: the text inside the reading crosshair ([ReadingFrame.stillBox]), so the wearer chooses what is
     * read by pointing their head. When the crosshair holds no text, the whole view is read after a short cue, so a
     * wearer who cannot aim still hears every sign in front of them. An engine without regions (`null`) reads the
     * whole frame as before, without the cue.
     */
    private suspend fun readUnderCrosshair(still: Bitmap): List<String>? {
        val framed = engine.readTextIn(still, ReadingFrame.stillBox)
        if (!framed.isNullOrEmpty()) return framed
        val whole = engine.readText(still) ?: return framed
        if (framed != null && whole.isNotEmpty()) say(phrasebook.readingWholeView)
        return whole
    }

    /**
     * ADR-0010: turn cloud Describe on or off and say so; "on" says that photos leave the headset and where they go.
     * A build without a cloud key says it is not set up and stays off.
     */
    private suspend fun setCloudDescribe(on: Boolean) {
        if (on && !engine.cloudDescribeAvailable) {
            _cloudDescribe.value = false
            say(phrasebook.cloudDescribeUnavailable)
            return
        }
        _cloudDescribe.value = on
        say(phrasebook.cloudDescribe(on))
    }

    /**
     * FR-N9: where the wearer is. The lookup (fix + tiles) runs under [WHERE_AM_I_TIMEOUT_MS] with the THINKING tick;
     * the answer is the cloud's wording when it speaks in time, else the template, and every other outcome is one
     * sentence that says what is wrong and how to fix it.
     */
    private suspend fun whereAmI(event: CommandEvent) {
        markActionStarted(event)
        speaker.earcon(Earcon.THINKING)
        try {
            val result = coroutineScope {
                val thinking = launchThinkingTicker()
                try {
                    withTimeout(WHERE_AM_I_TIMEOUT_MS) { places.whereAmI() }
                } finally {
                    thinking.cancel()
                }
            }
            when (result) {
                is WhereAmI.Found -> {
                    val prompt = prompts.whereAmI(result.surroundings)?.let { prompts.forLocale(it, speaker.locale.value) }
                    val worded = prompt != null && speakCloudWording(prompt, event) // SPEAKING from its first sentence
                    if (!worded) {
                        _state.value = ModeState.SPEAKING
                        val template = phrasebook.whereAmI(result)
                        showTranscript(template, TranscriptKind.WHERE_AM_I)
                        sayWhereAmI(template, event, WORDS_TEMPLATE)
                    }
                }
                WhereAmI.PermissionNeeded -> {
                    _state.value = ModeState.SPEAKING
                    sayNotice(phrasebook.whereAmI(result)) // what leaves the headset, before the dialog
                    _locationPermissionRequests.tryEmit(Unit)
                }
                else -> {
                    _state.value = ModeState.SPEAKING
                    sayNotice(phrasebook.whereAmI(result), event)
                }
            }
        } catch (e: TimeoutCancellationException) {
            speakFailure(InferenceException("where am I exceeded $WHERE_AM_I_TIMEOUT_MS ms", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speakFailure(e)
        }
    }

    /**
     * FR-N9: the cloud's wording, spoken sentence by sentence as it streams (as Describe does). Returns whether at least
     * one sentence was spoken; `false` (offline, no key, an error, or no sentence within [WORDING_FIRST_SENTENCE_MS])
     * means nothing was said and the caller speaks the template. A failure after the first sentence ends the answer
     * there and is recorded on [lastError].
     */
    private suspend fun speakCloudWording(prompt: String, event: CommandEvent): Boolean = coroutineScope {
        val sentences = Channel<String>(Channel.UNLIMITED)
        launch {
            var first = true
            for (sentence in sentences) {
                ensureActive()
                if (first) sayWhereAmI(sentence, event, WORDS_CLOUD) else say(sentence)
                first = false
            }
        }
        val thinking = launchThinkingTicker() // still INFERRING until the first sentence: never silent
        val spoken = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val splitter = SentenceSplitter()
        val generated = StringBuilder()
        fun onSegment(segment: String) {
            generated.append(segment)
            val sentence = segment.trim()
            if (sentence.isEmpty()) return
            if (spoken.incrementAndGet() == 1) {
                thinking.cancel()
                _state.value = ModeState.SPEAKING
            }
            showTranscript(generated.toString().trim(), TranscriptKind.WHERE_AM_I)
            sentences.trySend(sentence)
        }
        val collector = launch {
            try {
                withTimeout(InferenceEngine.VLM_TIMEOUT_MS) {
                    engine.cloudWording(prompt).collect { chunk -> splitter.push(chunk).forEach(::onSegment) }
                }
                onSegment(splitter.finish())
            } catch (e: TimeoutCancellationException) {
                failure.compareAndSet(null, InferenceException("cloud wording exceeded ${InferenceEngine.VLM_TIMEOUT_MS} ms", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure.compareAndSet(null, e)
            }
        }
        val deadline = launch {
            delay(WORDING_FIRST_SENTENCE_MS)
            if (spoken.get() == 0) {
                failure.compareAndSet(null, InferenceException("cloud wording: no sentence in $WORDING_FIRST_SENTENCE_MS ms"))
                collector.cancel()
            }
        }
        collector.join()
        deadline.cancel()
        thinking.cancel()
        sentences.close() // the speech coroutine finishes what is queued, then this scope returns
        failure.get()?.let { _lastError.value = it }
        spoken.get() > 0
    }

    /**
     * FR-N9: [say] [text] and, when it is the first words of the answer to [event], record command → first spoken word
     * ([whereAmILatency]). The watcher subscribes before speaking (UNDISPATCHED; `progress` has no replay).
     */
    private suspend fun sayWhereAmI(text: String, event: CommandEvent?, words: String) = coroutineScope {
        val watcher = event?.let { e ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                speaker.progress.first { !it.done && it.text == text }
                _whereAmILatency.value = WhereAmILatency(words, System.nanoTime() - e.recognizedAtNanos)
            }
        }
        try {
            say(text)
        } finally {
            watcher?.cancel()
        }
    }

    /** FR-N9: a one-sentence status (Location off, no fix, …), shown without the map credit and spoken. */
    private suspend fun sayNotice(text: String, event: CommandEvent? = null) {
        showTranscript(text, TranscriptKind.WHERE_AM_I_NOTICE)
        sayWhereAmI(text, event, WORDS_NOTICE)
    }

    /** FR-T2: every transcript write goes through here, so the UI knows what kind of answer it shows. */
    private fun showTranscript(text: String?, kind: TranscriptKind = TranscriptKind.ANSWER) {
        _transcriptKind.value = kind
        _transcript.value = text
    }

    /** FR-N3 / RK-6: two stills, two answers, agreement required; then anchor and guide. */
    private suspend fun findDoor(event: CommandEvent) {
        markActionStarted(event) // NFR: the state is already CAPTURING; the announcement is not latency
        endDoorWatch() // a new search replaces the old one
        announceMode(Mode.NAVIGATION)
        showTranscript(null) // a new search replaces the old words (an arrival detail, an earlier glance)
        // ADR-0006: on the phone there is no depth, no planes and no anchor, so nothing can guide; the search still
        // runs and ends in one honest glance (which third of the view the door is in) instead of guidance.
        val canGuide = ensureNavigationRunning()
        val wasRunning = canGuide && pauseNavigation()
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            _state.value = ModeState.CAPTURING
            speaker.earcon(Earcon.THINKING)
            first = frames.captureStill()
            second = frames.captureStill()
            _state.value = ModeState.INFERRING
            // Fast path: a detector that knows "door" answers in well under a second; the VLM vote (two
            // generations) runs only when it saw no door or is not loaded, and only if a VLM is loaded at all.
            val fromDetector = doorFromDetector(first, second)
            val bearing = when {
                fromDetector.bearing != null -> fromDetector.bearing
                fromDetector.ran && !engine.stats.value.vlmLoaded -> null
                else -> DoorVote.agree(
                    DoorVote.parse(inferWithWatchdog(first, prompts.door)),
                    DoorVote.parse(inferWithWatchdog(second, prompts.door)),
                )
            }
            _state.value = ModeState.SPEAKING
            if (!canGuide) {
                glanceAtDoor(bearing)
                return
            }
            val anchor = bearing?.let { anchors.placeFromBearing(it) }
            if (anchor == null) {
                watchForDoor() // nothing said: keep looking, speak only when a door is found
                return
            }
            guideToDoor(anchor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speakFailure(e)
        } catch (e: NotImplementedError) {
            speakFailure(e)
        } finally {
            withContext(NonCancellable) {
                first?.let { release(it) }
                second?.let { release(it) }
                if (wasRunning) navigation.resume()
            }
        }
    }

    /**
     * The phone's answer to "find the door" (ADR-0006, designs Phone v6 N1-N2): the door's third of the view is outlined
     * and said, with no distance, because none is measured there; or "no door, turn slowly" (FR-N3, RK-6 agreement).
     */
    private suspend fun glanceAtDoor(bearing: HorizontalBearing?) {
        if (bearing == null || bearing == HorizontalBearing.NONE) {
            overlay.highlightDoor(null)
            say(phrasebook.doorNotFound)
            return
        }
        val clock = when (bearing) {
            HorizontalBearing.LEFT -> 11
            HorizontalBearing.RIGHT -> 1
            else -> 12
        }
        overlay.highlightDoor(DoorHighlight.boxFor(clock, inView = true))
        val words = phrasebook.doorSeen(bearing)
        showTranscript(words.ifBlank { null })
        say(words)
    }

    /** R3 emits DoorBearing every 3 s, spoken in [onCue] with the spatial ping: that is how the wearer learns of it. */
    private suspend fun guideToDoor(anchor: AnchorId) {
        navigation.guideTo(anchor)
        _worldLabel.value = WorldLabel(anchor, WorldLabel.Kind.DOOR, text = "") // the marker at the door
    }

    /**
     * No door at the first look: watch the Navigation detector (≥ 5 fps, FR-N2) without a word until a door stays in
     * the same third of the view for [DOOR_WATCH_FRAMES] frames in a row (RK-6: agreement, not one frame), then anchor
     * and guide. A frame with no usable depth along that bearing just keeps the watch going.
     */
    private fun watchForDoor() {
        val run = runScope ?: return
        _lookingForDoor.value = true
        doorWatch = run.launch {
            try {
                var streak = 0
                var last: HorizontalBearing? = null
                navigation.detections.firstOrNull { detections ->
                    val bearing = DoorDetections.bearing(detections).takeIf { it != HorizontalBearing.NONE }
                    streak = when {
                        bearing == null -> 0
                        bearing == last -> streak + 1
                        else -> 1
                    }
                    last = bearing
                    if (bearing == null || streak < DOOR_WATCH_FRAMES) return@firstOrNull false
                    val anchor = placeDoor(bearing)
                    if (anchor == null) streak = 0 else guideToDoor(anchor)
                    anchor != null
                }
            } finally {
                _lookingForDoor.value = false
            }
        }
    }

    /** [AnchorStore.placeFromBearing], with a store failure read as "no usable depth" (the watch goes on). */
    private suspend fun placeDoor(bearing: HorizontalBearing): AnchorId? = try {
        anchors.placeFromBearing(bearing)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun endDoorWatch() {
        doorWatch?.cancel()
        doorWatch = null
        _lookingForDoor.value = false
    }

    /** What the detector said about two stills: [ran] false when it is not loaded or failed. */
    private class DetectorDoor(val ran: Boolean, val bearing: HorizontalBearing?)

    /** FR-N3 fast path: both stills must show a door in the same third (RK-6, as for the VLM vote). */
    private suspend fun doorFromDetector(first: Bitmap, second: Bitmap): DetectorDoor = try {
        val a = DoorDetections.bearing(engine.detect(first))
        val b = DoorDetections.bearing(engine.detect(second))
        DetectorDoor(ran = true, bearing = DoorVote.agree(a, b))
    } catch (e: CancellationException) {
        throw e
    } catch (e: InferenceException) {
        DetectorDoor(ran = false, bearing = null) // no detector: the VLM vote decides
    }

    /** "Guide me": Navigation mode on; proximity + outlines run in the background (FR-N1, FR-N2). */
    private suspend fun guideMe(event: CommandEvent) {
        markActionStarted(event)
        announceMode(Mode.NAVIGATION)
        if (!ensureNavigationRunning()) say(phrasebook.navigationUnavailable) // ADR-0006 (S25 Ultra)
    }

    /** FR-N6 (P1): one ARRIVAL prompt, then the target is cleared. */
    private suspend fun arrival() {
        captureAndDescribe(Mode.NAVIGATION, prompts.arrival, event = null)
        runCatching { navigation.clearTarget() }
        if (_worldLabel.value?.kind == WorldLabel.Kind.DOOR) _worldLabel.value = null
    }

    // ── Navigation cues ───────────────────────────────────────────────────────────────────────────

    /** R3 never builds strings: every cue goes through [ModePhrasebook.navigationCue] (INV-4). */
    private suspend fun onCue(cue: NavigationCue) {
        when (cue) {
            is NavigationCue.Proximity -> {
                speaker.earcon(Earcon.PROXIMITY) // FR-N1: rising tone, then the words; hazards first (SRS §3)
                phrasebook.navigationCue(cue)?.let { say(it, interrupt = true) }
            }
            is NavigationCue.DoorBearing -> {
                overlay.highlightDoor(DoorHighlight.boxFor(cue.clock, cue.inView)) // FR-N3/FR-N4
                if (!cue.pinged) speaker.earcon(Earcon.PING) // A-6 fallback: no spatial ping, so a flat one first
                phrasebook.navigationCue(cue)?.let { say(it) }
            }
            NavigationCue.Arrived -> {
                overlay.highlightDoor(null)
                phrasebook.navigationCue(cue)?.let { say(it) }
                if (active?.isActive != true) launchOneShot(ModeState.CAPTURING) { arrival() } // FR-N6
            }
            NavigationCue.TargetLost -> {
                overlay.highlightDoor(null)
                phrasebook.navigationCue(cue)?.let { say(it) }
            }
            is NavigationCue.WallAhead, is NavigationCue.FloorEnds -> {
                phrasebook.navigationCue(cue)?.let { say(it, interrupt = true) } // FR-N5: hazards first
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * INV-5: `describe` under [InferenceEngine.VLM_TIMEOUT_MS]; a THINKING earcon every
     * [THINKING_EARCON_PERIOD_MS] keeps the app from being silent for more than 10 s. A blank answer
     * is a failure: nothing is spoken that was not seen (SRS §3 rule 5).
     */
    private suspend fun inferWithWatchdog(still: Bitmap, prompt: String): String = coroutineScope {
        val thinking = launchThinkingTicker()
        try {
            val text = try {
                // FR-G6: the output language follows the voice (R2 exempts DOOR, whose allowlist is English).
                val localized = prompts.forLocale(prompt, speaker.locale.value)
                withTimeout(InferenceEngine.VLM_TIMEOUT_MS) { engine.describe(still, localized) }
            } catch (e: TimeoutCancellationException) {
                throw InferenceException("VLM exceeded ${InferenceEngine.VLM_TIMEOUT_MS} ms", e)
            }
            if (text.isBlank()) throw InferenceException("VLM returned no text")
            text
        } finally {
            thinking.cancel()
        }
    }

    /**
     * FR-T1 / FR-D1: speaks the VLM answer sentence by sentence while it is still generating, so the first word
     * waits for the first sentence instead of the whole answer (6.0 s prefill, then 36 tok/s on the headset). The
     * collector cuts the token stream with [SentenceSplitter] and hands each sentence to a speech coroutine through
     * an unlimited [Channel], so it never waits for speech; the transcript grows sentence by sentence (FR-T2) and
     * the state turns SPEAKING with the first one. INV-5: the stream runs under [InferenceEngine.VLM_TIMEOUT_MS]
     * (speech time is not counted) with the THINKING earcon until the first sentence. A stream with no text is a
     * failure (SRS §3 rule 5). Stop cancels this scope: the collection, which makes the engine stop the runner, and
     * the speech coroutine together.
     */
    private suspend fun speakWhileGenerating(mode: Mode, still: Bitmap, prompt: String, capturedAtNanos: Long?) =
        coroutineScope {
            val sentences = Channel<String>(Channel.UNLIMITED)
            launch {
                var first = true
                for (sentence in sentences) {
                    ensureActive() // a Stop that landed while this sentence was waiting wins over it (INV-6)
                    sayTimed(sentence, mode, capturedAtNanos.takeIf { first })
                    first = false
                }
            }
            val thinking = launchThinkingTicker()
            val splitter = SentenceSplitter()
            val generated = StringBuilder()
            var spoken = 0
            fun onSegment(segment: String) {
                generated.append(segment)
                val sentence = segment.trim()
                if (sentence.isEmpty()) return
                if (spoken++ == 0) {
                    thinking.cancel()
                    _state.value = ModeState.SPEAKING
                }
                showTranscript(generated.toString().trim()) // FR-T2
                sentences.trySend(sentence) // unlimited: never fails, never suspends
            }
            try {
                try {
                    // FR-G6: the output language follows the voice.
                    val localized = prompts.forLocale(prompt, speaker.locale.value)
                    // FR-D1 may answer from the cloud when the wearer turned it on (ADR-0010); the engine falls back.
                    val answer = if (mode == Mode.AUDIO_DESCRIPTION) {
                        engine.describeScene(still, localized, cloud = _cloudDescribe.value)
                    } else {
                        engine.describeStreaming(still, localized)
                    }
                    withTimeout(InferenceEngine.VLM_TIMEOUT_MS) {
                        answer.collect { chunk ->
                            splitter.push(chunk).forEach(::onSegment)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw InferenceException("VLM exceeded ${InferenceEngine.VLM_TIMEOUT_MS} ms", e)
                }
                onSegment(splitter.finish()) // the end of the stream ends the last sentence
                if (spoken == 0) throw InferenceException("VLM returned no text")
            } finally {
                thinking.cancel()
                sentences.close() // the speech coroutine finishes what is queued, then this scope returns
            }
        }

    /** Designs (Headset 3): never silent while the VLM works; a THINKING earcon every [THINKING_EARCON_PERIOD_MS]. */
    private fun CoroutineScope.launchThinkingTicker(): Job = launch {
        while (isActive) {
            delay(THINKING_EARCON_PERIOD_MS)
            speaker.earcon(Earcon.THINKING)
        }
    }

    /**
     * [say], and with [capturedAtNanos] also NFR latency (docs/nfr.md): `captureStill()` return → the first
     * `Speaker.progress` of [text], monotonic (H-21). The watcher subscribes before [text] is said (UNDISPATCHED;
     * `progress` has no replay) and is cancelled when the utterance ends, measured or not (interrupted first).
     */
    private suspend fun sayTimed(text: String, mode: Mode, capturedAtNanos: Long?) = coroutineScope {
        val watcher = capturedAtNanos?.let { t0 ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                speaker.progress.first { !it.done && it.text == text }
                _firstWordLatency.value = FirstWordLatency(mode, System.nanoTime() - t0)
            }
        }
        try {
            say(text)
        } finally {
            watcher?.cancel()
        }
    }

    /** NFR reliability: the error is spoken within 2 s and the state goes back to IDLE. */
    private suspend fun speakFailure(cause: Throwable) {
        _lastError.value = cause
        _state.value = ModeState.SPEAKING
        say(phrasebook.inferenceFailed, interrupt = true)
    }

    /** FR-G3: announce only when the mode actually changes. */
    private suspend fun announceMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        say(phrasebook.modeAnnounced(mode), interrupt = true)
    }

    /**
     * One mode at a time (Isaac, 2026-10-18): leaving Navigation for Read or Describe stops it outright (guidance, the
     * spatial ping, detector outlines and obstacle warnings) and removes the door marker, rather than pausing it to
     * resume after the answer. `NavigationEngine.stop()` also clears the door target.
     */
    private suspend fun stopNavigation() {
        endDoorWatch()
        if (!navigation.isRunning.value) return
        navigation.stop()
        overlay.clear()
        if (_worldLabel.value?.kind == WorldLabel.Kind.DOOR) _worldLabel.value = null
    }

    /** ADR-0004: returns whether Navigation was running (and is now paused), so the caller resumes it. */
    private suspend fun pauseNavigation(): Boolean {
        if (!navigation.isRunning.value) return false
        navigation.pause()
        return true
    }

    /** Starts Navigation if needed; `false` means the engine cannot run here (NoOp on the phone, ADR-0006). */
    private suspend fun ensureNavigationRunning(): Boolean {
        if (!navigation.isRunning.value) navigation.start()
        return navigation.isRunning.value
    }

    private fun describePrompt(): String = when (describeVariant) {
        DescribeVariant.BRIEF -> prompts.describeBrief ?: prompts.describe
        DescribeVariant.DETAILED -> prompts.describeDetailed ?: prompts.describe
        DescribeVariant.DEFAULT -> prompts.describe
    }

    /**
     * NFR latency: `recognizedAtNanos` → the state change to CAPTURING/LISTENING, monotonic (H-21). Called first
     * in each one-shot ([launchOneShot] already set the state), before any mode announcement is spoken.
     */
    private fun markActionStarted(event: CommandEvent) {
        _commandLatencyNanos.value = System.nanoTime() - event.recognizedAtNanos
    }

    /** INV-4: only [Speaker.say] speaks; a blank text means the phrasebook is not wired and is skipped. */
    private suspend fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        speaker.say(text, interrupt)
    }

    /** INV-9: the one owner recycles. */
    private fun release(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    /** SRS §3 "hazards first": these cues take the hazard lane and interrupt speech. */
    private fun NavigationCue.isHazard(): Boolean =
        this is NavigationCue.Proximity || this is NavigationCue.WallAhead || this is NavigationCue.FloorEnds

    private enum class DescribeVariant { DEFAULT, BRIEF, DETAILED }

    companion object {
        /**
         * Michelle Bui's designs (Headset 3 · Thinking): "Never silent more than 2 seconds: soft tick plus a
         * loading state", one soft tick every 1.5 s. Stricter than the SRS "never silent > 10 s". The Thinking
         * panel prints this same constant, so the screen and the sound cannot disagree.
         */
        const val THINKING_EARCON_PERIOD_MS: Long = 1_500

        /**
         * FR-N9: the whole lookup (a 10 s fix at worst, then up to four tiles with 3 s connect + 6 s read timeouts).
         * Past it the wearer hears [ModePhrasebook.inferenceFailed].
         */
        const val WHERE_AM_I_TIMEOUT_MS: Long = 25_000

        /**
         * The silent door watch anchors a door seen in the same third of the view in this many detector frames in a
         * row (about half a second at the ~11 fps measured on the headset), so one stray box never sends anyone off.
         */
        const val DOOR_WATCH_FRAMES: Int = 5

        /**
         * FR-N9: the cloud's first sentence must arrive within this, or the template answers. Gemini worded a place
         * in 1.35 s from a laptop (docs/roadmap-research.md §3); the headset number is in docs/benchmarks.md.
         */
        const val WORDING_FIRST_SENTENCE_MS: Long = 4_000

        /** [WhereAmILatency.words] values. */
        const val WORDS_CLOUD: String = "cloud"
        const val WORDS_TEMPLATE: String = "template"
        const val WORDS_NOTICE: String = "notice"

        /** A graph built without R3's place source: every "where am I" says there is no fix. */
        private val NO_PLACES: PlaceSource = object : PlaceSource {
            override suspend fun whereAmI(): WhereAmI = WhereAmI.NoFix
        }
    }
}

/** What [ModeController.transcript] holds (FR-T2): a Read / Describe answer, or FR-N9's answer or status sentence. */
enum class TranscriptKind { ANSWER, WHERE_AM_I, WHERE_AM_I_NOTICE }

/** FR-N9 evidence: "where am I" recognized → first spoken word, and who worded it (cloud, template or notice). */
data class WhereAmILatency(val words: String, val nanos: Long)

/** NFR latency: `captureStill()` return → the first spoken word of the answer, in [mode] (Read or Describe). */
data class FirstWordLatency(val mode: Mode, val nanos: Long)

/**
 * FR-T1 / FR-D1 streaming: cuts the VLM's token stream into sentences as they complete, so each can be spoken while
 * the rest is still generating. A sentence ends at ".", "!" or "?" followed by whitespace, or at a newline; the end
 * of the stream ends the last one ([finish]). A terminator with nothing after it yet waits for the next chunk, so a
 * decimal cut across chunks ("2." then "5 meters") stays whole. Segments keep their whitespace, so together they
 * are the answer as generated; the caller trims before speaking. Pure; unit-tested.
 */
internal class SentenceSplitter {

    private val buffer = StringBuilder()

    /** Appends [chunk] and returns the sentences it completed, in order. */
    fun push(chunk: String): List<String> {
        buffer.append(chunk)
        val done = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            val end = when {
                c == '\n' -> i + 1
                c in TERMINATORS && i + 1 < buffer.length && buffer[i + 1].isWhitespace() -> i + 2
                else -> -1
            }
            if (end < 0) {
                i++
                continue
            }
            done += buffer.substring(start, end)
            start = end
            i = end
        }
        buffer.delete(0, start)
        return done
    }

    /** The end of the stream: whatever is left, possibly blank. */
    fun finish(): String = buffer.toString().also { buffer.setLength(0) }

    private companion object {
        const val TERMINATORS = ".!?"
    }
}

/**
 * FR-N3 / RK-6: the allowlist parse of the DOOR answer, and the app's only door parser (plan.md Security:
 * "anything else = NONE"; `PromptLibrary.parseDoorAnswer` is reference only). Pure; unit-tested. The VLM is
 * asked for one word; anything else (two sides, prose with no side, an empty string, or a side word next to a
 * negation such as "There is no door on the left") is `null`, and `null` never agrees with anything, so the
 * user is never guided to a wall on a guess.
 */
internal object DoorVote {

    private val NON_LETTERS = Regex("[^a-z]+")

    private val allowed: Map<String, HorizontalBearing> = mapOf(
        "left" to HorizontalBearing.LEFT,
        "center" to HorizontalBearing.CENTER,
        "right" to HorizontalBearing.RIGHT,
        "none" to HorizontalBearing.NONE,
    )

    /** "n't" splits into "t" on [NON_LETTERS], so "isn't" / "don't" count as negations too. */
    private val NEGATIONS: Set<String> = setOf("no", "not", "nothing", "never", "without", "cannot", "t")

    /**
     * The single allowed word in [answer] (case-insensitive, punctuation ignored), or `null` if zero or several,
     * or if a side word comes with a negation (only NONE survives a negation: "No, none.").
     */
    fun parse(answer: String): HorizontalBearing? {
        val words = answer.lowercase().split(NON_LETTERS).filter { it.isNotEmpty() }
        val bearing = words.mapNotNull { allowed[it] }.distinct().singleOrNull() ?: return null
        if (bearing != HorizontalBearing.NONE && words.any { it in NEGATIONS }) return null
        return bearing
    }

    /** RK-6: both answers must exist, agree and not be NONE. */
    fun agree(first: HorizontalBearing?, second: HorizontalBearing?): HorizontalBearing? =
        if (first != null && first == second && first != HorizontalBearing.NONE) first else null
}

/**
 * FR-N3 fast path: the most confident "door" box in a detector's output, as the third of the frame its center falls
 * in (the same granularity as the VLM's answer and `AnchorStore.placeFromBearing`). [HorizontalBearing.NONE] when
 * there is no door at [MIN_CONFIDENCE] or above, which is always the case for a COCO detector (COCO has no door
 * class): the lockfile's `classes` must name one. Pure; unit-tested.
 */
internal object DoorDetections {
    const val LABEL: String = "door"
    const val MIN_CONFIDENCE: Float = 0.35f

    fun bearing(detections: List<Detection>): HorizontalBearing {
        val door = detections
            .filter { it.label.equals(LABEL, ignoreCase = true) && it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }
            ?: return HorizontalBearing.NONE
        val centerX = (door.box.left + door.box.right) / 2f
        return when {
            centerX < 1f / 3f -> HorizontalBearing.LEFT
            centerX > 2f / 3f -> HorizontalBearing.RIGHT
            else -> HorizontalBearing.CENTER
        }
    }
}

/**
 * FR-N3: where to draw the door highlight from a clock-face bearing. The engine reports hours, not
 * pixels, so the highlight is a vertical band in the left, center or right third of the view (the
 * same granularity as the VLM answer). Pure; unit-tested. `null` when the door is out of view (FR-N4).
 *
 * R3 sets `inView` for |relative bearing| ≤ 45° (`RelativeGeometry.IN_VIEW_HALF_ANGLE_DEG`) and rounds to
 * 30° per hour (`ClockFace`), so an in-view door is at 11, 12 or 1 o'clock (2 only at exactly +45°). 12 is
 * the center third (±15°), 11 the left third and 1 the right third, which is where R3 places a LEFT /
 * RIGHT answer (`BearingAngles` ±30° → 11 / 1 o'clock). 9, 10 and 2, 3 stay on their side for safety.
 */
internal object DoorHighlight {

    private const val BAND_TOP = 0.2f
    private const val BAND_BOTTOM = 0.8f
    private const val THIRD = 1f / 3f

    fun boxFor(clock: Int, inView: Boolean): NormalizedBox? {
        if (!inView) return null
        val third = when (clock) {
            12 -> 1 // straight ahead (±15°)
            9, 10, 11 -> 0 // left
            1, 2, 3 -> 2 // right
            else -> return null // behind the user: never in view
        }
        return NormalizedBox(
            left = third * THIRD,
            top = BAND_TOP,
            right = (third + 1) * THIRD,
            bottom = BAND_BOTTOM,
        )
    }
}

/** One thing pinned in the room by the augmented layer: a door marker or the text that was read, at [anchor]. */
data class WorldLabel(val anchor: AnchorId, val kind: Kind, val text: String) {
    enum class Kind { DOOR, TEXT }
}
