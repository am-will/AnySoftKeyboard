package com.amwill.keeb.voice

import com.amwill.keeb.models.ModelSize
import com.amwill.keeb.models.WhisperModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWhisperEngineTest {
    @Test fun loadsLocalModelAndRoutesTranscriptionThroughBridge() {
        val bridge = RecordingBridge(transcript = "local transcript")
        val engine = NativeWhisperEngine(bridge = bridge)
        val localModel = localModel()

        val transcript = engine.transcribe(localModel, shortArrayOf(1, 2, 3))

        assertEquals("local transcript", transcript)
        assertEquals(localModel.localPath, bridge.createdPaths.single())
        assertEquals(1L, bridge.transcribedHandles.single())
    }

    @Test fun reusesLoadedContextForSameModelAndFreesOnUnload() {
        val bridge = RecordingBridge(transcript = "ok")
        val engine = NativeWhisperEngine(bridge = bridge)
        val localModel = localModel()

        engine.load(localModel)
        engine.load(localModel)
        engine.unload()

        assertEquals(1, bridge.createdPaths.size)
        assertEquals(listOf(1L), bridge.freedHandles)
    }

    @Test fun rejectsRemoteCatalogModelUntilItIsInstalledLocally() {
        val remoteModel = localModel().copy(url = "https://example.invalid/model.bin", localPath = null)
        val engine = NativeWhisperEngine(bridge = RecordingBridge(transcript = "unused"))

        try {
            engine.load(remoteModel)
            throw AssertionError("Expected remote model load to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not installed locally"))
        }
    }

    @Test fun rejectsMissingOrEmptyLocalModelBeforeCallingNativeBridge() {
        val missingModel = localModel().copy(localPath = "/tmp/keeb-missing-whisper-model.bin")
        val bridge = RecordingBridge(transcript = "unused")
        val engine = NativeWhisperEngine(bridge = bridge)

        try {
            engine.load(missingModel)
            throw AssertionError("Expected missing model load to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("missing"))
        }

        assertTrue(bridge.createdPaths.isEmpty())
    }

    @Test fun rejectsChecksumMismatchBeforeCallingNativeBridge() {
        val badChecksumModel = localModel().copy(checksum = "0000000000000000000000000000000000000000000000000000000000000000")
        val bridge = RecordingBridge(transcript = "unused")
        val engine = NativeWhisperEngine(bridge = bridge)

        try {
            engine.load(badChecksumModel)
            throw AssertionError("Expected checksum mismatch to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("checksum", ignoreCase = true))
        }

        assertTrue(bridge.createdPaths.isEmpty())
    }

    @Test fun reportsNativeModelLoadFailureAsInvalidModel() {
        val bridge = RecordingBridge(transcript = "unused", createHandle = 0L)
        val engine = NativeWhisperEngine(bridge = bridge)

        try {
            engine.load(localModel())
            throw AssertionError("Expected native load failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("compatible whisper.cpp ggml model"))
        }
    }

    @Test fun cancelIsForwardedOnlyWhenLoaded() {
        val bridge = RecordingBridge(transcript = "ok")
        val engine = NativeWhisperEngine(bridge = bridge)
        val localModel = localModel()

        engine.cancel()
        engine.load(localModel)
        engine.cancel()

        assertEquals(listOf(1L), bridge.cancelledHandles)
    }

    private fun localModel(): WhisperModel {
        val file = File.createTempFile("keeb-whisper-fixture", ".bin").apply {
            writeBytes(byteArrayOf(1))
            deleteOnExit()
        }
        return WhisperModel(
            id = "fixture",
            displayName = "Fixture",
            size = ModelSize.Tiny,
            url = "file://${file.absolutePath}",
            bytes = 1,
            checksum = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a",
            minRamMb = 1,
            recommended = true,
            localPath = file.absolutePath,
        )
    }

    private class RecordingBridge(
        private val transcript: String,
        private val createHandle: Long? = null,
    ) : WhisperNativeBridge {
        val createdPaths = mutableListOf<String>()
        val transcribedHandles = mutableListOf<Long>()
        val freedHandles = mutableListOf<Long>()
        val cancelledHandles = mutableListOf<Long>()

        override fun createContext(modelPath: String): Long {
            createdPaths += modelPath
            return createHandle ?: createdPaths.size.toLong()
        }

        override fun freeContext(contextHandle: Long) {
            freedHandles += contextHandle
        }

        override fun transcribe(contextHandle: Long, pcm16: ShortArray, sampleRate: Int): String {
            transcribedHandles += contextHandle
            return transcript
        }

        override fun cancel(contextHandle: Long) {
            cancelledHandles += contextHandle
        }
    }
}
