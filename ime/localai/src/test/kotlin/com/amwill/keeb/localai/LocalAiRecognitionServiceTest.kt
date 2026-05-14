package com.amwill.keeb.localai

import android.os.Build
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class LocalAiRecognitionServiceTest {
    @Test fun resultBundleUsesSpeechRecognizerResultsKey() {
        val bundle = localAiRecognitionResultsBundle("hello keyboard")

        assertEquals(
            listOf("hello keyboard"),
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION),
        )
    }
}
