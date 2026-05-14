package com.amwill.keeb.voice

import com.amwill.keeb.models.ModelSize
import com.amwill.keeb.models.WhisperModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputControllerTest {
    private val model = WhisperModel("fixture", "Fixture", ModelSize.Tiny, "file://fixture", 1, "00", minRamMb = 1, recommended = true)

    @Test fun reportsMissingModelBeforeRecording() {
        val controller = controller(hasPermission = true, transcript = "hello")
        assertTrue(controller.start(null) is VoiceState.MissingModel)
    }

    @Test fun reportsMissingPermission() {
        val controller = controller(hasPermission = false, transcript = "hello")
        assertTrue(controller.start(model) is VoiceState.MissingPermission)
    }

    @Test fun recordsTranscribesAndConsumesInsertion() {
        val controller = controller(hasPermission = true, transcript = " hello keyboard ")
        assertTrue(controller.start(model) is VoiceState.ReadyToInsert)
        assertEquals("hello keyboard", controller.consumeInsertion())
        assertEquals(VoiceState.Idle, controller.state)
    }

    @Test fun publishesRecordingAndProcessingStateChangesDuringStart() {
        val states = mutableListOf<VoiceState>()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1, 2, 3) },
            transcriber = FakeLocalTranscriber("hello"),
            stateListener = { states += it },
        )

        controller.start(model)

        assertEquals(
            listOf(VoiceState.Recording, VoiceState.Processing, VoiceState.ReadyToInsert("hello")),
            states,
        )
    }

    @Test fun pushToTalkStopTriggersTranscriptionAfterRecorderStops() {
        val recorder = StopAwaitingRecorder()
        val transcriber = CancelAwareTranscriber()
        val states = mutableListOf<VoiceState>()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = recorder,
            transcriber = transcriber,
            stateListener = { states += it },
        )
        val result = AtomicReference<VoiceState>()
        val worker = Thread { result.set(controller.start(model)) }

        worker.start()
        assertTrue(recorder.awaitStarted())
        assertEquals(VoiceState.Recording, controller.state)
        assertFalse(transcriber.transcribeCalled)

        controller.stopRecording()
        worker.join(1_000)

        assertFalse(worker.isAlive)
        assertTrue(recorder.stopCalled)
        assertTrue(transcriber.transcribeCalled)
        assertEquals(VoiceState.ReadyToInsert("hello"), result.get())
        assertEquals(
            listOf(VoiceState.Recording, VoiceState.Processing, VoiceState.ReadyToInsert("hello")),
            states,
        )
    }

    @Test fun insertionFormatterTrimsAndAddsTrailingSpace() {
        assertEquals("hello keyboard ", VoiceInsertionFormatter.format(" hello keyboard "))
        assertEquals("", VoiceInsertionFormatter.format("   "))
    }

    @Test fun emptyRecordingReturnsRetryableErrorBeforeTranscribing() {
        val transcriber = CancelAwareTranscriber()
        val states = mutableListOf<VoiceState>()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf() },
            transcriber = transcriber,
            stateListener = { states += it },
        )

        val result = controller.start(model)

        assertEquals(VoiceState.Error("No speech captured. Try again."), result)
        assertFalse(transcriber.transcribeCalled)
        assertEquals(listOf(VoiceState.Recording, VoiceState.Error("No speech captured. Try again.")), states)
    }

    @Test fun emptyTranscriptReturnsNoSpeechError() {
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1, 2, 3) },
            transcriber = FakeLocalTranscriber("   "),
        )

        val result = controller.start(model)

        assertEquals(VoiceState.Error("No speech was transcribed. Try again."), result)
    }

    @Test fun transcriptionHangTimesOutAndCancelsNativeRuntime() {
        val transcriber = BlockingTranscriber()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1, 2, 3) },
            transcriber = transcriber,
            transcriptionTimeoutMillis = 75,
            watchdogPollMillis = 10,
        )

        val result = controller.start(model)

        assertTrue(result is VoiceState.Error)
        assertTrue((result as VoiceState.Error).message.contains("timed out"))
        assertTrue(transcriber.awaitTranscribeStarted())
        assertTrue(transcriber.cancelCalled)
    }

    @Test fun cancelDuringNativeHangReturnsCanceledInsteadOfLeavingProcessingState() {
        val transcriber = BlockingTranscriber()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1, 2, 3) },
            transcriber = transcriber,
            transcriptionTimeoutMillis = 5_000,
            watchdogPollMillis = 10,
        )
        val result = AtomicReference<VoiceState>()
        val worker = Thread { result.set(controller.start(model)) }

        worker.start()
        assertTrue(transcriber.awaitTranscribeStarted())
        assertEquals(VoiceState.Processing, controller.state)
        controller.cancel()
        worker.join(1_000)

        assertFalse(worker.isAlive)
        assertEquals(VoiceState.Canceled, result.get())
        assertTrue(transcriber.cancelCalled)
    }

    @Test fun cancellationIsDistinctFromTranscriptionErrorsAndForwardsToRuntimePieces() {
        val recorder = CancelAwareRecorder()
        val transcriber = CancelAwareTranscriber()
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = recorder,
            transcriber = transcriber,
        )

        controller.cancel()

        assertTrue(recorder.cancelCalled)
        assertTrue(transcriber.cancelCalled)
        assertEquals(VoiceState.Canceled, controller.state)
    }

    @Test fun recorderCancellationExceptionBecomesCanceledState() {
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16(): ShortArray = throw VoiceInputCanceledException() },
            transcriber = FakeLocalTranscriber("ignored"),
        )

        assertEquals(VoiceState.Canceled, controller.start(model))
    }

    private fun controller(hasPermission: Boolean, transcript: String) = VoiceInputController(
        permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = hasPermission },
        recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1, 2, 3) },
        transcriber = FakeLocalTranscriber(transcript),
    )

    private class CancelAwareRecorder : AudioRecorder {
        var cancelCalled = false
        override fun recordPcm16(): ShortArray = shortArrayOf(1, 2, 3)
        override fun cancel() { cancelCalled = true }
    }

    private class StopAwaitingRecorder : AudioRecorder {
        private val started = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        var stopCalled = false

        override fun recordPcm16(): ShortArray {
            started.countDown()
            if (!stopped.await(1, TimeUnit.SECONDS)) error("Recorder was not stopped")
            return shortArrayOf(1, 2, 3)
        }

        override fun stop() {
            stopCalled = true
            stopped.countDown()
        }

        fun awaitStarted(): Boolean = started.await(1, TimeUnit.SECONDS)
    }

    private class CancelAwareTranscriber : LocalTranscriber {
        var cancelCalled = false
        var transcribeCalled = false
        override fun transcribe(model: WhisperModel, pcm16: ShortArray): String = "hello"
            .also { transcribeCalled = true }
        override fun cancel() { cancelCalled = true }
    }

    private class BlockingTranscriber : LocalTranscriber {
        private val started = CountDownLatch(1)
        private val cancelled = CountDownLatch(1)
        @Volatile var cancelCalled = false

        override fun transcribe(model: WhisperModel, pcm16: ShortArray): String {
            started.countDown()
            cancelled.await(5, TimeUnit.SECONDS)
            return "late transcript"
        }

        override fun cancel() {
            cancelCalled = true
            cancelled.countDown()
        }

        fun awaitTranscribeStarted(): Boolean = started.await(1, TimeUnit.SECONDS)
    }
}
