package com.amwill.keeb.voice

import com.amwill.keeb.models.WhisperModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

sealed interface VoiceState {
    data object Idle : VoiceState
    data object MissingModel : VoiceState
    data object MissingPermission : VoiceState
    data object Recording : VoiceState
    data object Processing : VoiceState
    data object Canceled : VoiceState
    data class ReadyToInsert(val transcript: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

interface PermissionChecker { fun hasMicrophonePermission(): Boolean }
interface AudioRecorder {
    fun recordPcm16(): ShortArray
    fun stop() = Unit
    fun cancel() = Unit
}
interface LocalTranscriber {
    fun transcribe(model: WhisperModel, pcm16: ShortArray): String
    fun cancel() = Unit
}

class VoiceInputCanceledException : RuntimeException("Voice input canceled")
class VoiceInputTimeoutException(timeoutMillis: Long) : RuntimeException(
    "Whisper native transcription timed out after ${timeoutMillis / 1_000}s. Try a shorter recording or smaller model.",
)

class VoiceInputController(
    private val permissionChecker: PermissionChecker,
    private val recorder: AudioRecorder,
    private val transcriber: LocalTranscriber,
    private val stateListener: (VoiceState) -> Unit = {},
    private val transcriptionTimeoutMillis: Long = DEFAULT_TRANSCRIPTION_TIMEOUT_MILLIS,
    private val watchdogPollMillis: Long = DEFAULT_WATCHDOG_POLL_MILLIS,
) {
    @Volatile private var canceled = false
    var state: VoiceState = VoiceState.Idle
        private set

    fun start(selectedModel: WhisperModel?): VoiceState {
        canceled = false
        if (selectedModel == null) return update(VoiceState.MissingModel)
        if (!permissionChecker.hasMicrophonePermission()) return update(VoiceState.MissingPermission)
        return try {
            update(VoiceState.Recording)
            val audio = recorder.recordPcm16()
            if (canceled) throw VoiceInputCanceledException()
            if (audio.isEmpty()) return update(VoiceState.Error("No speech captured. Try again."))
            update(VoiceState.Processing)
            val transcript = transcribeWithWatchdog(selectedModel, audio).trim()
            if (canceled) throw VoiceInputCanceledException()
            if (transcript.isEmpty()) return update(VoiceState.Error("No speech was transcribed. Try again."))
            update(VoiceState.ReadyToInsert(transcript))
        } catch (_: VoiceInputCanceledException) {
            update(VoiceState.Canceled)
        } catch (error: VoiceInputTimeoutException) {
            update(VoiceState.Error(error.message ?: "Voice transcription timed out. Please retry."))
        } catch (error: RuntimeException) {
            update(VoiceState.Error(error.message ?: "Voice input failed"))
        }
    }

    fun consumeInsertion(): String? {
        val ready = state as? VoiceState.ReadyToInsert ?: return null
        state = VoiceState.Idle
        return ready.transcript
    }

    fun stopRecording() {
        recorder.stop()
    }

    fun cancel() {
        canceled = true
        recorder.cancel()
        transcriber.cancel()
        update(VoiceState.Canceled)
    }

    private fun update(next: VoiceState): VoiceState {
        state = next
        stateListener(next)
        return next
    }

    private fun transcribeWithWatchdog(model: WhisperModel, audio: ShortArray): String {
        val done = CountDownLatch(1)
        val transcript = AtomicReference<String?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                transcript.set(transcriber.transcribe(model, audio))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }.apply {
            name = "KeebVoiceTranscriber"
            isDaemon = true
        }
        worker.start()

        val timeoutMillis = transcriptionTimeoutMillis.coerceAtLeast(1L)
        val pollMillis = watchdogPollMillis.coerceAtLeast(1L)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                transcriber.cancel()
                throw VoiceInputTimeoutException(timeoutMillis)
            }
            try {
                if (done.await(minOf(pollMillis, remaining), TimeUnit.MILLISECONDS)) break
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                transcriber.cancel()
                throw VoiceInputCanceledException()
            }
            if (canceled) {
                transcriber.cancel()
                throw VoiceInputCanceledException()
            }
        }
        if (canceled) throw VoiceInputCanceledException()
        failure.get()?.let { throw it.asRuntimeException() }
        return transcript.get().orEmpty()
    }

    private fun Throwable.asRuntimeException(): RuntimeException =
        this as? RuntimeException ?: RuntimeException(message ?: "Voice input failed", this)

    private companion object {
        const val DEFAULT_TRANSCRIPTION_TIMEOUT_MILLIS = 45_000L
        const val DEFAULT_WATCHDOG_POLL_MILLIS = 250L
    }
}

class FakeLocalTranscriber(private val transcript: String) : LocalTranscriber {
    override fun transcribe(model: WhisperModel, pcm16: ShortArray): String = transcript
}

object VoiceInsertionFormatter {
    fun format(transcript: String): String =
        transcript.trim().takeIf { it.isNotEmpty() }?.let { "$it " }.orEmpty()
}
