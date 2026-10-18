package com.vysion.xr.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  Vysion XR — cross-module contracts (SRS §6, §8; ADR-0005)
 *
 *  "Every module exposes a Kotlin Flow or a suspend fun; no module reaches into another's
 *   internals. The handoff table in section 8 is the contract."
 *
 *  This file IS that handoff table, as code. Each interface below is PROVIDED by exactly one
 *  role and CONSUMED by the others. Modules import from `com.vysion.xr.core` and from their own
 *  package only.
 *
 *  Changing a signature here is a cross-role change: say so in the merge channel, let R5 merge it
 *  alone, and update docs/contracts.md in the same commit.
 *
 *  Threading: every `suspend fun` is main-safe (callers may invoke it from any dispatcher; the
 *  implementation switches as needed). Every `Flow` is cold unless typed `StateFlow`.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Shared value types
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Axis-aligned box in normalized image coordinates: origin top-left, every value in 0..1.
 * Produced by R2 ([InferenceEngine.detect]), consumed by R1 ([OverlayRenderer]) and R3
 * ([NavigationEngine]). Normalized so the overlay never needs the detector's input size.
 */
data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "NormalizedBox values must be within 0..1: $this"
        }
        require(left <= right && top <= bottom) { "NormalizedBox must be ordered: $this" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** One detector hit. `label` is the COCO class name ("person", "chair", ...), `confidence` in 0..1. */
data class Detection(
    val label: String,
    val confidence: Float,
    val box: NormalizedBox,
)

/** Where the VLM says the door is (FR-N3). `NONE` means "no door in this frame". */
enum class HorizontalBearing { LEFT, CENTER, RIGHT, NONE }

/**
 * Metadata the diagnostics overlay prints (FR-G5). Every field is read from the model file or the
 * runtime, never typed in by hand (NFR "Honesty"). `backend` is the ExecuTorch delegate name,
 * e.g. "XNNPACK".
 */
data class ModelInfo(
    val name: String,
    val quantization: String,
    val backend: String,
    val fileSizeBytes: Long,
    val inputWidth: Int,
    val inputHeight: Int,
)

/** The three product modes (SRS §2). Shown on the control panel and announced on change (FR-G3). */
enum class Mode { TEXT_READER, AUDIO_DESCRIPTION, NAVIGATION }

/** [ModeController] states (SRS §6). Navigation runs as a background loop while `IDLE`. */
enum class ModeState { IDLE, LISTENING, CAPTURING, INFERRING, SPEAKING }

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// R1 · XR Platform — provides FrameSource, OverlayRenderer (and owns ModeController, the panels)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Measured at runtime from the camera stream; `null` until the first frame arrives (A-1). */
data class FrameStats(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val format: String,
    val fps: Float,
)

/**
 * The world-facing camera, two streams (SRS §6):
 * - [analysisFrames]: the 640x480 analysis stream for the detector (≥ 5 fps, FR-N2). Backed by
 *   CameraX `ImageAnalysis` with the latest-frame strategy; collectors should `conflate()`.
 * - [captureStill]: one on-demand square still, center-cropped, large enough for OCR on small print
 *   (`CameraFrameSource.DEFAULT_STILL_EDGE`; FR-T1, FR-D1). The inference engine fits it to its VLM's fixed input
 *   itself. Returns a `Bitmap` that the caller owns and must `recycle()` when done.
 *
 * Privacy (ADR-0003): frames exist only in memory. No implementation of this interface may write a
 * frame to storage or log its contents.
 */
interface FrameSource {
    val analysisFrames: Flow<Bitmap>
    val stats: StateFlow<FrameStats?>
    suspend fun start()
    suspend fun stop()
    suspend fun captureStill(): Bitmap
}

/** Draws detector outlines and the door highlight in passthrough from normalized boxes (FR-N2, FR-N3). */
interface OverlayRenderer {
    fun showDetections(detections: List<Detection>)
    fun highlightDoor(box: NormalizedBox?)
    fun clear()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// R2 · ML / ExecuTorch — provides InferenceEngine (and owns PromptLibrary, the .pte files)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Any failure inside the engine. The controller speaks an error within 2 s and returns to IDLE (NFR reliability). */
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Live counters for the overlay. `null` means "not measured yet" and renders as such (NFR "Honesty"). */
data class InferenceStats(
    val vlmLoaded: Boolean = false,
    val detectorLoaded: Boolean = false,
    val lastVlmMs: Long? = null,
    val medianVlmMs: Long? = null,
    val p90VlmMs: Long? = null,
    val vlmRuns: Int = 0,
    val lastDetectMs: Long? = null,
    /** Frames per second over a 5 s sliding window, measured inside [InferenceEngine.detect] (FR-N2). */
    val detectorFps: Float = 0f,
    /**
     * `System.nanoTime()` of the last successful [InferenceEngine.detect]; `null` until it ran. Lets a reader
     * age [detectorFps] out (0 when no detect happened for a whole window) instead of showing a stale value.
     */
    val lastDetectAtNanos: Long? = null,
)

/**
 * One engine, both models, one `Mutex` (ADR-0004). Inside [describe] the detector is unloaded and
 * reloaded after, so peak memory is one model plus the runtime. Nothing inside the engine calls
 * back into any other module; it returns data only.
 */
interface InferenceEngine {
    /** `null` until the model is loaded and its metadata read. */
    val vlmInfo: StateFlow<ModelInfo?>
    val detectorInfo: StateFlow<ModelInfo?>
    val stats: StateFlow<InferenceStats>

    /**
     * Copy `.pte` files to `filesDir` if needed (A-11) and load the models. Idempotent: returns at once when
     * already loaded. `ModeController.start()` calls it on every `onStart` (`AppGraph.stop` keeps the models).
     */
    suspend fun warmUp()

    /**
     * Run the VLM on one frame with one prompt from `PromptLibrary` (FR-T1, FR-D1, FR-N3, FR-N6).
     * Calling it again with the same [bitmap] instance must not recapture; implementations may
     * cache the prefill for follow-ups (FR-D2, FR-T4, RK-2 "prefill once").
     * @throws InferenceException on any failure, including a timeout.
     */
    suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): String

    /**
     * Optional token stream so the Speaker can start before generation ends (first spoken word ≤ 6 s,
     * FR-T1). The default wraps [describe]; R2 overrides it once the runner supports streaming.
     */
    fun describeStreaming(bitmap: Bitmap, prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): Flow<String> =
        flow { emit(describe(bitmap, prompt, maxNewTokens)) }

    /**
     * FR-D1 Describe, which may answer from a cloud model (ADR-0010 row "Cloud Describe") when [cloud] is true (the
     * wearer turned it on) and the headset is online. Offline, on any cloud error, or when the cloud is slower than
     * the headset, the answer comes from [describeStreaming], so the first spoken word stays within FR-D1's 8 s.
     * Same chunk stream as [describeStreaming]. The default never leaves the headset.
     */
    fun describeScene(
        bitmap: Bitmap,
        prompt: String,
        cloud: Boolean,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
    ): Flow<String> = describeStreaming(bitmap, prompt, maxNewTokens)

    /** ADR-0010: whether [describeScene] can reach a cloud model at all (this build has a key). Default `false`. */
    val cloudDescribeAvailable: Boolean get() = false

    /**
     * FR-N9 "where am I" (ADR-0010 row "Where am I wording"): a text-only answer to [prompt] from the cloud model,
     * streamed in chunks. Throws [InferenceException] (when collected) without a key, offline, or on any failure, and
     * the caller speaks its on-device template ([ModePhrasebook.whereAmI]) instead. Never touches the on-device models
     * or their mutex. The default has no cloud model.
     */
    fun cloudWording(prompt: String): Flow<String> = flow { throw InferenceException("no cloud model in this build") }

    /** Run the detector on one analysis frame (FR-N2). Must sustain ≥ 5 fps on the headset. */
    suspend fun detect(bitmap: Bitmap): List<Detection>

    /**
     * FR-T1 fast path: the text in [bitmap] from an OCR model, one entry per line in reading order; an empty list
     * means "no text". `null` means no OCR model is loaded, and the caller reads with [describe] and the READ
     * prompt instead. Same mutex as the other models (INV-1); [bitmap] is only read (INV-9).
     * @throws InferenceException when the OCR model is loaded but fails.
     */
    suspend fun readText(bitmap: Bitmap): List<String>? = null

    /**
     * Augmented Read (reading crosshair): the text inside [region] of [bitmap] only, one entry per line in reading
     * order, so the wearer chooses what is read by pointing their head. The region is cropped before detection, which
     * also gives small print more pixels. Same `[]` / `null` meaning as [readText]; the default `null` means "regions
     * not supported", and the caller reads the whole frame instead. [bitmap] is only read (INV-9).
     */
    suspend fun readTextIn(bitmap: Bitmap, region: NormalizedBox): List<String>? = null

    /** Unload everything. Called once from `AppGraph.release()` (`onDestroy` when finishing), never from `onStop`. */
    suspend fun release()

    companion object {
        /** RK-2 mitigation: cap output tokens at 60. */
        const val DEFAULT_MAX_NEW_TOKENS: Int = 60

        /** INFERRING watchdog (NFR reliability: never silent > 10 s; error spoken within 2 s). */
        const val VLM_TIMEOUT_MS: Long = 15_000
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// R3 · Spatial Perception — provides NavigationEngine, AnchorStore, HeadPoseSource
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What Navigation says (SRS §8 "NavigationCues flow: proximity, door bearing in clock-face form,
 * distance in meters rounded to 0.5"). R4 turns each cue into words in `Cues.kt`; R3 never
 * builds strings.
 */
sealed interface NavigationCue {
    /** FR-N1: depth in the forward 60° cone fell below 1.0 m. Emitted at most every 2 s. */
    data class Proximity(val distanceM: Float) : NavigationCue

    /**
     * FR-N3/FR-N4: the door anchor relative to the current head pose. `clock` in 1..12 ([ClockFace]).
     * `pinged` is false when the SceneCore spatial ping could not play (A-6 failed); the consumer then
     * plays `Earcon.PING` through the `Speaker` as the non-spatial fallback.
     */
    data class DoorBearing(
        val clock: Int,
        val distanceM: Float,
        val inView: Boolean,
        val pinged: Boolean = true,
    ) : NavigationCue

    /** FR-N5: a wall plane within 1.5 m on the heading. */
    data class WallAhead(val distanceM: Float) : NavigationCue

    /** FR-N5: the floor plane stops within 1.5 m. */
    data class FloorEnds(val distanceM: Float) : NavigationCue

    /** FR-N6: anchor within 1 m. The controller runs the ARRIVAL prompt once. */
    data object Arrived : NavigationCue

    /** Tracking of the target anchor was lost and could not be recovered. */
    data object TargetLost : NavigationCue
}

@JvmInline
value class AnchorId(val value: String)

/** An anchor relative to the current head pose. `bearingDeg`: 0 straight ahead, positive to the right. */
data class RelativeTarget(
    val bearingDeg: Float,
    val clock: Int,
    val distanceM: Float,
    val inView: Boolean,
)

/** Head pose from the XR runtime. `angularSpeedDegPerS` drives the "hold still" retry (FR-T5). */
data class HeadPose(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val angularSpeedDegPerS: Float,
    val timestampNanos: Long,
)

interface HeadPoseSource {
    val pose: StateFlow<HeadPose?>
}

/**
 * Eyes-free hand gestures (blind users cannot aim gaze at a button). Chosen so none collides with a Galaxy XR system
 * gesture: the system owns the index-finger pinch (select), the 2 s index pinch (options) and any pinch with the palm
 * facing the user (Home), so every gesture here is ignored while the palm faces the user and none uses the index.
 */
enum class HandGesture {
    /** Thumb to middle finger, held past the tap window: start listening. */
    TALK_START,

    /** The held middle-finger pinch released: send what was heard. */
    TALK_END,

    /** A quick thumb-to-middle tap: run the current page (Read, Describe or Find door). */
    ACTIVATE,

    /** A thumb-to-ring-finger tap: move to the next page and say its name. */
    NEXT_PAGE,

    /** Open hand, palm facing away, held still for half a second: stop. */
    STOP,
}

/** Provided by R3 from hand tracking; a no-op off the headset. */
interface HandGestureSource {
    val gestures: Flow<HandGesture>
}

/**
 * Holds the door target (SRS §8 R3 "AnchorStore: given a bearing (left/center/right from the VLM)
 * and a depth sample along it, create an anchor; expose bearing and distance from the current head
 * pose"). Anchors are native XR anchors so they persist out of view (FR-N4).
 */
interface AnchorStore {
    /** Sample depth along [bearing] from the current head pose and drop an anchor there. `null` if no usable depth. */
    suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId?

    /**
     * Augmented layer: an anchor where the wearer is looking (head yaw AND pitch, so a sign below eye level counts):
     * on the depth hit, else [fallbackDistanceM] along the gaze. `null` without a tracked head. Default `null`
     * (no XR) so other stores keep compiling.
     */
    suspend fun placeAtGaze(fallbackDistanceM: Float = 1.0f): AnchorId? = null
    fun track(id: AnchorId): Flow<RelativeTarget>
    suspend fun remove(id: AnchorId)
    suspend fun clear()
}

/**
 * The Navigation background loop: proximity at 10 Hz, detector at ≥ 5 fps, plane semantics, and
 * guidance to the anchor every 3 s with a spatial-audio ping (FR-N1..FR-N6). It runs only in Navigation mode: one
 * mode at a time, so Read and Describe [stop] it (Isaac, 2026-10-18). The one-shots that belong to Navigation (the
 * door vote, the arrival check) call [pause] before and [resume] after (ADR-0004), so the detector never competes
 * with the VLM.
 */
interface NavigationEngine {
    val cues: Flow<NavigationCue>

    /** Latest detector results for [OverlayRenderer]; empty while paused. */
    val detections: Flow<List<Detection>>
    val isRunning: StateFlow<Boolean>

    suspend fun start()
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()

    /** Start speaking bearing + distance every 3 s and pinging from the anchor (FR-N3). */
    suspend fun guideTo(anchor: AnchorId)
    suspend fun clearTarget()
}

/** A named street or place near the wearer: its name, its OpenMapTiles class or subclass ("cafe", "primary"), meters. */
data class NamedPlace(val name: String, val kind: String?, val distanceM: Float)

/**
 * FR-N9 "where am I", decoded on the headset from OpenFreeMap tiles around the approximate (Wi-Fi) location. Distances
 * are straight-line meters from that location. There are no directions: the headset has no public compass, so its
 * heading is unknown (docs/roadmap-research.md §3). Location and names are never logged or stored (ADR-0010).
 */
data class Surroundings(
    /** The nearest named street or footpath; `null` when the map around the wearer names none. */
    val street: NamedPlace?,
    /** The nearest named streets that cross [street], at most one on each side along it, nearest first. */
    val crossStreets: List<NamedPlace>,
    /** The nearest named places (shops, stops, buildings), at most three, nearest first, distinct names. */
    val places: List<NamedPlace>,
    /** Radius of the location fix in meters (`Location.accuracy`, 68 % confidence). */
    val accuracyM: Float,
)

/** What [PlaceSource.whereAmI] found out; every case has words in [ModePhrasebook.whereAmI]. */
sealed interface WhereAmI {
    data class Found(val surroundings: Surroundings) : WhereAmI

    /** Precise location is not granted. The controller explains, then asks once through the activity. */
    data object PermissionNeeded : WhereAmI

    /** The wearer refused precise location in the system dialog (or refused it for good earlier). */
    data object PermissionDenied : WhereAmI

    /** Location is off in Settings. */
    data object LocationOff : WhereAmI

    /** No fix in time: no Wi-Fi nearby, or no network for the Wi-Fi lookup. */
    data object NoFix : WhereAmI

    /** The map around the fix could not be downloaded and is not in memory. */
    data object NoMap : WhereAmI
}

/**
 * FR-N9: where the wearer is, from Android network location (Wi-Fi through Play services; the Galaxy XR has no GPS)
 * and OpenFreeMap vector tiles decoded on the device. Tiles stay in memory; nothing about the location is stored or
 * logged (ADR-0010 row "Where am I map").
 */
interface PlaceSource {
    suspend fun whereAmI(): WhereAmI
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// R4 · Voice & Accessibility UX — provides CommandBus, Speaker (and owns Cues.kt, the grammar)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The command grammar (FR-G2 + P1/P2 extensions). Fuzzy matching lives in R4's `CommandGrammar`. */
sealed interface Command {
    data object Read : Command
    data object Describe : Command
    data object GuideMe : Command
    data object FindDoor : Command
    data object Stop : Command
    data object Repeat : Command
    data object Slower : Command
    data object InSpanish : Command

    /** P2, FR-T6. */
    data object Next : Command

    /** P1, FR-D3. */
    data object Brief : Command
    data object Detailed : Command

    /** P1, FR-D2: runs against the same frame without recapture. */
    data class FollowUp(val question: String) : Command

    /** P2, FR-N7. */
    data class SelectDoor(val side: HorizontalBearing) : Command

    /** ADR-0010: "cloud on" / "cloud off" for Describe ([InferenceEngine.describeScene]); off at every launch. */
    data class CloudDescribe(val on: Boolean) : Command

    /** FR-N9: "where am I": the nearest street, its cross streets and the nearest named places ([PlaceSource]). */
    data object WhereAmI : Command
}

enum class ListeningState { IDLE, LISTENING, PROCESSING, UNAVAILABLE }

enum class CommandSource { VOICE, PINCH, PANEL, TEST }

/** `recognizedAtNanos` is `System.nanoTime()` at recognition, for the ≤ 1.5 s command-to-action NFR. */
data class CommandEvent(
    val command: Command,
    val source: CommandSource,
    val recognizedAtNanos: Long,
)

/**
 * Parsed commands as a flow (SRS §8 R4). Voice via on-device `SpeechRecognizer` (FR-G2); the mic
 * button and pinch-and-hold call [startListening]; panel buttons and tests call [inject].
 */
interface CommandBus {
    val commands: Flow<CommandEvent>
    val state: StateFlow<ListeningState>
    fun startListening()
    fun stopListening()
    fun inject(command: Command, source: CommandSource = CommandSource.PANEL)

    /**
     * Discard the current listening session without delivering what was heard (the toolbar's Cancel while
     * LISTENING). The default delivers instead, for buses that cannot discard; R4's bus overrides it.
     */
    fun cancelListening() = stopListening()

    /** FR-G6: recognition language follows "in Spanish". Default no-op for buses with no recognizer. */
    fun setLocale(locale: Locale) {}

    /** `SpeechRecognizer.destroy()` is mandatory; called once when the activity finishes. Default no-op. */
    fun release() {}
}

/** Non-speech sounds (SRS §8 R4 "earcons for listening, thinking, done, error"; PROXIMITY is the rising tone of FR-N1). */
enum class Earcon { LISTENING, THINKING, DONE, ERROR, PROXIMITY, PING }

/** Emitted as TTS progresses so the transcript panel can auto-scroll in sync (R4 panel spec). */
data class SpeechProgress(
    val utteranceId: String,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val done: Boolean,
)

/**
 * The only way anything in the app is spoken (FR-G4). Queue, interrupt, rate, locale (SRS §8 R4).
 * Every string passed to [say] comes from `Cues.kt` or from the VLM; nothing else builds sentences.
 */
interface Speaker {
    val progress: Flow<SpeechProgress>
    val locale: StateFlow<Locale>
    val isSpeaking: StateFlow<Boolean>

    /**
     * Speak [text]. Suspends until the utterance finishes or is interrupted. With [interrupt] the
     * queue is flushed first (hazards before descriptions, SRS §3).
     */
    suspend fun say(text: String, interrupt: Boolean = false)

    /** FR-G4: must silence output within [STOP_LATENCY_BUDGET_MS]. Non-suspending on purpose. */
    fun stop()
    fun earcon(kind: Earcon)

    /** 1.0 is normal; [SLOWER_RATE] for "slower" (FR-T3). */
    fun setRate(rate: Float)
    fun setLocale(locale: Locale)

    /** FR-T3: replay the last utterance, optionally at another rate. */
    suspend fun repeatLast(rate: Float = 1.0f)

    /** `TextToSpeech.shutdown()`; called once when the activity finishes. Default no-op. */
    fun release() {}

    companion object {
        val EN_US: Locale = Locale.forLanguageTag("en-US")
        val ES_MX: Locale = Locale.forLanguageTag("es-MX")
        const val SLOWER_RATE: Float = 0.7f
        const val STOP_LATENCY_BUDGET_MS: Long = 300
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Seams the ModeController (R1) speaks and prompts through, so `platform` never imports `voice` or
// `inference` (ADR-0005). R4 implements ModePhrasebook in voice/Cues.kt (CuesPhrasebook); R2
// implements PromptSet in inference/PromptLibrary.kt (LibraryPromptSet); AppGraph passes both.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Every sentence the controller speaks. R4 owns the words in `voice/Cues.kt` (INV-4). A blank value
 * is treated as "not wired" and skipped, never spoken.
 */
interface ModePhrasebook {
    /** FR-G1: the welcome that lists the three commands. */
    val welcome: String

    /** Busy policy: a non-Stop command arrived while CAPTURING/INFERRING. */
    val busy: String

    /** NFR reliability: any failed or timed-out inference. */
    val inferenceFailed: String

    /** RK-6 / FR-N3: the two door answers disagree or say "none". */
    val doorNotFound: String

    /** ADR-0006: Navigation asked for on a device whose engine never runs. */
    val navigationUnavailable: String

    /** FR-T5 (P1): head moving too fast at capture. */
    val holdStill: String

    /** FR-G1: spoken before the permission dialogs. */
    val permissionExplanation: String

    /** FR-G1: camera or microphone denied. */
    val permissionDenied: String

    /**
     * A-7 / RK-4: `CommandBus.state` became `UNAVAILABLE` (no on-device recognition); the panel buttons and
     * pinch still work. Spoken once per `ModeController.start()`. Default blank (= not wired, skipped).
     */
    val voiceCommandsUnavailable: String get() = ""

    /** docs/nfr.md fault model: "camera stream stops (no frame for 2 s)". Default blank (= not wired, skipped). */
    val cameraLost: String get() = ""

    /**
     * README troubleshooting row "App says model not found": a model file is missing or failed its lockfile
     * check. Default blank (= not wired, skipped).
     */
    val modelNotFound: String get() = ""

    /**
     * Augmented Read: nothing readable inside the reading crosshair, so the whole view is read next. Spoken before
     * that text. Default blank (= not wired, skipped).
     */
    val readingWholeView: String get() = ""

    /**
     * ADR-0010: spoken when the wearer turns cloud Describe on (it must say that photos leave the headset and where
     * they go) or off. Default blank (= not wired, skipped).
     */
    fun cloudDescribe(on: Boolean): String = ""

    /** ADR-0010: "cloud on" in a build with no cloud model ([InferenceEngine.cloudDescribeAvailable] false). Default blank. */
    val cloudDescribeUnavailable: String get() = ""

    /**
     * FR-N9: the words for every "where am I" outcome. [WhereAmI.Found] is the on-device template answer (used offline
     * or when the cloud wording fails); [WhereAmI.PermissionNeeded] is said before the location dialog and names what
     * leaves the headset; the others say what is wrong and how to fix it, in one sentence. Default blank.
     */
    fun whereAmI(result: WhereAmI): String = ""

    /**
     * ADR-0006, FR-N3 on a device with no depth or anchors (the phone): "find the door" answers with one honest glance,
     * which third of the view the door is in, instead of guidance. Default blank (= not wired, skipped).
     */
    fun doorSeen(bearing: HorizontalBearing): String = ""

    /** FR-G3: "<mode> mode" on every mode change. */
    fun modeAnnounced(mode: Mode): String

    /**
     * Eyes-free paging ([HandGesture.NEXT_PAGE]): the page's name and how to run it, for someone who cannot see the
     * rail ("Read page. Tap your middle finger to read."). Defaults to [modeAnnounced].
     */
    fun pageFocused(mode: Mode): String = modeAnnounced(mode)

    /**
     * FR-N1..FR-N5: words for a navigation cue ("door, 2 o'clock, 3 meters"); `null` means nothing to
     * say for this cue (for example [NavigationCue.Arrived], which triggers the ARRIVAL prompt).
     */
    fun navigationCue(cue: NavigationCue): String?

    /**
     * First-launch onboarding (designs pages 2-8): each page is read aloud, so its words live in R4's
     * `voice/Cues.kt` like every other spoken string and the UI displays the same text. Default blank.
     */
    fun onboarding(step: OnboardingStep): OnboardingPage = OnboardingPage(step = step, title = "", spoken = "")

    /** Onboarding page 4 practice: what Vysion says so the user can try stopping it. Default blank. */
    val stopPractice: String get() = ""

    /**
     * First-launch onboarding on a phone (designs "Phone v6 · End-to-end", section 1: six pages, three of them lessons
     * that wait for the gesture they teach). Shown and read aloud like [onboarding]. Default blank.
     */
    fun phoneOnboarding(step: PhoneOnboardingStep): PhoneOnboardingPage = PhoneOnboardingPage(step = step, title = "", spoken = "")

    /** Phone lesson 3: what Vysion says so the user can practice the two-finger double-tap that stops it. Default blank. */
    val phoneStopPractice: String get() = ""

    /** Blank placeholder for tests and for a graph built without R4; nothing is spoken. */
    object Unwired : ModePhrasebook {
        override val welcome: String = ""
        override val busy: String = ""
        override val inferenceFailed: String = ""
        override val doorNotFound: String = ""
        override val navigationUnavailable: String = ""
        override val holdStill: String = ""
        override val permissionExplanation: String = ""
        override val permissionDenied: String = ""
        override fun modeAnnounced(mode: Mode): String = ""
        override fun navigationCue(cue: NavigationCue): String? = null
    }
}

/**
 * The prompts the controller sends to the VLM. R2 owns the text in `inference/PromptLibrary.kt`
 * (SRS §7). The four P0 prompts are required; the P1 variants default to `null` (= not available yet).
 */
interface PromptSet {
    /** FR-T1. */
    val read: String

    /** FR-D1. */
    val describe: String

    /** FR-N3: the answer is one of left / center / right / none. */
    val door: String

    /** FR-N6. */
    val arrival: String

    /** FR-T4, P1. */
    val translateEs: String? get() = null

    /** FR-D3, P1. */
    val describeBrief: String? get() = null

    /** FR-D3, P1. */
    val describeDetailed: String? get() = null

    /** FR-D2, P1: a follow-up question about the same frame. */
    fun followUp(question: String): String? = null

    /** FR-N9: the text-only prompt that asks the cloud model to word [surroundings] ([InferenceEngine.cloudWording]). */
    fun whereAmI(surroundings: Surroundings): String? = null

    /**
     * The exact answer the READ prompt asks the VLM to give when it sees no text ("No text found"), so the UI
     * can show the "move closer" state instead of reading the sentinel as content. `null` = not known.
     */
    val noTextSentinel: String? get() = null

    /**
     * FR-G6 (P1): [prompt] adapted to the output [locale] (for example "Respond in Spanish." appended).
     * The default returns [prompt] unchanged; R2's implementation decides which prompts are exempt (DOOR).
     */
    fun forLocale(prompt: String, locale: Locale): String = prompt

    /** Blank placeholder for tests and for a graph built without R2. */
    object Unwired : PromptSet {
        override val read: String = ""
        override val describe: String = ""
        override val door: String = ""
        override val arrival: String = ""
    }
}

/** The seven first-launch pages, in order (designs pages 2-8). */
enum class OnboardingStep { WELCOME, PRIVACY, TALK, STOP, SOUNDS, SAFETY, ALL_SET }

/** One row on an onboarding page: a privacy promise, a sound, or a mode with its spoken example. */
data class OnboardingItem(val title: String, val detail: String? = null)

/**
 * The words of one onboarding page. [spoken] is what Vysion reads aloud on entering the page; the other
 * fields are the same words laid out for the eye. [hint] is the page's call-out (a practice prompt or the
 * cane reminder); [footnote] is the small line under the content.
 */
data class OnboardingPage(
    val step: OnboardingStep,
    val title: String,
    val body: String? = null,
    val items: List<OnboardingItem> = emptyList(),
    val hint: String? = null,
    val footnote: String? = null,
    val spoken: String,
)

/**
 * The six first-launch pages on a phone, in order (designs "Phone v6 · End-to-end", section 1). The three LEARN_* pages
 * are lessons that wait for the gesture they teach. A device runs this flow or [OnboardingStep], never both.
 */
enum class PhoneOnboardingStep { WELCOME, PERMISSIONS, LEARN_TAP, LEARN_SWIPE, LEARN_TALK_STOP, ALL_SET }

/**
 * The words of one phone onboarding page. [spoken] is read aloud on entering the page and is built from the fields on
 * screen; [hint] is the page's pill ("Tap anywhere to start"); [items] are the sound chips of the last page.
 */
data class PhoneOnboardingPage(
    val step: PhoneOnboardingStep,
    val title: String,
    val body: String? = null,
    val hint: String? = null,
    val items: List<OnboardingItem> = emptyList(),
    val spoken: String,
)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// R5 · Integration, Docs & Demo — provides DiagnosticsSource (the overlay reads this and nothing else)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Everything the diagnostics overlay shows (FR-G5, SRS §11 table). Every nullable field is `null`
 * until measured and renders as "not measured"; no default numbers anywhere (NFR "Honesty").
 */
data class DiagnosticsSnapshot(
    val vlm: ModelInfo? = null,
    val detector: ModelInfo? = null,
    /** ExecuTorch delegate name from the runtime or export metadata; expected "XNNPACK" (ADR-0002). */
    val backend: String? = null,
    val lastVlmMs: Long? = null,
    val medianVlmMs: Long? = null,
    val detectorFps: Float? = null,
    /** Total PSS of this process from `Debug.getPss()` (not rate-limited), sampled every second. */
    val appMemoryMb: Int? = null,
    /** Read from the manifest at runtime via `PackageManager`; prints "none" on the demo build. */
    val networkPermission: String? = null,
    val measuredAtMs: Long = 0L,
    /**
     * INV-2 monitor: whether the detector is resident (`InferenceStats.detectorLoaded`), `null` until the
     * detector's [ModelInfo] is known. Rendered inside the Model row ("resident" / "unloaded").
     */
    val detectorLoaded: Boolean? = null,
)

interface DiagnosticsSource {
    val snapshot: StateFlow<DiagnosticsSnapshot>
}
