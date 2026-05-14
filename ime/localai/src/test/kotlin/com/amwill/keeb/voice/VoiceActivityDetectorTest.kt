package com.amwill.keeb.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private val config = VoiceActivityConfig(
        sampleRate = 1_000,
        speechAmplitudeThreshold = 10,
        leadingSilenceMillis = 300,
        trailingSilenceMillis = 200,
        minSpeechMillis = 100,
    )

    @Test fun stopsAfterLeadingSilenceWithoutSpeech() {
        val detector = VoiceActivityDetector(config)

        assertFalse(detector.shouldStopAfter(ShortArray(100), 100))
        assertFalse(detector.shouldStopAfter(ShortArray(100), 100))
        assertTrue(detector.shouldStopAfter(ShortArray(100), 100))
    }

    @Test fun stopsAfterSpeechAndTrailingSilence() {
        val detector = VoiceActivityDetector(config)

        assertFalse(detector.shouldStopAfter(ShortArray(100) { 20 }, 100))
        assertFalse(detector.shouldStopAfter(ShortArray(100), 100))
        assertTrue(detector.shouldStopAfter(ShortArray(100), 100))
    }

    @Test fun doesNotStopAfterTooLittleSpeech() {
        val detector = VoiceActivityDetector(config.copy(minSpeechMillis = 250))

        assertFalse(detector.shouldStopAfter(ShortArray(100) { 20 }, 100))
        assertFalse(detector.shouldStopAfter(ShortArray(200), 200))
    }

    @Test fun reportsLevelAndSpeechDetectionWithActivityResult() {
        val detector = VoiceActivityDetector(config)

        val quiet = detector.analyze(ShortArray(100) { 5 }, 100)
        val speech = detector.analyze(ShortArray(100) { 20 }, 100)

        assertFalse(quiet.audioLevel.speechDetected)
        assertEquals(5, quiet.audioLevel.peakAmplitude)
        assertTrue(speech.audioLevel.speechDetected)
        assertEquals(20, speech.audioLevel.peakAmplitude)
        assertTrue(speech.audioLevel.levelPercent >= quiet.audioLevel.levelPercent)
    }
}
