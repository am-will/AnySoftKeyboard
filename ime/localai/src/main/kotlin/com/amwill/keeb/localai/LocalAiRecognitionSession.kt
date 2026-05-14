package com.amwill.keeb.localai

import android.content.Context
import android.speech.SpeechRecognizer
import com.amwill.keeb.models.LocalModelFileScanner
import com.amwill.keeb.models.ModelInventory
import com.amwill.keeb.models.SharedPreferencesModelInventoryStore
import com.amwill.keeb.models.WhisperModel
import com.amwill.keeb.voice.AndroidMicrophonePermissionChecker
import com.amwill.keeb.voice.AndroidPcm16AudioRecorder
import com.amwill.keeb.voice.NativeWhisperEngine
import com.amwill.keeb.voice.Pcm16RecordingConfig
import com.amwill.keeb.voice.VoiceActivityDetector
import com.amwill.keeb.voice.VoiceAudioLevel
import com.amwill.keeb.voice.VoiceInputController
import com.amwill.keeb.voice.VoiceState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalAiWhisperModelSelection {
    const val MODEL_DIRECTORY = "whisper-models"

    fun modelDirectory(context: Context): File = File(context.applicationContext.filesDir, MODEL_DIRECTORY)

    fun selectedModel(context: Context): WhisperModel? {
        val appContext = context.applicationContext
        val installed = LocalModelFileScanner(modelDirectory(appContext)).installedRecords()
        val stored = SharedPreferencesModelInventoryStore(appContext).load()
        return ModelInventory(persistedRecords = installed + stored).selectedModel()
    }
}

internal class LocalAiRecognitionSession(
    private val selectedModel: WhisperModel?,
    private val controllerFactory: ((VoiceState) -> Unit, (VoiceAudioLevel) -> Unit) -> VoiceInputController,
    private val listener: Listener,
    private val onFinished: (LocalAiRecognitionSession) -> Unit = {},
) {
    interface Listener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onRmsChanged(rms: Float)
        fun onEndOfSpeech()
        fun onResult(transcript: String)
        fun onError(errorCode: Int, message: String)
    }

    private val started = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val beginningOfSpeechSent = AtomicBoolean(false)
    private val endOfSpeechSent = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    @Volatile private var controller: VoiceInputController? = null
    @Volatile private var worker: Thread? = null

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        worker = Thread(::runRecognition).apply {
            name = "KeebLocalAiRecognizer"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        controller?.stopRecording()
    }

    fun cancel() {
        cancelRequested.set(true)
        controller?.cancel()
    }

    fun awaitFinished(timeoutMillis: Long): Boolean = finished.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun runRecognition() {
        try {
            val model = selectedModel
            if (model == null) {
                fail(
                    SpeechRecognizer.ERROR_CLIENT,
                    "No verified Whisper model is selected. Open KEEB LocalAI Voice to download or select a model.",
                )
                return
            }
            if (cancelRequested.get()) return

            listener.onReadyForSpeech()
            val nextController = controllerFactory(::onStateChanged, ::onAudioLevel)
            controller = nextController
            if (cancelRequested.get()) {
                nextController.cancel()
                return
            }

            publishFinalState(nextController.start(model))
        } catch (error: RuntimeException) {
            if (!cancelRequested.get()) {
                fail(SpeechRecognizer.ERROR_SERVER, error.message ?: "LocalAI voice recognition failed.")
            }
        } finally {
            controller = null
            finished.countDown()
            onFinished(this)
        }
    }

    private fun onStateChanged(state: VoiceState) {
        if (state is VoiceState.Processing) sendEndOfSpeech()
    }

    private fun onAudioLevel(level: VoiceAudioLevel) {
        if (cancelRequested.get()) return
        listener.onRmsChanged(level.levelPercent / RMS_PERCENT_TO_DB_SCALE)
        if (level.speechDetected && beginningOfSpeechSent.compareAndSet(false, true)) {
            listener.onBeginningOfSpeech()
        }
    }

    private fun publishFinalState(state: VoiceState) {
        if (cancelRequested.get()) return
        when (state) {
            is VoiceState.ReadyToInsert -> {
                sendEndOfSpeech()
                listener.onResult(state.transcript.trim())
            }
            VoiceState.MissingPermission -> fail(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "Microphone permission is required for KEEB LocalAI voice recognition.",
            )
            VoiceState.MissingModel -> fail(
                SpeechRecognizer.ERROR_CLIENT,
                "No verified Whisper model is selected. Open KEEB LocalAI Voice.",
            )
            is VoiceState.Error -> fail(state.speechRecognizerErrorCode(), state.message)
            VoiceState.Canceled,
            VoiceState.Idle,
            VoiceState.Processing,
            VoiceState.Recording,
            -> Unit
        }
    }

    private fun sendEndOfSpeech() {
        if (!cancelRequested.get() && endOfSpeechSent.compareAndSet(false, true)) {
            listener.onEndOfSpeech()
        }
    }

    private fun fail(errorCode: Int, message: String) {
        if (!cancelRequested.get()) listener.onError(errorCode, message)
    }

    private fun VoiceState.Error.speechRecognizerErrorCode(): Int = when {
        message.contains("No speech captured", ignoreCase = true) -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        message.contains("No speech was transcribed", ignoreCase = true) -> SpeechRecognizer.ERROR_NO_MATCH
        else -> SpeechRecognizer.ERROR_SERVER
    }

    companion object {
        private const val MAX_RECORDING_MILLIS = 60_000
        private const val RMS_PERCENT_TO_DB_SCALE = 10f

        fun create(
            context: Context,
            listener: Listener,
            onFinished: (LocalAiRecognitionSession) -> Unit,
        ): LocalAiRecognitionSession {
            val appContext = context.applicationContext
            return LocalAiRecognitionSession(
                selectedModel = LocalAiWhisperModelSelection.selectedModel(appContext),
                controllerFactory = { stateListener, audioLevelListener ->
                    VoiceInputController(
                        permissionChecker = AndroidMicrophonePermissionChecker(appContext),
                        recorder = AndroidPcm16AudioRecorder(
                            config = Pcm16RecordingConfig(maxMillis = MAX_RECORDING_MILLIS),
                            voiceActivityDetectorFactory = ::VoiceActivityDetector,
                            audioLevelListener = audioLevelListener,
                        ),
                        transcriber = NativeWhisperEngine(),
                        stateListener = stateListener,
                    )
                },
                listener = listener,
                onFinished = onFinished,
            )
        }
    }
}
