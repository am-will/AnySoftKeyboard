package com.amwill.keeb.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidVoiceInputTest {
    @Test fun pcm16RecordingConfigComputesExpectedSampleBudget() {
        assertEquals(80_000, Pcm16RecordingConfig(sampleRate = 16_000, maxMillis = 5_000).maxSamples)
        assertEquals(8_000, Pcm16RecordingConfig(sampleRate = 16_000, maxMillis = 500).maxSamples)
    }
}
