package com.amwill.keeb.voice

import com.amwill.keeb.models.ModelFileCheck
import com.amwill.keeb.models.WhisperModel
import com.amwill.keeb.models.verifyInstalledModelFile
import java.io.File

interface WhisperEngine : LocalTranscriber {
    fun load(model: WhisperModel)
    fun unload()
    override fun cancel()
}

interface WhisperNativeBridge {
    fun createContext(modelPath: String): Long
    fun freeContext(contextHandle: Long)
    fun transcribe(contextHandle: Long, pcm16: ShortArray, sampleRate: Int): String
    fun cancel(contextHandle: Long)
}

class NativeWhisperBridge(
    libraryLoader: () -> Unit = { System.loadLibrary("keeb_whisper_jni") },
) : WhisperNativeBridge {
    init {
        libraryLoader()
    }

    override fun createContext(modelPath: String): Long = nativeCreateContext(modelPath)
    override fun freeContext(contextHandle: Long) = nativeFreeContext(contextHandle)
    override fun transcribe(contextHandle: Long, pcm16: ShortArray, sampleRate: Int): String =
        nativeTranscribe(contextHandle, pcm16, sampleRate)

    override fun cancel(contextHandle: Long) = nativeCancel(contextHandle)

    private external fun nativeCreateContext(modelPath: String): Long
    private external fun nativeFreeContext(contextHandle: Long)
    private external fun nativeTranscribe(contextHandle: Long, pcm16: ShortArray, sampleRate: Int): String
    private external fun nativeCancel(contextHandle: Long)
}

class NativeWhisperEngine(
    private val bridge: WhisperNativeBridge = NativeWhisperBridge(),
    private val sampleRate: Int = 16_000,
    private val modelFileValidator: (WhisperModel, String) -> ModelFileCheck = { model, path ->
        verifyInstalledModelFile(model, File(path))
    },
) : WhisperEngine {
    private var loadedModelId: String? = null
    private var contextHandle: Long = 0L

    override fun load(model: WhisperModel) {
        val modelPath = model.localModelPath()
        if (loadedModelId == model.id && contextHandle != 0L) return
        unload()
        when (val check = modelFileValidator(model, modelPath)) {
            is ModelFileCheck.Valid -> Unit
            ModelFileCheck.Missing -> error("Local Whisper model is missing at $modelPath")
            is ModelFileCheck.Invalid -> error("Local Whisper model is invalid: ${check.reason}")
        }
        contextHandle = bridge.createContext(modelPath)
        check(contextHandle != 0L) { "Could not load local Whisper model at $modelPath. The file may not be a compatible whisper.cpp ggml model." }
        loadedModelId = model.id
    }

    override fun transcribe(model: WhisperModel, pcm16: ShortArray): String {
        load(model)
        return bridge.transcribe(contextHandle, pcm16, sampleRate)
    }

    override fun unload() {
        if (contextHandle != 0L) {
            bridge.freeContext(contextHandle)
        }
        contextHandle = 0L
        loadedModelId = null
    }

    override fun cancel() {
        if (contextHandle != 0L) bridge.cancel(contextHandle)
    }

    private fun WhisperModel.localModelPath(): String {
        localPath?.let { return it }
        if (url.startsWith("file://")) return url.removePrefix("file://")
        error("Model ${id} is not installed locally")
    }
}
