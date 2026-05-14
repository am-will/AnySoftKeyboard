package com.amwill.keeb.models

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilityDetectorTest {
    @Test fun recommendationFiltersUnsafeModelsAndPrefersRecommendedSmallModels() {
        val tiny = model("tiny", bytes = 10, minRamMb = 512, recommended = true)
        val huge = model("huge", bytes = 10, minRamMb = 4096, recommended = false)
        val notEnoughStorage = model("storage", bytes = 1_000, minRamMb = 512, recommended = true)
        val summary = DeviceCapabilitySummary(
            profile = DeviceProfile(availableRamMb = 1024, freeStorageBytes = 100),
            primaryAbi = "arm64-v8a",
            cpuCores = 4,
        )

        assertEquals(listOf(tiny), summary.recommendationFor(listOf(huge, notEnoughStorage, tiny)))
    }

    private fun model(id: String, bytes: Long, minRamMb: Int, recommended: Boolean) = WhisperModel(
        id = id,
        displayName = id,
        size = ModelSize.Tiny,
        url = "https://example.invalid/$id.bin",
        bytes = bytes,
        checksum = "00",
        minRamMb = minRamMb,
        recommended = recommended,
    )
}
