package com.amwill.keeb.voice

import com.amwill.keeb.models.ModelInventory
import com.amwill.keeb.models.ModelSize
import com.amwill.keeb.models.WhisperModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputCoordinatorTest {
    private val model = WhisperModel("fixture", "Fixture", ModelSize.Tiny, "file://fixture", 1, "00", minRamMb = 1, recommended = true)

    @Test fun selectedInstalledModelIsPassedToTranscriber() {
        val inventory = ModelInventory(listOf(model))
        inventory.markInstalled("fixture")
        inventory.select("fixture")
        var seenModelId: String? = null
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1) },
            transcriber = object : LocalTranscriber {
                override fun transcribe(model: WhisperModel, pcm16: ShortArray): String {
                    seenModelId = model.id
                    return "hello"
                }
            },
        )
        val state = VoiceInputCoordinator(inventory, controller).startWithSelectedModel()
        assertTrue(state is VoiceState.ReadyToInsert)
        assertEquals("fixture", seenModelId)
    }

    @Test fun noSelectedInstalledModelReportsMissingModel() {
        val inventory = ModelInventory(listOf(model))
        val controller = VoiceInputController(
            permissionChecker = object : PermissionChecker { override fun hasMicrophonePermission() = true },
            recorder = object : AudioRecorder { override fun recordPcm16() = shortArrayOf(1) },
            transcriber = FakeLocalTranscriber("hello"),
        )
        assertTrue(VoiceInputCoordinator(inventory, controller).startWithSelectedModel() is VoiceState.MissingModel)
    }
}
