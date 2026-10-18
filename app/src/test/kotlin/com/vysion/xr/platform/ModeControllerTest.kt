package com.vysion.xr.platform

import android.graphics.Bitmap
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Detection
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import com.vysion.xr.core.HeadPose
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NamedPlace
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.PlaceSource
import com.vysion.xr.core.PromptSet
import com.vysion.xr.core.RelativeTarget
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.SpeechProgress
import com.vysion.xr.core.Surroundings
import com.vysion.xr.core.WhereAmI
import com.vysion.xr.inference.FakeInferenceEngine
import com.vysion.xr.perception.FakePlaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM spec-as-tests for [ModeController] (plan.md F3 3.3, H-48: "the watchdog and the busy policy
 * have no test yet"). Uses R2's [FakeInferenceEngine] plus the tiny in-file fakes below; virtual
 * time from `kotlinx-coroutines-test` drives the 15 s watchdog (INV-5) and the 4 s thinking earcon.
 * No Android class is exercised except `Bitmap` as an opaque value (see [TestFrameSource.newBitmap]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeControllerTest {

    // ── FR-G1 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `welcome is spoken on start then the state returns to IDLE`() = controllerTest { f ->
        f.controller.start()
        runCurrent()
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        assertEquals(listOf(TestPhrasebook.welcome), f.speaker.said)
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertTrue("frame source started", f.frames.started)
        assertNotNull("engine warmed up (vlmInfo non-null after warmUp)", f.engine.vlmInfo.value)
    }

    // ── FR-T1 / FR-T2 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `read walks IDLE CAPTURING INFERRING SPEAKING IDLE and sets the transcript`() = controllerTest { f ->
        startAndSettle(f)
        val states = mutableListOf<ModeState>()
        // Unconfined: the collector runs inside every state write, so it records each transition. A
        // StandardTestDispatcher collector in backgroundScope misses the final IDLE, because
        // advanceUntilIdle() returns once only background work is left (kotlinx-coroutines-test ≥ 1.7).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.state.collect { states += it } }
        val shown = transcriptsOf(f)

        f.commands.inject(Command.Read)
        advanceUntilIdle()

        assertEquals(
            listOf(ModeState.IDLE, ModeState.CAPTURING, ModeState.INFERRING, ModeState.SPEAKING, ModeState.IDLE),
            states,
        )
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertTrue("shown while it is read aloud", READ_ANSWER in shown)
        assertNull("gone once the voice finished", f.controller.transcript.value)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)
        // FR-G3, then the text
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), READ_ANSWER), f.speaker.said)
        assertEquals(1, f.frames.captures)
        assertTrue("thinking earcon before capture", f.speaker.earcons.contains(Earcon.THINKING))
        assertNotNull("command→action latency measured", f.controller.commandLatencyNanos.value)
    }

    @Test
    fun `read uses the OCR lines when an OCR model is loaded and never asks the VLM`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedText = listOf("Meeting rooms", "Floor 2")
        val describesBefore = f.engine.describeCalls
        val shown = transcriptsOf(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()

        assertTrue("Meeting rooms\nFloor 2" in shown)
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), "Meeting rooms\nFloor 2"), f.speaker.said)
        assertEquals(1, f.engine.readTextCalls)
        assertEquals(describesBefore, f.engine.describeCalls)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `read takes the text under the crosshair first and skips the rest of the view`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedRegionText = listOf("EXIT")
        f.engine.cannedText = listOf("EXIT", "Wet floor", "Room 204")
        val shown = transcriptsOf(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()

        assertEquals(listOf(ReadingFrame.stillBox), f.engine.readRegions)
        assertTrue("EXIT" in shown)
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), "EXIT"), f.speaker.said)
        assertEquals(0, f.engine.readTextCalls)
    }

    @Test
    fun `nothing under the crosshair reads the whole view after saying so`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedRegionText = emptyList()
        f.engine.cannedText = listOf("Room 204")
        f.commands.inject(Command.Read)
        advanceUntilIdle()

        assertEquals(
            listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), TestPhrasebook.readingWholeView, "Room 204"),
            f.speaker.said,
        )
        assertEquals(1, f.engine.readTextCalls)

        // Nothing anywhere: no cue, just "no text".
        f.engine.cannedText = emptyList()
        f.speaker.said.clear()
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertTrue(f.speaker.said.none { it == TestPhrasebook.readingWholeView })
    }

    @Test
    fun `read pins the text in the room where the wearer looks, but never the no-text sentinel`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedText = listOf("Meeting rooms", "Floor 2")
        val labels = labelsOf(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        val label = labels.filterNotNull().last()
        assertNull("the card leaves with the voice", f.controller.worldLabel.value)
        assertEquals(WorldLabel.Kind.TEXT, label.kind)
        assertEquals("Meeting rooms\nFloor 2", label.text)
        assertEquals(f.anchors.lastId, label.anchor)
        assertEquals(1, f.anchors.gazePlacements)

        f.engine.cannedText = emptyList()
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(1, f.anchors.gazePlacements) // "No text found" is not pinned
    }

    @Test
    fun `find the door pins a door marker at its anchor, and stopping the app clears it`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "Left."
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertEquals(WorldLabel(f.anchors.lastId!!, WorldLabel.Kind.DOOR, ""), f.controller.worldLabel.value)
        f.controller.stop()
        assertNull(f.controller.worldLabel.value)
    }

    @Test
    fun `read with OCR that finds no text shows the no-text sentinel, and describe never runs OCR`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedText = emptyList()
        val shown = transcriptsOf(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertTrue(TestPrompts.noTextSentinel in shown)
        assertNull(f.controller.transcript.value)

        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(1, f.engine.readTextCalls)
        assertEquals(DESCRIBE_ANSWER, f.controller.transcript.value)
    }

    @Test
    fun `describe uses the describe prompt and announces the mode once`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(DESCRIBE_ANSWER, f.controller.transcript.value)
        assertEquals(
            listOf(TestPhrasebook.modeAnnounced(Mode.AUDIO_DESCRIPTION), DESCRIBE_ANSWER, DESCRIBE_ANSWER),
            f.speaker.said,
        )
    }

    @Test
    fun `next captures and reads again without announcing the mode twice (FR-T6)`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        f.commands.inject(Command.Next)
        advanceUntilIdle()
        assertEquals(2, f.frames.captures)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), READ_ANSWER, READ_ANSWER), f.speaker.said)
    }

    // ── ADR-0010 cloud Describe switch ───────────────────────────────────────────────────────────

    @Test
    fun `cloud on is said aloud, reaches Describe only, and is withdrawn when the headset comes off`() {
        lateinit var engine: CloudFlagEngine
        controllerTest(engineWrapper = { CloudFlagEngine(it, cloudDescribeAvailable = true).also { e -> engine = e } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.CloudDescribe(on = true))
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.cloudDescribe(true)), f.speaker.said)
            assertTrue(f.controller.cloudDescribe.value)

            f.commands.inject(Command.Describe)
            advanceUntilIdle()
            f.commands.inject(Command.Read) // Read never asks for the cloud
            advanceUntilIdle()
            assertEquals("only Describe goes through describeScene", listOf(true), engine.cloudFlags)

            f.controller.stop() // headset taken off: the next wearer has not said "cloud on"
            assertFalse(f.controller.cloudDescribe.value)
            f.controller.start(speakWelcome = false)
            advanceUntilIdle()
            f.commands.inject(Command.Describe)
            advanceUntilIdle()
            assertEquals(listOf(true, false), engine.cloudFlags)
        }
    }

    @Test
    fun `cloud off is said aloud and Describe stays on the headset`() {
        lateinit var engine: CloudFlagEngine
        controllerTest(engineWrapper = { CloudFlagEngine(it, cloudDescribeAvailable = true).also { e -> engine = e } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.CloudDescribe(on = true))
            advanceUntilIdle()
            f.commands.inject(Command.CloudDescribe(on = false))
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.cloudDescribe(true), TestPhrasebook.cloudDescribe(false)), f.speaker.said)
            assertFalse(f.controller.cloudDescribe.value)
            f.commands.inject(Command.Describe)
            advanceUntilIdle()
            assertEquals(listOf(false), engine.cloudFlags)
        }
    }

    @Test
    fun `cloud on in a build without a key says it is not set up and stays off`() {
        lateinit var engine: CloudFlagEngine
        controllerTest(engineWrapper = { CloudFlagEngine(it, cloudDescribeAvailable = false).also { e -> engine = e } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.CloudDescribe(on = true))
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.cloudDescribeUnavailable), f.speaker.said)
            assertFalse(f.controller.cloudDescribe.value)
            f.commands.inject(Command.Describe)
            advanceUntilIdle()
            assertEquals(listOf(false), engine.cloudFlags)
        }
    }

    // ── FR-T1 / FR-D1 streaming: speak while the VLM is still generating ─────────────────────────

    @Test
    fun `describe speaks each sentence as it completes while the VLM is still generating`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.streamChunks = listOf("The door", " is 2.", "5 meters", " ahead.", " A chair", " is on the left.")
        f.engine.streamChunkDelayMs = STREAM_CHUNK_MS
        val announced = TestPhrasebook.modeAnnounced(Mode.AUDIO_DESCRIPTION)
        val first = "The door is 2.5 meters ahead."
        val second = "A chair is on the left."
        f.commands.inject(Command.Describe)

        // Announcement, capture, prefill; then " A chair" (chunk 5) shows that "ahead." ended a sentence.
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + 4 * STREAM_CHUNK_MS + 1)
        assertEquals(listOf(announced, first), f.speaker.said)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        assertEquals(first, f.controller.transcript.value)
        assertEquals("one chunk still to generate", 5, f.engine.streamedChunks)

        // The last chunk lands while the first sentence is still being spoken: collection never waits for speech.
        advanceTimeBy(STREAM_CHUNK_MS)
        assertEquals(6, f.engine.streamedChunks)
        assertEquals("$first $second", f.controller.transcript.value)
        assertEquals(listOf(announced, first), f.speaker.said)

        advanceUntilIdle()
        assertEquals("two utterances, in order", listOf(announced, first, second), f.speaker.said)
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertEquals(0, f.engine.streamsCancelled)
    }

    @Test
    fun `stop mid-stream silences the speaker and cancels the generation`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.streamChunks = listOf("A hallway.", " A chair", " on the left.", " A trash can", " on the right.")
        f.engine.streamChunkDelayMs = STREAM_CHUNK_MS
        val announced = TestPhrasebook.modeAnnounced(Mode.AUDIO_DESCRIPTION)
        f.commands.inject(Command.Describe)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + STREAM_CHUNK_MS + 1)
        assertEquals(listOf(announced, "A hallway."), f.speaker.said)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)

        f.commands.inject(Command.Stop)
        runCurrent() // no virtual time passes: Stop waits for neither the stream nor the speech (INV-6)

        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertEquals(1, f.speaker.stopCalls)
        assertEquals("the generation was cancelled", 1, f.engine.streamsCancelled)
        advanceUntilIdle()
        assertEquals("nothing more is spoken", listOf(announced, "A hallway."), f.speaker.said)
        assertEquals(2, f.engine.streamedChunks)
        assertFalse(f.speaker.said.contains(TestPhrasebook.inferenceFailed))
    }

    @Test
    fun `a stream that never ends a sentence is spoken whole when it ends`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.streamChunks = listOf("a red", " chair", " by the window")
        f.engine.streamChunkDelayMs = STREAM_CHUNK_MS
        val announced = TestPhrasebook.modeAnnounced(Mode.AUDIO_DESCRIPTION)
        f.commands.inject(Command.Describe)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + STREAM_CHUNK_MS + 1)
        assertEquals("nothing spoken mid-sentence", listOf(announced), f.speaker.said)
        assertEquals(ModeState.INFERRING, f.controller.state.value)

        advanceUntilIdle()
        assertEquals(listOf(announced, "a red chair by the window"), f.speaker.said)
        assertEquals("a red chair by the window", f.controller.transcript.value)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `capture to first spoken word is measured for describe and read, not for the arrival prompt`() =
        controllerTest { f ->
            startAndSettle(f)
            f.navigation.cuesFlow.tryEmit(NavigationCue.Arrived)
            advanceUntilIdle()
            assertNull(f.controller.firstWordLatency.value)

            f.commands.inject(Command.Describe)
            advanceUntilIdle()
            val describe = f.controller.firstWordLatency.value
            assertEquals(Mode.AUDIO_DESCRIPTION, describe?.mode)
            assertTrue(describe!!.nanos >= 0)

            f.engine.cannedText = listOf("Room 101") // the OCR path is measured too
            f.commands.inject(Command.Read)
            advanceUntilIdle()
            assertEquals(Mode.TEXT_READER, f.controller.firstWordLatency.value?.mode)
        }

    // ── NFR reliability ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a failed inference speaks inferenceFailed and returns to IDLE`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.failNextDescribe = true
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertTrue(f.speaker.said.contains(TestPhrasebook.inferenceFailed))
        assertNull("no transcript from a failed call", f.controller.transcript.value)
        assertTrue(f.controller.lastError.value is InferenceException)
    }

    @Test
    fun `a hung inference hits the 15 s watchdog, keeps the thinking earcon going and speaks the failure`() =
        controllerTest { f ->
            startAndSettle(f)
            f.engine.hangNextDescribe = true
            f.commands.inject(Command.Read)
            advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
            assertEquals(ModeState.INFERRING, f.controller.state.value)
            val thinkingAtStart = f.speaker.earcons.count { it == Earcon.THINKING }

            advanceTimeBy(InferenceEngine.VLM_TIMEOUT_MS - 1)
            assertEquals("still inferring one ms before the watchdog", ModeState.INFERRING, f.controller.state.value)
            assertFalse(f.speaker.said.contains(TestPhrasebook.inferenceFailed))
            // Never silent > 2 s (designs, Headset 3): a THINKING tick every THINKING_EARCON_PERIOD_MS while inferring.
            val ticks = ((InferenceEngine.VLM_TIMEOUT_MS - 1) / ModeController.THINKING_EARCON_PERIOD_MS).toInt()
            assertEquals(thinkingAtStart + ticks, f.speaker.earcons.count { it == Earcon.THINKING })

            advanceTimeBy(2)
            runCurrent()
            assertTrue(
                "failure spoken within 2 s of the timeout",
                f.speaker.said.contains(TestPhrasebook.inferenceFailed),
            )
            advanceUntilIdle()
            assertEquals(ModeState.IDLE, f.controller.state.value)
            assertTrue(f.controller.lastError.value is InferenceException)
        }

    // ── FR-G4 / INV-6 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stop during INFERRING cancels the command and returns to IDLE without advancing time`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.hangNextDescribe = true
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
        assertEquals(ModeState.INFERRING, f.controller.state.value)

        f.commands.inject(Command.Stop)
        runCurrent() // no virtual time passes: the Stop lane does not wait for the VLM

        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertEquals(1, f.speaker.stopCalls)
        assertFalse("a cancelled command speaks nothing", f.speaker.said.contains(TestPhrasebook.inferenceFailed))
    }

    @Test
    fun `stop during SPEAKING silences the speaker`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + 1)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        f.commands.inject(Command.Stop)
        runCurrent()
        assertEquals(1, f.speaker.stopCalls)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    // ── Busy policy (plan.md Q3) ──────────────────────────────────────────────────────────────────

    @Test
    fun `a second command while INFERRING is dropped with the busy cue`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.hangNextDescribe = true
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
        assertEquals(ModeState.INFERRING, f.controller.state.value)

        f.commands.inject(Command.Describe)
        runCurrent()

        assertEquals(TestPhrasebook.busy, f.speaker.said.last())
        assertEquals(ModeState.INFERRING, f.controller.state.value)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)
        assertEquals("the dropped command captured nothing", 1, f.frames.captures)
    }

    @Test
    fun `a command while SPEAKING preempts the utterance`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + 1)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(1, f.speaker.stopCalls)
        assertEquals(Mode.AUDIO_DESCRIPTION, f.controller.mode.value)
        assertEquals(DESCRIBE_ANSWER, f.controller.transcript.value)
    }

    // ── FR-N3 / RK-6 door vote ────────────────────────────────────────────────────────────────────

    @Test
    fun `door vote with two agreeing answers places an anchor and guides to it`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "Left."
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()

        assertEquals(2, f.frames.captures)
        assertEquals(listOf(HorizontalBearing.LEFT), f.anchors.placed)
        assertEquals(listOf(f.anchors.lastId), f.navigation.guided)
        assertFalse(f.speaker.said.contains(TestPhrasebook.doorNotFound))
        assertEquals(Mode.NAVIGATION, f.controller.mode.value)
        assertTrue("navigation started for the mode", f.navigation.isRunning.value)
        assertEquals("paused once around the VLM (ADR-0004)", 1, f.navigation.pauseCount)
        assertEquals("resumed once after", 1, f.navigation.resumeCount)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `a detector that sees a door in both stills places the anchor without asking the VLM`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedDetections = listOf(Detection("door", 0.8f, NormalizedBox(0.70f, 0.10f, 0.90f, 0.95f)))
        val describesBefore = f.engine.describeCalls
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()

        assertEquals(listOf(HorizontalBearing.RIGHT), f.anchors.placed)
        assertEquals(listOf(f.anchors.lastId), f.navigation.guided)
        assertEquals("no VLM generation for the door", describesBefore, f.engine.describeCalls)
        assertEquals(1, f.navigation.resumeCount)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `door vote with disagreeing answers says nothing, places nothing and keeps looking`() =
        controllerTest(engineWrapper = { ScriptedDoorEngine(it, "left", "right") }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.FindDoor)
            advanceUntilIdle()

            assertEquals(2, f.frames.captures)
            assertTrue(f.anchors.placed.isEmpty())
            assertTrue(f.navigation.guided.isEmpty())
            assertEquals("only the mode is said", listOf(TestPhrasebook.modeAnnounced(Mode.NAVIGATION)), f.speaker.said)
            assertTrue(f.controller.lookingForDoor.value)
            assertEquals(ModeState.IDLE, f.controller.state.value)
            assertEquals(1, f.navigation.resumeCount)
        }

    @Test
    fun `door vote answering none twice says nothing and keeps looking`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "none"
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertTrue(f.anchors.placed.isEmpty())
        assertFalse(f.speaker.said.contains(TestPhrasebook.doorNotFound))
        assertTrue(f.controller.lookingForDoor.value)
    }

    @Test
    fun `a door the detector holds in one third for five frames is anchored and guided, with nothing said before`() =
        controllerTest { f ->
            startAndSettle(f)
            f.engine.cannedAnswers[TestPrompts.door] = "none"
            f.commands.inject(Command.FindDoor)
            advanceUntilIdle()
            assertTrue(f.controller.lookingForDoor.value)

            repeat(ModeController.DOOR_WATCH_FRAMES - 1) { f.navigation.detectionsFlow.tryEmit(listOf(LEFT_DOOR)) }
            advanceUntilIdle()
            f.navigation.detectionsFlow.tryEmit(emptyList()) // a frame without the door starts the count again
            advanceUntilIdle()
            assertTrue("not yet", f.anchors.placed.isEmpty())

            repeat(ModeController.DOOR_WATCH_FRAMES) { f.navigation.detectionsFlow.tryEmit(listOf(LEFT_DOOR)) }
            advanceUntilIdle()
            assertEquals(listOf(HorizontalBearing.LEFT), f.anchors.placed)
            assertEquals(listOf(f.anchors.lastId!!), f.navigation.guided)
            assertEquals(WorldLabel.Kind.DOOR, f.controller.worldLabel.value?.kind)
            assertFalse(f.controller.lookingForDoor.value)
            assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.NAVIGATION)), f.speaker.said)
        }

    @Test
    fun `stop, read or the headset coming off ends the silent door watch`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "none"
        for (end in listOf<() -> Unit>({ f.commands.inject(Command.Stop) }, { f.commands.inject(Command.Read) })) {
            f.commands.inject(Command.FindDoor)
            advanceUntilIdle()
            assertTrue(f.controller.lookingForDoor.value)
            end()
            advanceUntilIdle()
            assertFalse(f.controller.lookingForDoor.value)
            repeat(ModeController.DOOR_WATCH_FRAMES) { f.navigation.detectionsFlow.tryEmit(listOf(LEFT_DOOR)) }
            advanceUntilIdle()
            assertTrue(f.anchors.placed.isEmpty())
        }
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        f.controller.stop()
        assertFalse(f.controller.lookingForDoor.value)
    }

    @Test
    fun `door vote with no usable depth says nothing and keeps looking`() = controllerTest { f ->
        startAndSettle(f)
        f.anchors.failPlacement = true
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertEquals(listOf(HorizontalBearing.LEFT), f.anchors.placed)
        assertTrue(f.navigation.guided.isEmpty())
        assertFalse(f.speaker.said.contains(TestPhrasebook.doorNotFound))
        assertTrue(f.controller.lookingForDoor.value)
    }

    // ── Navigation (FR-N1, FR-N2, ADR-0004, ADR-0006) ─────────────────────────────────────────────

    @Test
    fun `one mode at a time - read after guide me stops navigation instead of pausing it`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.GuideMe)
        advanceUntilIdle()
        assertTrue(f.navigation.isRunning.value)
        assertEquals(Mode.NAVIGATION, f.controller.mode.value)

        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertFalse("Read ends Navigation", f.navigation.isRunning.value)
        assertEquals(1, f.navigation.stopCount)
        assertEquals("nothing to resume after the answer", 0, f.navigation.resumeCount)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)

        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertTrue("find the door starts it again", f.navigation.isRunning.value)
        assertEquals(Mode.NAVIGATION, f.controller.mode.value)
    }

    @Test
    fun `one mode at a time - describe during door guidance drops the door marker and the outlines`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertEquals(WorldLabel.Kind.DOOR, f.controller.worldLabel.value?.kind)
        f.navigation.detectionsFlow.tryEmit(listOf(Detection("chair", 0.5f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        assertEquals(1, f.overlay.detections.value.size)

        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertFalse(f.navigation.isRunning.value)
        assertNull("the door marker belongs to Navigation", f.controller.worldLabel.value)
        assertTrue("outlines cleared", f.overlay.detections.value.isEmpty())
        assertEquals(Mode.AUDIO_DESCRIPTION, f.controller.mode.value)
    }

    @Test
    fun `the arrival check stays in Navigation, paused and resumed around the VLM (ADR-0004)`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.GuideMe)
        advanceUntilIdle()
        f.navigation.cuesFlow.tryEmit(NavigationCue.Arrived)
        advanceUntilIdle()
        assertTrue(f.navigation.isRunning.value)
        assertEquals(1, f.navigation.pauseCount)
        assertEquals(1, f.navigation.resumeCount)
        assertEquals(0, f.navigation.stopCount)
    }

    @Test
    fun `find the door on a device without navigation says which side the door is on (ADR-0006 glance)`() = controllerTest { f ->
        f.navigation.available = false
        startAndSettle(f)
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        // The fixture's engine answers the same side twice (RK-6 agreement): the glance names that side, no distance.
        val glance = f.speaker.said.lastOrNull { it.startsWith("<cue:doorSeen:") }
        assertTrue(f.speaker.said.toString(), glance != null && !glance.contains("NONE"))
        assertFalse(f.speaker.said.contains(TestPhrasebook.navigationUnavailable))
        assertEquals(glance, f.controller.transcript.value) // shown and spoken are the same words
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `guide me on a device without navigation speaks navigationUnavailable`() = controllerTest { f ->
        f.navigation.available = false
        startAndSettle(f)
        f.commands.inject(Command.GuideMe)
        advanceUntilIdle()
        assertTrue(f.speaker.said.contains(TestPhrasebook.navigationUnavailable))
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `navigation cues are spoken through the phrasebook and drive the overlay`() = controllerTest { f ->
        startAndSettle(f)
        f.navigation.cuesFlow.tryEmit(NavigationCue.Proximity(0.8f))
        advanceUntilIdle()
        assertTrue(f.speaker.earcons.contains(Earcon.PROXIMITY))
        assertEquals(TestPhrasebook.navigationCue(NavigationCue.Proximity(0.8f)), f.speaker.said.last())

        f.navigation.cuesFlow.tryEmit(NavigationCue.DoorBearing(clock = 1, distanceM = 3f, inView = true))
        advanceUntilIdle()
        assertNotNull("door highlighted while in view", f.overlay.door.value)
        f.navigation.cuesFlow.tryEmit(NavigationCue.DoorBearing(clock = 1, distanceM = 3f, inView = false))
        advanceUntilIdle()
        assertNull("highlight cleared out of view (FR-N4)", f.overlay.door.value)

        f.navigation.detectionsFlow.tryEmit(listOf(Detection("person", 0.9f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        assertEquals(1, f.overlay.detections.value.size)
    }

    @Test
    fun `stop tears down collectors and clears the overlay`() = controllerTest { f ->
        startAndSettle(f)
        f.navigation.detectionsFlow.tryEmit(listOf(Detection("chair", 0.5f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        f.controller.stop()
        advanceUntilIdle()
        assertTrue(f.overlay.detections.value.isEmpty())
        assertFalse(f.navigation.isRunning.value)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals("no command handled after stop", 0, f.frames.captures)
    }

    @Test
    fun `a door bearing whose spatial ping failed plays the PING earcon before its words (A-6)`() =
        controllerTest { f ->
            startAndSettle(f)
            val pinged = NavigationCue.DoorBearing(clock = 2, distanceM = 3f, inView = true)
            f.navigation.cuesFlow.tryEmit(pinged)
            advanceUntilIdle()
            val wordsOnly = listOf<Any>(TestPhrasebook.navigationCue(pinged)!!)
            assertEquals("spatial ping played: words only", wordsOnly, f.speaker.events)

            f.speaker.events.clear()
            val unpinged = NavigationCue.DoorBearing(clock = 2, distanceM = 2.5f, inView = true, pinged = false)
            f.navigation.cuesFlow.tryEmit(unpinged)
            advanceUntilIdle()
            assertEquals(listOf(Earcon.PING, TestPhrasebook.navigationCue(unpinged)!!), f.speaker.events)
        }

    @Test
    fun `a hazard during a guidance utterance plays PROXIMITY without waiting for that utterance`() =
        controllerTest { f ->
            startAndSettle(f)
            val bearing = NavigationCue.DoorBearing(clock = 2, distanceM = 3f, inView = true)
            f.navigation.cuesFlow.tryEmit(bearing)
            advanceTimeBy(1) // the bearing's words are being spoken (SAY_MS = 100)
            runCurrent()
            assertEquals(listOf(TestPhrasebook.navigationCue(bearing)), f.speaker.said)

            val hazard = NavigationCue.Proximity(0.8f)
            f.navigation.cuesFlow.tryEmit(hazard)
            runCurrent() // no virtual time passes: the hazard lane does not wait behind the bearing (SRS §3)
            assertTrue(f.speaker.earcons.contains(Earcon.PROXIMITY))
            assertEquals(
                listOf(TestPhrasebook.navigationCue(bearing), TestPhrasebook.navigationCue(hazard)),
                f.speaker.said,
            )
        }

    @Test
    fun `arrived runs the arrival prompt once and shows its answer (FR-N6)`() = controllerTest { f ->
        startAndSettle(f)
        f.navigation.cuesFlow.tryEmit(NavigationCue.Arrived)
        advanceUntilIdle()
        assertEquals(TestPrompts.arrival, f.engine.lastPrompt)
        assertEquals(1, f.engine.describeCalls)
        assertEquals(1, f.frames.captures)
        assertEquals(ARRIVAL_ANSWER, f.controller.transcript.value)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `brief and detailed pick the describe variants (FR-D3)`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Brief)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(TestPrompts.describeBrief, f.engine.lastPrompt)
        f.commands.inject(Command.Detailed)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(TestPrompts.describeDetailed, f.engine.lastPrompt)
    }

    // ── A-7 / RK-4 ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `voice commands unavailable is spoken once per run, after the welcome`() = controllerTest { f ->
        f.commands.state.value = ListeningState.UNAVAILABLE
        f.controller.start()
        advanceUntilIdle()
        assertEquals(listOf(TestPhrasebook.welcome, TestPhrasebook.voiceCommandsUnavailable), f.speaker.said)

        f.commands.state.value = ListeningState.IDLE
        advanceUntilIdle()
        f.commands.state.value = ListeningState.UNAVAILABLE
        advanceUntilIdle()
        assertEquals("once per run", 1, f.speaker.said.count { it == TestPhrasebook.voiceCommandsUnavailable })

        f.controller.stop()
        f.speaker.said.clear()
        f.controller.start()
        advanceUntilIdle()
        assertEquals(listOf(TestPhrasebook.welcome, TestPhrasebook.voiceCommandsUnavailable), f.speaker.said)
    }

    // ── FR-G6 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `in Spanish switches the voice and the recognizer to es-MX together`() = controllerTest { f ->
        startAndSettle(f)
        assertNull("the bus keeps its own default until told", f.commands.lastLocale)
        f.commands.inject(Command.InSpanish)
        advanceUntilIdle()
        assertEquals(Speaker.ES_MX, f.speaker.locale.value)
        assertEquals(Speaker.ES_MX, f.commands.lastLocale)
        assertEquals(ModeState.IDLE, f.controller.state.value)

        // The prompt half of FR-G6: every prompt goes through PromptSet.forLocale with the new locale.
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(TestPrompts.read + TestPrompts.SPANISH_MARK, f.engine.lastPrompt)
    }

    // ── Lifecycle (AppGraph.stop/start follow onStop/onStart and repeat) ─────────────────────────

    @Test
    fun `stop then start restarts collectors and speaks the welcome again`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)

        // Headset off in the middle of a Read, then straight back on, with a camera teardown that takes time.
        f.frames.stopDelayMs = 20
        f.engine.hangNextDescribe = true
        f.commands.inject(Command.Read)
        advanceTimeBy(CAPTURE_MS + 1)
        assertEquals(ModeState.INFERRING, f.controller.state.value)
        f.controller.stop()
        assertNull("no mode while stopped: Navigation is off", f.controller.mode.value)
        f.speaker.said.clear()

        f.controller.start()
        runCurrent()
        assertEquals("the cancelled Read must not reset the new welcome", ModeState.SPEAKING, f.controller.state.value)
        assertEquals(listOf(TestPhrasebook.welcome), f.speaker.said)
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertEquals("the restart waits for the teardown", listOf("start", "stop", "start"), f.frames.lifecycle)

        // Every lane is live again: commands (mode announced anew), cues, detections, Stop.
        f.speaker.said.clear()
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), READ_ANSWER), f.speaker.said)
        assertNull("read, then cleared", f.controller.transcript.value)

        f.navigation.cuesFlow.tryEmit(NavigationCue.WallAhead(1.2f))
        advanceUntilIdle()
        assertEquals(TestPhrasebook.navigationCue(NavigationCue.WallAhead(1.2f)), f.speaker.said.last())

        f.navigation.detectionsFlow.tryEmit(listOf(Detection("chair", 0.5f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        assertEquals(1, f.overlay.detections.value.size)

        val stopsBefore = f.speaker.stopCalls
        f.commands.inject(Command.Stop)
        runCurrent()
        assertEquals(stopsBefore + 1, f.speaker.stopCalls)
    }

    // ── FR-N9 where am I ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `where am I speaks the cloud's wording sentence by sentence and credits the map`() {
        val places = FakePlaceSource(WhereAmI.Found(SURROUNDINGS))
        lateinit var engine: WordingEngine
        controllerTest(places = places, engineWrapper = { WordingEngine(it) { emit("You are near Larkin Street. "); emit("A library is close.") }.also { e -> engine = e } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceUntilIdle()
            assertEquals(listOf("You are near Larkin Street.", "A library is close."), f.speaker.said)
            assertEquals(listOf(TestPrompts.whereAmI(SURROUNDINGS)), engine.prompts)
            assertEquals("You are near Larkin Street. A library is close.", f.controller.transcript.value)
            assertEquals(TranscriptKind.WHERE_AM_I, f.controller.transcriptKind.value)
            assertEquals(ModeController.WORDS_CLOUD, f.controller.whereAmILatency.value?.words)
            assertNull("no mode change: where am I is not a mode", f.controller.mode.value)
            assertEquals(ModeState.IDLE, f.controller.state.value)
        }
    }

    @Test
    fun `where am I speaks the template when the cloud cannot word it (offline, no key)`() {
        val places = FakePlaceSource(WhereAmI.Found(SURROUNDINGS))
        controllerTest(places = places) { f -> // the fake engine has no cloud: cloudWording throws
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceUntilIdle()
            val template = TestPhrasebook.whereAmI(WhereAmI.Found(SURROUNDINGS))
            assertEquals(listOf(template), f.speaker.said)
            assertEquals(template, f.controller.transcript.value)
            assertEquals(TranscriptKind.WHERE_AM_I, f.controller.transcriptKind.value)
            assertEquals(ModeController.WORDS_TEMPLATE, f.controller.whereAmILatency.value?.words)
            assertTrue(f.speaker.earcons.contains(Earcon.THINKING))
        }
    }

    @Test
    fun `a cloud with no sentence in time loses to the template`() {
        val places = FakePlaceSource(WhereAmI.Found(SURROUNDINGS))
        controllerTest(places = places, engineWrapper = { WordingEngine(it) { delay(10_000); emit("Too late.") } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceTimeBy(ModeController.WORDING_FIRST_SENTENCE_MS - 1)
            assertEquals("still thinking while the cloud is quiet", ModeState.INFERRING, f.controller.state.value)
            assertTrue(f.speaker.said.isEmpty())
            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(TestPhrasebook.whereAmI(WhereAmI.Found(SURROUNDINGS))), f.speaker.said)
            advanceUntilIdle()
            assertFalse("the late cloud text is never spoken", f.speaker.said.contains("Too late."))
        }
    }

    @Test
    fun `location off is one sentence, shown without the map credit, and nothing goes to the cloud`() {
        val places = FakePlaceSource(WhereAmI.LocationOff)
        lateinit var engine: WordingEngine
        controllerTest(places = places, engineWrapper = { WordingEngine(it) { emit("x.") }.also { e -> engine = e } }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.whereAmI(WhereAmI.LocationOff)), f.speaker.said)
            assertEquals(TranscriptKind.WHERE_AM_I_NOTICE, f.controller.transcriptKind.value)
            assertTrue(engine.prompts.isEmpty())
        }
    }

    @Test
    fun `precise location is explained, asked for once, and a grant runs the lookup again`() {
        val places = FakePlaceSource(WhereAmI.PermissionNeeded)
        controllerTest(places = places) { f ->
            startAndSettle(f)
            val requests = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.locationPermissionRequests.collect { requests += it } }
            f.commands.inject(Command.WhereAmI)
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.whereAmI(WhereAmI.PermissionNeeded)), f.speaker.said)
            assertEquals(1, requests.size)

            places.result = WhereAmI.Found(SURROUNDINGS) // the wearer allowed it
            f.speaker.said.clear()
            f.controller.onLocationPermissionResult(granted = true)
            advanceUntilIdle()
            assertEquals(2, places.calls)
            assertEquals(listOf(TestPhrasebook.whereAmI(WhereAmI.Found(SURROUNDINGS))), f.speaker.said)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `a refused location dialog says how to allow it`() {
        controllerTest(places = FakePlaceSource(WhereAmI.PermissionNeeded)) { f ->
            startAndSettle(f)
            f.controller.onLocationPermissionResult(granted = false)
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.whereAmI(WhereAmI.PermissionDenied)), f.speaker.said)
        }
    }

    @Test
    fun `a hung lookup keeps ticking and speaks the failure at the timeout`() {
        val hung = object : PlaceSource {
            override suspend fun whereAmI(): WhereAmI = awaitCancellation()
        }
        controllerTest(places = hung) { f ->
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceTimeBy(ModeController.WHERE_AM_I_TIMEOUT_MS - 1)
            assertTrue(f.speaker.said.isEmpty())
            assertTrue("never silent while looking", f.speaker.earcons.count { it == Earcon.THINKING } > 10)
            advanceUntilIdle()
            assertEquals(listOf(TestPhrasebook.inferenceFailed), f.speaker.said)
            assertNotNull(f.controller.lastError.value)
        }
    }

    @Test
    fun `stop while looking cancels the lookup and says nothing`() {
        val hung = object : PlaceSource {
            override suspend fun whereAmI(): WhereAmI = awaitCancellation()
        }
        controllerTest(places = hung) { f ->
            startAndSettle(f)
            f.commands.inject(Command.WhereAmI)
            advanceTimeBy(2_000)
            f.commands.inject(Command.Stop)
            advanceUntilIdle()
            assertTrue(f.speaker.said.isEmpty())
            assertEquals(ModeState.IDLE, f.controller.state.value)
        }
    }

    // ── Harness ───────────────────────────────────────────────────────────────────────────────────

    private class Fixture(
        scope: CoroutineScope,
        engineWrapper: (FakeInferenceEngine) -> InferenceEngine,
        places: PlaceSource,
    ) {
        val frames = TestFrameSource(captureMs = CAPTURE_MS)
        val engine = FakeInferenceEngine(describeDelayMs = DESCRIBE_MS)
        val commands = TestCommandBus()
        val speaker = TestSpeaker(sayMs = SAY_MS)
        val navigation = TestNavigationEngine()
        val anchors = TestAnchorStore()
        val overlay = PassthroughOverlayRenderer()
        val controller = ModeController(
            frames = frames,
            engine = engineWrapper(engine),
            commands = commands,
            speaker = speaker,
            navigation = navigation,
            anchors = anchors,
            headPose = TestHeadPoseSource(),
            overlay = overlay,
            scope = scope,
            phrasebook = TestPhrasebook,
            prompts = TestPrompts,
            places = places,
        )

        init {
            engine.cannedAnswers[TestPrompts.read] = READ_ANSWER
            engine.cannedAnswers[TestPrompts.describe] = DESCRIBE_ANSWER
            engine.cannedAnswers[TestPrompts.door] = "left"
            engine.cannedAnswers[TestPrompts.arrival] = ARRIVAL_ANSWER
        }
    }

    private fun controllerTest(
        engineWrapper: (FakeInferenceEngine) -> InferenceEngine = { it },
        places: PlaceSource = FakePlaceSource(),
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val fixture = Fixture(scope, engineWrapper, places)
        try {
            body(fixture)
        } finally {
            fixture.controller.stop()
            scope.cancel()
        }
    }

    /** Every transcript the controller showed, in order (Unconfined: the collector sees each write). */
    private fun TestScope.transcriptsOf(f: Fixture): List<String?> = mutableListOf<String?>().also { list ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.transcript.collect { list += it } }
    }

    /** Every world label the controller pinned or cleared, in order. */
    private fun TestScope.labelsOf(f: Fixture): List<WorldLabel?> = mutableListOf<WorldLabel?>().also { list ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.worldLabel.collect { list += it } }
    }

    /** `start()` and let the welcome finish, so each test begins in IDLE with the collectors subscribed. */
    private fun TestScope.startAndSettle(f: Fixture) {
        f.controller.start()
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        f.speaker.said.clear()
        f.speaker.earcons.clear()
        f.speaker.events.clear()
    }

    private companion object {
        const val SAY_MS = 100L
        const val CAPTURE_MS = 10L
        const val DESCRIBE_MS = 50L
        const val STREAM_CHUNK_MS = 10L
        const val READ_ANSWER = "<vlm:read>"
        const val DESCRIBE_ANSWER = "<vlm:describe>"
        const val ARRIVAL_ANSWER = "<vlm:arrival>"

        /** An Open Images "Door" box centered in the left third (`DoorDetections`). */
        val LEFT_DOOR = Detection("Door", 0.9f, NormalizedBox(0.05f, 0.2f, 0.25f, 0.8f))
        val SURROUNDINGS = Surroundings(
            street = NamedPlace("Larkin Street", "secondary", 10f),
            crossStreets = listOf(NamedPlace("Grove Street", "minor", 50f), NamedPlace("Fulton Street", "tertiary", 53f)),
            places = listOf(NamedPlace("Main Library", "library", 20f)),
            accuracyM = 30f,
        )
    }
}

// ── Pure helpers ───────────────────────────────────────────────────────────────────────────────────

class SentenceSplitterTest {

    @Test
    fun `splits at a terminator followed by whitespace and at newlines, keeping the raw text`() {
        val splitter = SentenceSplitter()
        val text = "Wet floor! Is it open? Yes.\nEXIT\nPush bar"
        val done = splitter.push(text)
        assertEquals(listOf("Wet floor! ", "Is it open? ", "Yes.\n", "EXIT\n"), done)
        val rest = splitter.finish()
        assertEquals("Push bar", rest)
        assertEquals("nothing lost or added", text, (done + rest).joinToString(""))
    }

    @Test
    fun `a decimal is never split, even across chunks`() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.push("The door is 2."))
        assertEquals(emptyList<String>(), splitter.push("5 meters away"))
        assertEquals(listOf("The door is 2.5 meters away. "), splitter.push(". A chair"))
        assertEquals("A chair", splitter.finish())
    }

    @Test
    fun `a terminator at the end of a chunk waits for the next one, and finish empties the buffer`() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.push("Hello."))
        assertEquals(listOf("Hello. "), splitter.push(" World"))
        assertEquals("World", splitter.finish())
        assertEquals("", splitter.finish())
    }
}

class DoorVoteTest {

    @Test
    fun `parse accepts exactly one allowed word, any case, any punctuation`() {
        assertEquals(HorizontalBearing.LEFT, DoorVote.parse("left"))
        assertEquals(HorizontalBearing.CENTER, DoorVote.parse("Center."))
        assertEquals(HorizontalBearing.RIGHT, DoorVote.parse("  RIGHT\n"))
        assertEquals(HorizontalBearing.NONE, DoorVote.parse("none"))
        assertEquals(HorizontalBearing.LEFT, DoorVote.parse("The door is on the left."))
    }

    @Test
    fun `parse rejects prose with no side, two sides, or spelling variants`() {
        assertNull(DoorVote.parse(""))
        assertNull(DoorVote.parse("I think there might be a door somewhere"))
        assertNull(DoorVote.parse("left or right"))
        assertNull(DoorVote.parse("centre"))
        assertNull(DoorVote.parse("leftish"))
    }

    @Test
    fun `parse rejects a side word next to a negation, but keeps none`() {
        assertNull(DoorVote.parse("There is no door on the left."))
        assertNull(DoorVote.parse("Not on the right."))
        assertNull(DoorVote.parse("The door isn't on the left."))
        assertNull(DoorVote.parse("I cannot see a door at the center."))
        assertEquals(HorizontalBearing.NONE, DoorVote.parse("No, none."))
        assertEquals(HorizontalBearing.NONE, DoorVote.parse("None. There is no door."))
    }

    @Test
    fun `door detections pick the most confident door and report its third`() {
        fun door(left: Float, right: Float, conf: Float = 0.8f) = Detection("Door", conf, NormalizedBox(left, 0.1f, right, 0.9f))
        assertEquals(HorizontalBearing.LEFT, DoorDetections.bearing(listOf(door(0.05f, 0.25f))))
        assertEquals(HorizontalBearing.CENTER, DoorDetections.bearing(listOf(door(0.40f, 0.60f))))
        assertEquals(HorizontalBearing.RIGHT, DoorDetections.bearing(listOf(door(0.70f, 0.95f, 0.5f), door(0.05f, 0.2f, 0.4f))))
        assertEquals(HorizontalBearing.NONE, DoorDetections.bearing(listOf(door(0.4f, 0.6f, 0.2f)))) // below threshold
        val chair = Detection("chair", 0.9f, NormalizedBox(0.4f, 0.4f, 0.6f, 0.9f))
        assertEquals(HorizontalBearing.NONE, DoorDetections.bearing(listOf(chair))) // COCO has no door class
    }

    @Test
    fun `agree needs two identical non-none answers`() {
        assertEquals(HorizontalBearing.RIGHT, DoorVote.agree(HorizontalBearing.RIGHT, HorizontalBearing.RIGHT))
        assertNull(DoorVote.agree(HorizontalBearing.LEFT, HorizontalBearing.RIGHT))
        assertNull(DoorVote.agree(HorizontalBearing.NONE, HorizontalBearing.NONE))
        assertNull(DoorVote.agree(null, HorizontalBearing.LEFT))
        assertNull(DoorVote.agree(HorizontalBearing.LEFT, null))
        assertNull(DoorVote.agree(null, null))
    }

    @Test
    fun `door highlight is a third of the view by clock hour and null out of view or behind`() {
        val center = DoorHighlight.boxFor(12, true)!!
        assertEquals(1f / 3f, center.left, 1e-6f)
        assertEquals(2f / 3f, center.right, 1e-6f)
        assertEquals(0f, DoorHighlight.boxFor(10, true)!!.left, 1e-6f)
        assertEquals(1f, DoorHighlight.boxFor(2, true)!!.right, 1e-6f)
        assertNull(DoorHighlight.boxFor(12, false))
        assertNull(DoorHighlight.boxFor(6, true))
    }
}

class FrameMathTest {

    @Test
    fun `fps meter needs two frames and measures over the window`() {
        val meter = FpsMeter(windowNanos = 5_000_000_000L)
        assertNull(meter.onFrame(0L))
        assertEquals(5f, meter.onFrame(200_000_000L)!!, 1e-3f)
        assertEquals(5f, meter.onFrame(400_000_000L)!!, 1e-3f)
        // A 2 s gap, then frames again: the window averages over what it has seen.
        val fps = meter.onFrame(2_400_000_000L)!!
        assertTrue(fps < 5f && fps > 1f)
    }

    @Test
    fun `fps meter drops frames older than the window`() {
        val meter = FpsMeter(windowNanos = 1_000_000_000L)
        meter.onFrame(0L)
        meter.onFrame(100_000_000L)
        // 10 s later only this frame and the next count.
        assertNull(meter.onFrame(10_000_000_000L))
        assertEquals(10f, meter.onFrame(10_100_000_000L)!!, 1e-3f)
    }

    @Test
    fun `center crop keeps the target aspect and centers the rectangle`() {
        assertEquals(CropRect(left = 80, top = 0, width = 480, height = 480), CenterCrop.rect(640, 480, 896, 896))
        assertEquals(CropRect(left = 0, top = 80, width = 480, height = 480), CenterCrop.rect(480, 640, 896, 896))
        assertEquals(CropRect(left = 0, top = 0, width = 896, height = 896), CenterCrop.rect(896, 896, 896, 896))
        assertEquals(CropRect(left = 0, top = 160, width = 1280, height = 640), CenterCrop.rect(1280, 960, 640, 320))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `center crop rejects empty sizes`() {
        CenterCrop.rect(0, 480, 896, 896)
    }
}

// ── In-file fakes (R1 owns FrameSource/OverlayRenderer; the others are the minimum the controller needs) ──

/**
 * Captures return a real `Bitmap` instance so the controller can hand it to the engine and recycle it
 * (INV-9). The unit-test android.jar (`isReturnDefaultValues = true`) turns `Bitmap`'s package-private
 * constructor and `recycle()` into no-ops, so reflection is enough; no mocking library is needed.
 */
private class TestFrameSource(private val captureMs: Long) : FrameSource {
    var started = false
    var captures = 0

    /** "start" / "stop" in completion order, so a restart test can see that teardown finished first. */
    val lifecycle = mutableListOf<String>()

    /** Virtual time a `stop()` takes (the real one unbinds CameraX on the main thread). */
    var stopDelayMs = 0L
    override val analysisFrames: Flow<Bitmap> = emptyFlow()
    override val stats: StateFlow<FrameStats?> = MutableStateFlow(null)

    override suspend fun start() {
        started = true
        lifecycle += "start"
    }

    override suspend fun stop() {
        delay(stopDelayMs)
        lifecycle += "stop"
    }

    override suspend fun captureStill(): Bitmap {
        captures++
        delay(captureMs)
        return newBitmap()
    }

    private companion object {
        fun newBitmap(): Bitmap =
            Bitmap::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }
}

/** plan.md: `MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST)`. */
private class TestCommandBus : CommandBus {
    private val events =
        MutableSharedFlow<CommandEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val commands: Flow<CommandEvent> = events
    override val state = MutableStateFlow(ListeningState.IDLE)

    /** Last locale passed to [setLocale]; `null` until the controller sets one (FR-G6). */
    var lastLocale: Locale? = null

    override fun setLocale(locale: Locale) {
        lastLocale = locale
    }

    override fun startListening() {
        state.value = ListeningState.LISTENING
    }

    override fun stopListening() {
        state.value = ListeningState.IDLE
    }

    override fun inject(command: Command, source: CommandSource) {
        events.tryEmit(CommandEvent(command, source, System.nanoTime()))
    }
}

/**
 * Records everything; `say` takes [sayMs] of virtual time so SPEAKING is observable, and emits the
 * `onStart`-style progress `AndroidSpeaker` emits (`charStart = charEnd = 0`, not done).
 */
private class TestSpeaker(private val sayMs: Long) : Speaker {
    val said = mutableListOf<String>()
    val earcons = mutableListOf<Earcon>()

    /** Earcons and spoken texts interleaved in call order, for "earcon before words" assertions. */
    val events = mutableListOf<Any>()
    var stopCalls = 0
    override val progress = MutableSharedFlow<SpeechProgress>(extraBufferCapacity = 16)
    override val locale = MutableStateFlow(Speaker.EN_US)
    override val isSpeaking = MutableStateFlow(false)

    override suspend fun say(text: String, interrupt: Boolean) {
        said += text
        events += text
        progress.tryEmit(SpeechProgress("test-${said.size}", text, charStart = 0, charEnd = 0, done = false))
        isSpeaking.value = true
        try {
            delay(sayMs)
        } finally {
            isSpeaking.value = false
        }
    }

    override fun stop() {
        stopCalls++
    }

    override fun earcon(kind: Earcon) {
        earcons += kind
        events += kind
    }

    override fun setRate(rate: Float) = Unit

    override fun setLocale(locale: Locale) {
        this.locale.value = locale
    }

    override suspend fun repeatLast(rate: Float) {
        said.lastOrNull()?.let { say(it, interrupt = false) }
    }
}

private class TestNavigationEngine : NavigationEngine {
    /** `false` models the NoOp engine on the S25 Ultra: `start()` never makes it run (ADR-0006). */
    var available = true
    var pauseCount = 0
    var resumeCount = 0
    val guided = mutableListOf<AnchorId>()
    val cuesFlow = MutableSharedFlow<NavigationCue>(extraBufferCapacity = 8)
    val detectionsFlow = MutableSharedFlow<List<Detection>>(extraBufferCapacity = 8)
    override val cues: Flow<NavigationCue> = cuesFlow
    override val detections: Flow<List<Detection>> = detectionsFlow
    override val isRunning = MutableStateFlow(false)

    override suspend fun start() {
        if (available) isRunning.value = true
    }

    override suspend fun pause() {
        pauseCount++
    }

    override suspend fun resume() {
        resumeCount++
    }

    var stopCount = 0

    override suspend fun stop() {
        stopCount++
        isRunning.value = false
    }

    override suspend fun guideTo(anchor: AnchorId) {
        guided += anchor
    }

    override suspend fun clearTarget() = Unit
}

private class TestAnchorStore : AnchorStore {
    var failPlacement = false
    val placed = mutableListOf<HorizontalBearing>()
    var lastId: AnchorId? = null

    override suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId? {
        placed += bearing
        if (failPlacement) return null
        return AnchorId("door-${placed.size}").also { lastId = it }
    }

    var gazePlacements = 0

    override suspend fun placeAtGaze(fallbackDistanceM: Float): AnchorId? {
        gazePlacements++
        return AnchorId("gaze-$gazePlacements").also { lastId = it }
    }

    override fun track(id: AnchorId): Flow<RelativeTarget> = emptyFlow()
    override suspend fun remove(id: AnchorId) = Unit
    override suspend fun clear() = Unit
}

private class TestHeadPoseSource : HeadPoseSource {
    override val pose: StateFlow<HeadPose?> = MutableStateFlow(null)
}

/** Records the `cloud` flag of every [InferenceEngine.describeScene] (ADR-0010); answers from the fake. */
private class CloudFlagEngine(
    private val delegate: FakeInferenceEngine,
    override val cloudDescribeAvailable: Boolean,
) : InferenceEngine by delegate {
    val cloudFlags = mutableListOf<Boolean>()

    override fun describeScene(bitmap: Bitmap, prompt: String, cloud: Boolean, maxNewTokens: Int): Flow<String> {
        cloudFlags += cloud
        return delegate.describeStreaming(bitmap, prompt, maxNewTokens)
    }
}

/** FR-N9: a cloud that words "where am I" with [script]; records each prompt. Everything else is the fake's. */
private class WordingEngine(
    private val delegate: FakeInferenceEngine,
    private val script: suspend kotlinx.coroutines.flow.FlowCollector<String>.() -> Unit,
) : InferenceEngine by delegate {
    val prompts = mutableListOf<String>()

    override fun cloudWording(prompt: String): Flow<String> = flow {
        prompts += prompt
        script()
    }
}

/** Scripts the DOOR answers in call order on top of the fake, so the two vote calls can disagree. */
private class ScriptedDoorEngine(
    private val delegate: FakeInferenceEngine,
    vararg answers: String,
) : InferenceEngine by delegate {
    private val queue = ArrayDeque(answers.toList())

    override suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int): String {
        if (prompt == TestPrompts.door && queue.isNotEmpty()) delegate.cannedAnswers[prompt] = queue.removeFirst()
        return delegate.describe(bitmap, prompt, maxNewTokens)
    }
}

/** Test tokens, not product strings: the real words live in voice/Cues.kt (R4). */
private object TestPhrasebook : ModePhrasebook {
    override val welcome = "<cue:welcome>"
    override val busy = "<cue:busy>"
    override val inferenceFailed = "<cue:inferenceFailed>"
    override val readingWholeView = "<cue:readingWholeView>"
    override val doorNotFound = "<cue:doorNotFound>"
    override val navigationUnavailable = "<cue:navigationUnavailable>"
    override fun doorSeen(bearing: HorizontalBearing) = "<cue:doorSeen:$bearing>"
    override val holdStill = "<cue:holdStill>"
    override val permissionExplanation = "<cue:permissionExplanation>"
    override val permissionDenied = "<cue:permissionDenied>"
    override val voiceCommandsUnavailable = "<cue:voiceCommandsUnavailable>"
    override val cloudDescribeUnavailable = "<cue:cloudDescribeUnavailable>"
    override fun cloudDescribe(on: Boolean) = "<cue:cloudDescribe:$on>"
    override fun modeAnnounced(mode: Mode) = "<cue:mode:${mode.name}>"
    override fun navigationCue(cue: NavigationCue): String? =
        if (cue == NavigationCue.Arrived) null else "<cue:${cue::class.simpleName}>"
    override fun whereAmI(result: WhereAmI) = "<cue:whereAmI:${result::class.simpleName}>"
}

/** Test tokens, not prompts: the real text lives in inference/PromptLibrary.kt (R2). */
private object TestPrompts : PromptSet {
    override val read = "<prompt:read>"
    override val noTextSentinel = "<prompt:noTextSentinel>"
    override val describe = "<prompt:describe>"
    override val door = "<prompt:door>"
    override val arrival = "<prompt:arrival>"
    override val describeBrief = "<prompt:describeBrief>"
    override val describeDetailed = "<prompt:describeDetailed>"
    override fun whereAmI(surroundings: Surroundings) = "<prompt:whereAmI:${surroundings.street?.name}>"
    override fun forLocale(prompt: String, locale: Locale): String =
        if (locale.language == "es") prompt + SPANISH_MARK else prompt

    const val SPANISH_MARK = "<es>"
}
