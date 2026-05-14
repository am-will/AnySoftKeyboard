package com.amwill.keeb.localai

import android.speech.SpeechRecognizer
import com.amwill.keeb.models.ModelSize
import com.amwill.keeb.models.WhisperModel
import com.amwill.keeb.voice.AudioRecorder
import com.amwill.keeb.voice.FakeLocalTranscriber
import com.amwill.keeb.voice.LocalTranscriber
import com.amwill.keeb.voice.PermissionChecker
import com.amwill.keeb.voice.VoiceAudioLevel
import com.amwill.keeb.voice.VoiceInputCanceledException
import com.amwill.keeb.voice.VoiceInputController
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiRecognitionSessionTest {
    private val model = WhisperModel("fixture", "Fixture", ModelSize.Tiny, "file://fixture", 1, "00", minRamMb = 1, recommended = true)

    @Test fun missingModelReportsRecognizerError() {
        val listener = RecordingListener()
        val session = session(selectedModel = null, listener = listener)

        assertTrue(session.start())
        assertTrue(session.awaitFinished(1_000))

        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), listener.errorCodes())
        assertTrue(listener.results().isEmpty())
        assertEquals(0, listener.readyCount.get())
    }

    @Test fun fakeTranscriptReturnsRecognitionResult() {
        val listener = RecordingListener()
        val session = session(listener = listener, transcriber = FakeLocalTranscriber(" hello keyboard "))

        assertTrue(session.start())
        assertTrue(session.awaitFinished(1_000))

        assertEquals(listOf("hello keyboard"), listener.results())
        assertTrue(listener.errorCodes().isEmpty())
        assertEquals(1, listener.readyCount.get())
        assertEquals(1, listener.endCount.get())
    }

    @Test fun stopRequestsTranscriptionAndReturnsResult() {
        val listener = RecordingListener()
        val recorder = StopAwaitingRecorder()
        val session = session(listener = listener, recorder = recorder)

        assertTrue(session.start())
        assertTrue(recorder.awaitStarted())
        session.stop()
        assertTrue(session.awaitFinished(1_000))

        assertTrue(recorder.stopCalled)
        assertEquals(listOf("hello"), listener.results())
        assertTrue(listener.errorCodes().isEmpty())
    }

    @Test fun cancelStopsRecorderWithoutDeliveringTerminalCallback() {
        val listener = RecordingListener()
        val recorder = CancelAwaitingRecorder()
        val session = session(listener = listener, recorder = recorder)

        assertTrue(session.start())
        assertTrue(recorder.awaitStarted())
        session.cancel()
        assertTrue(session.awaitFinished(1_000))

        assertTrue(recorder.cancelCalled)
        assertTrue(listener.results().isEmpty())
        assertTrue(listener.errorCodes().isEmpty())
    }

    private fun session(
        selectedModel: WhisperModel? = model,
        listener: RecordingListener,
        recorder: AudioRecorder = object : AudioRecorder {
            override fun recordPcm16(): ShortArray = shortArrayOf(1, 2, 3)
        },
        transcriber: LocalTranscriber = FakeLocalTranscriber("hello"),
        hasPermission: Boolean = true,
    ) = LocalAiRecognitionSession(
        selectedModel = selectedModel,
        controllerFactory = { stateListener, audioLevelListener ->
            VoiceInputController(
                permissionChecker = object : PermissionChecker {
                    override fun hasMicrophonePermission(): Boolean = hasPermission
                },
                recorder = object : AudioRecorder {
                    override fun recordPcm16(): ShortArray {
                        audioLevelListener(VoiceAudioLevel(levelPercent = 20, speechDetected = true, peakAmplitude = 6_000))
                        return recorder.recordPcm16()
                    }

                    override fun stop() = recorder.stop()
                    override fun cancel() = recorder.cancel()
                },
                transcriber = transcriber,
                stateListener = stateListener,
            )
        },
        listener = listener,
    )

    private class RecordingListener : LocalAiRecognitionSession.Listener {
        val readyCount = AtomicInteger()
        val endCount = AtomicInteger()
        private val results = Collections.synchronizedList(mutableListOf<String>())
        private val errors = Collections.synchronizedList(mutableListOf<Int>())

        override fun onReadyForSpeech() {
            readyCount.incrementAndGet()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rms: Float) = Unit

        override fun onEndOfSpeech() {
            endCount.incrementAndGet()
        }

        override fun onResult(transcript: String) {
            results += transcript
        }

        override fun onError(errorCode: Int, message: String) {
            errors += errorCode
        }

        fun results(): List<String> = synchronized(results) { results.toList() }
        fun errorCodes(): List<Int> = synchronized(errors) { errors.toList() }
    }

    private class StopAwaitingRecorder : AudioRecorder {
        private val started = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        @Volatile var stopCalled = false

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

    private class CancelAwaitingRecorder : AudioRecorder {
        private val started = CountDownLatch(1)
        private val canceled = CountDownLatch(1)
        @Volatile var cancelCalled = false

        override fun recordPcm16(): ShortArray {
            started.countDown()
            canceled.await(1, TimeUnit.SECONDS)
            throw VoiceInputCanceledException()
        }

        override fun cancel() {
            cancelCalled = true
            canceled.countDown()
        }

        fun awaitStarted(): Boolean = started.await(1, TimeUnit.SECONDS)
    }
}
