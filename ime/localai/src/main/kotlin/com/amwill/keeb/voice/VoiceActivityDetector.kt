package com.amwill.keeb.voice

import kotlin.math.abs
import kotlin.math.roundToInt

data class VoiceActivityConfig(
    val sampleRate: Int = 16_000,
    val speechAmplitudeThreshold: Int = 650,
    val leadingSilenceMillis: Int = 1_500,
    val trailingSilenceMillis: Int = 900,
    val minSpeechMillis: Int = 250,
) {
    fun samplesForMillis(millis: Int): Int = sampleRate * millis / 1_000
}

data class VoiceAudioLevel(
    val levelPercent: Int,
    val speechDetected: Boolean,
    val peakAmplitude: Int,
)

data class VoiceActivityResult(
    val shouldStopAfter: Boolean,
    val audioLevel: VoiceAudioLevel,
)

class VoiceActivityDetector(private val config: VoiceActivityConfig = VoiceActivityConfig()) {
    private var seenSpeech = false
    private var speechSamples = 0
    private var leadingSilenceSamples = 0
    private var trailingSilenceSamples = 0

    fun shouldStopAfter(samples: ShortArray, count: Int): Boolean = analyze(samples, count).shouldStopAfter

    fun analyze(samples: ShortArray, count: Int): VoiceActivityResult {
        val peakAmplitude = samples.take(count).maxOfOrNull { abs(it.toInt()) } ?: 0
        val active = peakAmplitude >= config.speechAmplitudeThreshold
        val audioLevel = VoiceAudioLevel(
            levelPercent = ((peakAmplitude.toDouble() / Short.MAX_VALUE) * 100.0).roundToInt().coerceIn(0, 100),
            speechDetected = active,
            peakAmplitude = peakAmplitude,
        )
        if (active) {
            seenSpeech = true
            speechSamples += count
            trailingSilenceSamples = 0
            return VoiceActivityResult(shouldStopAfter = false, audioLevel = audioLevel)
        }

        if (seenSpeech) {
            trailingSilenceSamples += count
            return VoiceActivityResult(
                shouldStopAfter = speechSamples >= config.samplesForMillis(config.minSpeechMillis) &&
                    trailingSilenceSamples >= config.samplesForMillis(config.trailingSilenceMillis),
                audioLevel = audioLevel,
            )
        }

        leadingSilenceSamples += count
        return VoiceActivityResult(
            shouldStopAfter = leadingSilenceSamples >= config.samplesForMillis(config.leadingSilenceMillis),
            audioLevel = audioLevel,
        )
    }
}
