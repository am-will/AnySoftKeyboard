package com.amwill.keeb.localai

import android.content.Context
import com.amwill.keeb.models.LocalModelFileScanner
import com.amwill.keeb.models.ModelInventory
import com.amwill.keeb.models.SharedPreferencesModelInventoryStore
import com.amwill.keeb.models.WhisperModel
import com.amwill.keeb.voice.AndroidMicrophonePermissionChecker
import com.amwill.keeb.voice.AndroidPcm16AudioRecorder
import com.amwill.keeb.voice.NativeWhisperEngine
import com.amwill.keeb.voice.VoiceActivityDetector
import com.amwill.keeb.voice.VoiceInputController
import com.amwill.keeb.voice.VoiceInsertionFormatter
import com.amwill.keeb.voice.VoiceState
import java.io.File

class AskLocalAiVoiceBridge(context: Context) {
    interface Listener {
        fun onState(message: String)
        fun onAudioLevel(levelPercent: Int, speechDetected: Boolean)
        fun onTranscript(transcript: String)
        fun onTerminal(message: String)
        fun onSetupRequired(message: String)
    }

    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
    @Volatile private var controller: VoiceInputController? = null
    @Volatile private var worker: Thread? = null

    fun start(listener: Listener) {
        if (worker?.isAlive == true) {
            listener.onState("LocalAI voice is already recording.")
            return
        }

        val selectedModel = selectedModel()
        if (selectedModel == null) {
            listener.onSetupRequired("No verified Whisper model is selected. Open LocalAI Voice to download or select a model.")
            return
        }

        val nextController = VoiceInputController(
            permissionChecker = AndroidMicrophonePermissionChecker(appContext),
            recorder = AndroidPcm16AudioRecorder(
                voiceActivityDetectorFactory = ::VoiceActivityDetector,
                audioLevelListener = { listener.onAudioLevel(it.levelPercent, it.speechDetected) },
            ),
            transcriber = NativeWhisperEngine(),
            stateListener = { state -> listener.onState(state.message()) },
        )
        controller = nextController
        worker = Thread {
            val finalState = nextController.start(selectedModel)
            when (finalState) {
                is VoiceState.ReadyToInsert -> listener.onTranscript(VoiceInsertionFormatter.format(finalState.transcript))
                VoiceState.Canceled -> listener.onTerminal("LocalAI voice input canceled.")
                VoiceState.MissingPermission -> listener.onSetupRequired("Microphone permission is required for local voice typing. Open LocalAI Voice to grant it and try again.")
                VoiceState.MissingModel -> listener.onSetupRequired("No verified Whisper model is selected. Open LocalAI Voice.")
                is VoiceState.Error -> listener.onTerminal(finalState.message)
                else -> Unit
            }
            if (controller === nextController) controller = null
        }.apply {
            name = "KeebAskLocalAiVoice"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        controller?.stopRecording()
    }

    fun cancel() {
        controller?.cancel()
    }

    fun modelDirectoryPath(): String = modelDirectory.absolutePath

    private fun selectedModel(): WhisperModel? {
        val store = SharedPreferencesModelInventoryStore(appContext)
        val installed = LocalModelFileScanner(modelDirectory).installedRecords()
        return ModelInventory(persistedRecords = installed + store.load()).selectedModel()
    }

    private fun VoiceState.message(): String = when (this) {
        VoiceState.Idle -> "LocalAI voice is ready."
        VoiceState.MissingModel -> "No verified Whisper model is selected."
        VoiceState.MissingPermission -> "Microphone permission is required for local voice typing."
        VoiceState.Recording -> "Recording locally... Tap Stop when you are done."
        VoiceState.Processing -> "Transcribing on device..."
        VoiceState.Canceled -> "LocalAI voice input canceled."
        is VoiceState.ReadyToInsert -> "Local transcript ready."
        is VoiceState.Error -> message
    }

    private companion object {
        const val MODEL_DIRECTORY = "whisper-models"
    }
}
