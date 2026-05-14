package com.amwill.keeb.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max
import kotlin.math.roundToInt

data class Pcm16RecordingConfig(
    val sampleRate: Int = 16_000,
    val maxMillis: Int = 60_000,
) {
    val maxSamples: Int get() = sampleRate * maxMillis / 1_000
}

class AndroidMicrophonePermissionChecker(private val context: Context) : PermissionChecker {
    override fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

class AndroidPcm16AudioRecorder(
    private val config: Pcm16RecordingConfig = Pcm16RecordingConfig(),
    private val voiceActivityDetectorFactory: (() -> VoiceActivityDetector)? = null,
    private val audioLevelListener: (VoiceAudioLevel) -> Unit = {},
) : AudioRecorder {
    @Volatile private var canceled = false
    @Volatile private var stopRequested = false
    @Volatile private var activeRecorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun recordPcm16(): ShortArray {
        canceled = false
        stopRequested = false
        val minBufferBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "Device does not support PCM16 mono recording at ${config.sampleRate} Hz" }
        val readBufferSamples = max(minBufferBytes / BYTES_PER_SAMPLE, config.sampleRate / 10)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(readBufferSamples * BYTES_PER_SAMPLE)
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone recorder" }
        activeRecorder = recorder

        val output = ShortArray(config.maxSamples)
        val buffer = ShortArray(readBufferSamples)
        val voiceActivityDetector = voiceActivityDetectorFactory?.invoke()
        var offset = 0
        try {
            recorder.startRecording()
            while (offset < output.size && !canceled && !stopRequested) {
                val read = recorder.read(buffer, 0, minOf(buffer.size, output.size - offset))
                if (read <= 0) break
                buffer.copyInto(output, destinationOffset = offset, startIndex = 0, endIndex = read)
                offset += read
                val activity = voiceActivityDetector?.analyze(buffer, read)
                audioLevelListener(activity?.audioLevel ?: buffer.audioLevel(read))
                if (activity?.shouldStopAfter == true) break
            }
            if (canceled) throw VoiceInputCanceledException()
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            activeRecorder = null
            recorder.release()
        }
        return output.copyOf(offset)
    }

    override fun stop() {
        stopRequested = true
        runCatching { activeRecorder?.stop() }
    }

    override fun cancel() {
        canceled = true
        runCatching { activeRecorder?.stop() }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}

private fun ShortArray.audioLevel(count: Int): VoiceAudioLevel {
    val peakAmplitude = take(count).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
    return VoiceAudioLevel(
        levelPercent = ((peakAmplitude.toDouble() / Short.MAX_VALUE) * 100.0).roundToInt().coerceIn(0, 100),
        speechDetected = false,
        peakAmplitude = peakAmplitude,
    )
}
