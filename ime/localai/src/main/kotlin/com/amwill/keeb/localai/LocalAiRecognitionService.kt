package com.amwill.keeb.localai

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

class LocalAiRecognitionService : RecognitionService() {
    private val activeSession = AtomicReference<LocalAiRecognitionSession?>()

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        val session = LocalAiRecognitionSession.create(
            context = this,
            listener = RecognitionCallbackListener(callback),
            onFinished = { finished -> activeSession.compareAndSet(finished, null) },
        )
        if (!activeSession.compareAndSet(null, session)) {
            Log.w(TAG, "Rejecting LocalAI recognition request because another session is active.")
            callback.safeError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        Log.i(TAG, "Starting LocalAI speech recognition session.")
        if (!session.start()) {
            activeSession.compareAndSet(session, null)
            callback.safeError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        }
    }

    override fun onStopListening(callback: Callback) {
        val session = activeSession.get()
        if (session == null) {
            Log.w(TAG, "Stop requested without an active LocalAI recognition session.")
            callback.safeError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        Log.i(TAG, "Stopping LocalAI speech recording for transcription.")
        session.stop()
    }

    override fun onCancel(callback: Callback) {
        Log.i(TAG, "Canceling LocalAI speech recognition session.")
        activeSession.getAndSet(null)?.cancel()
    }

    override fun onDestroy() {
        activeSession.getAndSet(null)?.cancel()
        super.onDestroy()
    }

    private class RecognitionCallbackListener(
        private val callback: Callback,
    ) : LocalAiRecognitionSession.Listener {
        override fun onReadyForSpeech() = safeCallback("readyForSpeech") {
            callback.readyForSpeech(Bundle.EMPTY)
        }

        override fun onBeginningOfSpeech() = safeCallback("beginningOfSpeech") {
            callback.beginningOfSpeech()
        }

        override fun onRmsChanged(rms: Float) = safeCallback("rmsChanged") {
            callback.rmsChanged(rms)
        }

        override fun onEndOfSpeech() = safeCallback("endOfSpeech") {
            callback.endOfSpeech()
        }

        override fun onResult(transcript: String) = safeCallback("results") {
            Log.i(TAG, "LocalAI speech recognition produced a transcript.")
            callback.results(localAiRecognitionResultsBundle(transcript))
        }

        override fun onError(errorCode: Int, message: String) = safeCallback("error") {
            Log.w(TAG, "LocalAI speech recognition failed: $message")
            callback.error(errorCode)
        }

        private fun safeCallback(action: String, block: () -> Unit) {
            try {
                block()
            } catch (error: RemoteException) {
                Log.w(TAG, "Could not deliver LocalAI RecognitionService callback: $action", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not deliver LocalAI RecognitionService callback: $action", error)
            }
        }
    }

    private fun Callback.safeError(errorCode: Int) {
        try {
            error(errorCode)
        } catch (error: RemoteException) {
            Log.w(TAG, "Could not deliver LocalAI RecognitionService error callback.", error)
        }
    }

    private companion object {
        const val TAG = "KeebLocalAiRecognizer"
    }
}

internal fun localAiRecognitionResultsBundle(transcript: String): Bundle = Bundle().apply {
    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(transcript))
}
