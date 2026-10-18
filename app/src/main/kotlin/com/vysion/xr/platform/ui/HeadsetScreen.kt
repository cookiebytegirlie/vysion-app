package com.vysion.xr.platform.ui

import com.vysion.xr.core.Command
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.platform.TranscriptKind

/**
 * Which of the designs' headset screens is showing (Michelle Bui, Headset 1-12). Derived, never stored: the
 * controller, the command bus and the navigation engine are the truth, and [HeadsetScreens.reduce] maps their
 * current values to one screen. Pure Kotlin, unit-tested in `HeadsetScreensTest`.
 */
sealed interface HeadsetScreen {
    /** Headset 1. */
    data object Ready : HeadsetScreen

    /** Headset 2. */
    data object Listening : HeadsetScreen

    /** Headset 3: capture and inference of a one-shot; [asked] is the command being answered. */
    data class Thinking(val asked: Command?) : HeadsetScreen

    /** Headset 6: the door vote is running. */
    data object LookingForDoor : HeadsetScreen

    /** Headset 4 (Read) and 5 (Describe): the VLM's answer, spoken and shown; on the phone also the door glance (ADR-0006). */
    data class Answer(val mode: Mode, val text: String) : HeadsetScreen

    /**
     * FR-N9 "where am I": the answer, or a one-sentence status (Location off, no fix). [mapCredit] is true when the text
     * came from map data, so the window shows "© OpenMapTiles, data from OpenStreetMap" under it.
     */
    data class Place(val text: String, val mapCredit: Boolean) : HeadsetScreen

    /** Headset 11: the READ prompt came back with the no-text sentinel; say what to do instead of guessing. */
    data object NoText : HeadsetScreen

    /** Headset 7: bearing and distance from the door anchor. */
    data class Guiding(val clock: Int, val distanceM: Float, val inView: Boolean) : HeadsetScreen

    /** Headset 8: anchor within 1 m; [detail] is the arrival answer once the VLM has given it. */
    data class Arrived(val detail: String?) : HeadsetScreen

    /** Headset 9: tracking of the door was lost; [lastClock] is where it was last. */
    data class Lost(val lastClock: Int?) : HeadsetScreen

    /** Headset 12: the last inference failed and nothing new was asked since. */
    data object Error : HeadsetScreen
}

/** Headset 10: a hazard that cuts in over whatever screen is showing. */
data class ObstacleAlert(val distanceM: Float)

/** The values [HeadsetScreens.reduce] reads. Times are monotonic milliseconds (`SystemClock.elapsedRealtime`). */
data class ScreenInput(
    val state: ModeState,
    val mode: Mode?,
    val transcript: String?,
    val listening: ListeningState,
    /** What [transcript] is: a Read / Describe answer or a "where am I" one (FR-N9). */
    val transcriptKind: TranscriptKind = TranscriptKind.ANSWER,
    /** "Find the door" is watching the detector for a door, silently (`ModeController.lookingForDoor`). */
    val lookingForDoor: Boolean = false,
    val lastCommand: Command? = null,
    val lastCommandAtMs: Long? = null,
    val lastErrorAtMs: Long? = null,
    val navCue: NavigationCue? = null,
    val navCueAtMs: Long? = null,
    val lastDoorClock: Int? = null,
    val hazard: NavigationCue? = null,
    val hazardAtMs: Long? = null,
    val hazardDismissedAtMs: Long? = null,
    val noTextSentinel: String? = null,
    val nowMs: Long = 0L,
)

object HeadsetScreens {
    /** A door bearing older than this means guidance stopped (bearings arrive every 3 s, FR-N3). */
    const val GUIDING_FRESH_MS: Long = 7_000

    /** How long the obstacle card stays after its cue (the cue repeats at most every 2 s while it holds, FR-N1). */
    const val HAZARD_SHOWN_MS: Long = 5_000

    fun reduce(input: ScreenInput): HeadsetScreen {
        if (input.listening == ListeningState.LISTENING) return HeadsetScreen.Listening

        if (input.state == ModeState.CAPTURING || input.state == ModeState.INFERRING) {
            return if (input.lastCommand == Command.FindDoor) {
                HeadsetScreen.LookingForDoor
            } else {
                HeadsetScreen.Thinking(input.lastCommand)
            }
        }

        if (errorIsCurrent(input)) return HeadsetScreen.Error

        navigationScreen(input)?.let { return it }

        if (input.lookingForDoor) return HeadsetScreen.LookingForDoor

        val text = input.transcript
        if (text != null && input.transcriptKind != TranscriptKind.ANSWER) {
            return HeadsetScreen.Place(text, mapCredit = input.transcriptKind == TranscriptKind.WHERE_AM_I)
        }
        if (text != null && input.mode != null) { // Read, Describe, or the phone's door glance (ADR-0006)
            return if (isNoText(text, input.noTextSentinel)) HeadsetScreen.NoText else HeadsetScreen.Answer(input.mode, text)
        }
        return HeadsetScreen.Ready
    }

    /** The obstacle card, while its cue is recent and the wearer has not pressed OK since. */
    fun obstacle(input: ScreenInput): ObstacleAlert? {
        val cue = input.hazard ?: return null
        val at = input.hazardAtMs ?: return null
        if (input.nowMs - at > HAZARD_SHOWN_MS) return null
        if ((input.hazardDismissedAtMs ?: Long.MIN_VALUE) >= at) return null
        return when (cue) {
            is NavigationCue.Proximity -> ObstacleAlert(cue.distanceM)
            is NavigationCue.WallAhead -> ObstacleAlert(cue.distanceM)
            is NavigationCue.FloorEnds -> ObstacleAlert(cue.distanceM)
            else -> null
        }
    }

    /** The sentinel the READ prompt asks for, compared without case, surrounding space or a final period. */
    fun isNoText(text: String, sentinel: String?): Boolean {
        if (sentinel.isNullOrBlank()) return false
        fun norm(s: String) = s.trim().trimEnd('.', '!').trim().lowercase()
        return norm(text) == norm(sentinel)
    }

    private fun errorIsCurrent(input: ScreenInput): Boolean {
        val errorAt = input.lastErrorAtMs ?: return false
        val commandAt = input.lastCommandAtMs ?: Long.MIN_VALUE
        return input.state == ModeState.IDLE && errorAt >= commandAt
    }

    private fun navigationScreen(input: ScreenInput): HeadsetScreen? {
        val cue = input.navCue ?: return null
        val at = input.navCueAtMs ?: return null
        // A cue from before the last command belongs to a finished task.
        if (input.lastCommandAtMs != null && at < input.lastCommandAtMs) return null
        return when (cue) {
            NavigationCue.Arrived -> HeadsetScreen.Arrived(input.transcript.takeIf { input.mode == Mode.NAVIGATION })
            NavigationCue.TargetLost -> HeadsetScreen.Lost(input.lastDoorClock)
            is NavigationCue.DoorBearing ->
                if (input.nowMs - at <= GUIDING_FRESH_MS) HeadsetScreen.Guiding(cue.clock, cue.distanceM, cue.inView) else null
            else -> null
        }
    }

    /**
     * The sentence of [text] that contains the spoken range [start, end): the designs light the phrase being
     * spoken, not just the word. Boundaries are sentence ends (. ! ? :) followed by a space.
     */
    fun spokenSentence(text: String, start: Int, end: Int): IntRange? {
        if (text.isEmpty() || start < 0 || start >= text.length) return null
        var from = start
        while (from > 0 && !isBoundary(text, from - 1)) from--
        // At least one character: the engine reports (0, 0) when an utterance starts, and to = 0 would read index -1
        // (StringIndexOutOfBoundsException on the main thread, crash on every spoken answer, headset 2026-10-17).
        var to = end.coerceIn(start + 1, text.length)
        while (to < text.length && !isBoundary(text, to - 1)) to++
        while (from < to && text[from].isWhitespace()) from++
        return from until to
    }

    private fun isBoundary(text: String, i: Int): Boolean =
        text[i] in ".!?:" && (i + 1 >= text.length || text[i + 1].isWhitespace())
}
