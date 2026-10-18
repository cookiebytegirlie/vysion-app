package com.vysion.xr.platform.ui

import com.vysion.xr.core.Command
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.platform.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which designs screen shows for which signals (HeadsetScreen.kt). */
class HeadsetScreensTest {

    private val idle = ScreenInput(state = ModeState.IDLE, mode = null, transcript = null, listening = ListeningState.IDLE)

    @Test
    fun `nothing happening is Ready`() {
        assertEquals(HeadsetScreen.Ready, HeadsetScreens.reduce(idle))
    }

    @Test
    fun `a navigation transcript is an answer too (the phone's door glance, ADR-0006)`() {
        val input = idle.copy(mode = Mode.NAVIGATION, transcript = "Door on your left.")
        assertEquals(HeadsetScreen.Answer(Mode.NAVIGATION, "Door on your left."), HeadsetScreens.reduce(input))
    }

    @Test
    fun `listening wins over every other state`() {
        val input = idle.copy(listening = ListeningState.LISTENING, state = ModeState.INFERRING)
        assertEquals(HeadsetScreen.Listening, HeadsetScreens.reduce(input))
    }

    @Test
    fun `capturing and inferring are Thinking with the command being answered`() {
        val input = idle.copy(state = ModeState.INFERRING, lastCommand = Command.Describe, lastCommandAtMs = 10)
        assertEquals(HeadsetScreen.Thinking(Command.Describe), HeadsetScreens.reduce(input))
        assertEquals(HeadsetScreen.Thinking(Command.Read), HeadsetScreens.reduce(input.copy(state = ModeState.CAPTURING, lastCommand = Command.Read)))
    }

    @Test
    fun `the door vote is Looking for a door`() {
        val input = idle.copy(state = ModeState.CAPTURING, lastCommand = Command.FindDoor, lastCommandAtMs = 10)
        assertEquals(HeadsetScreen.LookingForDoor, HeadsetScreens.reduce(input))
    }

    @Test
    fun `an answer shows with its mode`() {
        val input = idle.copy(state = ModeState.SPEAKING, mode = Mode.TEXT_READER, transcript = "Meeting rooms are on floor 2.")
        assertEquals(HeadsetScreen.Answer(Mode.TEXT_READER, "Meeting rooms are on floor 2."), HeadsetScreens.reduce(input))
    }

    @Test
    fun `the no text sentinel becomes the move closer screen`() {
        val input = idle.copy(mode = Mode.TEXT_READER, transcript = " No text found. ", noTextSentinel = "No text found")
        assertEquals(HeadsetScreen.NoText, HeadsetScreens.reduce(input))
        assertTrue(HeadsetScreens.isNoText("no text found", "No text found."))
        assertFalse(HeadsetScreens.isNoText("No text found here, but a sign", "No text found"))
        assertFalse(HeadsetScreens.isNoText("No text found", null))
    }

    @Test
    fun `an error after the last command is shown until something new is asked`() {
        val failed = idle.copy(lastCommand = Command.Read, lastCommandAtMs = 100, lastErrorAtMs = 200)
        assertEquals(HeadsetScreen.Error, HeadsetScreens.reduce(failed))
        val askedAgain = failed.copy(lastCommandAtMs = 300)
        assertEquals(HeadsetScreen.Ready, HeadsetScreens.reduce(askedAgain))
    }

    @Test
    fun `a fresh door bearing is Guiding and a stale one is not`() {
        val cue = NavigationCue.DoorBearing(clock = 2, distanceM = 3f, inView = true)
        val input = idle.copy(mode = Mode.NAVIGATION, navCue = cue, navCueAtMs = 1_000, nowMs = 2_000)
        assertEquals(HeadsetScreen.Guiding(2, 3f, true), HeadsetScreens.reduce(input))
        assertEquals(HeadsetScreen.Ready, HeadsetScreens.reduce(input.copy(nowMs = 1_000 + HeadsetScreens.GUIDING_FRESH_MS + 1)))
    }

    @Test
    fun `arrived and lost persist until the next command`() {
        val arrived = idle.copy(mode = Mode.NAVIGATION, navCue = NavigationCue.Arrived, navCueAtMs = 1_000, nowMs = 60_000)
        assertEquals(HeadsetScreen.Arrived(null), HeadsetScreens.reduce(arrived))
        val withAnswer = arrived.copy(transcript = "Open, handle on the right.")
        assertEquals(HeadsetScreen.Arrived("Open, handle on the right."), HeadsetScreens.reduce(withAnswer))
        val lost = arrived.copy(navCue = NavigationCue.TargetLost, lastDoorClock = 12)
        assertEquals(HeadsetScreen.Lost(12), HeadsetScreens.reduce(lost))
        assertEquals(HeadsetScreen.Ready, HeadsetScreens.reduce(lost.copy(lastCommand = Command.Read, lastCommandAtMs = 2_000)))
    }

    @Test
    fun `the obstacle card shows for a recent hazard until OK`() {
        val hazard = idle.copy(hazard = NavigationCue.Proximity(0.8f), hazardAtMs = 1_000, nowMs = 2_000)
        assertEquals(ObstacleAlert(0.8f), HeadsetScreens.obstacle(hazard))
        assertNull(HeadsetScreens.obstacle(hazard.copy(nowMs = 1_000 + HeadsetScreens.HAZARD_SHOWN_MS + 1)))
        assertNull(HeadsetScreens.obstacle(hazard.copy(hazardDismissedAtMs = 1_500)))
        assertEquals(ObstacleAlert(1.2f), HeadsetScreens.obstacle(hazard.copy(hazard = NavigationCue.WallAhead(1.2f))))
    }

    @Test
    fun `the spoken sentence is the one containing the spoken word`() {
        val text = "Bags on the floor ahead, on your right. Tables with people on both sides."
        val first = HeadsetScreens.spokenSentence(text, 5, 9)!!
        assertEquals("Bags on the floor ahead, on your right.", text.substring(first))
        val second = HeadsetScreens.spokenSentence(text, 45, 52)!!
        assertEquals("Tables with people on both sides.", text.substring(second))
        assertNull(HeadsetScreens.spokenSentence("", 0, 0))
    }

    @Test
    fun `spoken sentence survives the zero-length range reported when an utterance starts`() {
        val text = "Meeting rooms are on floor 2. Also on 2: the main office."
        assertEquals(0 until 29, HeadsetScreens.spokenSentence(text, 0, 0))
        assertEquals(0 until 29, HeadsetScreens.spokenSentence(text, 0, 7))
        assertEquals(30 until 40, HeadsetScreens.spokenSentence(text, 30, 30))
        assertEquals(0 until 1, HeadsetScreens.spokenSentence("A", 0, 0))
    }

    @Test
    fun `a where am I answer shows with the map credit, its status sentences without it`() {
        val answer = idle.copy(mode = Mode.TEXT_READER, transcript = "You are near Larkin Street.", transcriptKind = TranscriptKind.WHERE_AM_I)
        assertEquals(HeadsetScreen.Place("You are near Larkin Street.", mapCredit = true), HeadsetScreens.reduce(answer))
        val notice = idle.copy(transcript = "Location is off.", transcriptKind = TranscriptKind.WHERE_AM_I_NOTICE)
        assertEquals(HeadsetScreen.Place("Location is off.", mapCredit = false), HeadsetScreens.reduce(notice))
        assertEquals(
            "while looking it is Thinking",
            HeadsetScreen.Thinking(Command.WhereAmI),
            HeadsetScreens.reduce(answer.copy(state = ModeState.INFERRING, lastCommand = Command.WhereAmI)),
        )
    }

    @Test
    fun `the silent door watch shows Looking for a door until guidance starts`() {
        assertEquals(HeadsetScreen.LookingForDoor, HeadsetScreens.reduce(idle.copy(mode = Mode.NAVIGATION, lookingForDoor = true)))
        val guiding = idle.copy(
            mode = Mode.NAVIGATION, lookingForDoor = true,
            navCue = NavigationCue.DoorBearing(12, 3f, true), navCueAtMs = 1_000, nowMs = 2_000,
        )
        assertEquals(HeadsetScreen.Guiding(12, 3f, true), HeadsetScreens.reduce(guiding))
    }
}
