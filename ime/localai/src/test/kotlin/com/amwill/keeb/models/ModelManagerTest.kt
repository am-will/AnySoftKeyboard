package com.amwill.keeb.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ModelManagerTest {
    @Test fun refusesModelsWithoutPinnedChecksums() {
        val directory = tempDir()
        val placeholder = WhisperModel(
            id = "placeholder",
            displayName = "Placeholder",
            size = ModelSize.Tiny,
            url = "https://example.invalid/model.bin",
            bytes = 1,
            checksum = "REQUIRES_OFFICIAL_CHECKSUM",
            minRamMb = 1,
            recommended = false,
        )
        val manager = ModelManager(directory) { ByteArray(0) }
        val result = manager.install(placeholder)
        assertTrue(result is ModelInstallResult.Failed)
        directory.deleteRecursively()
    }

    @Test fun installsOnlyAfterSha256Verification() {
        val bytes = "local model".toByteArray()
        val model = WhisperModel("fixture", "Fixture", ModelSize.Tiny, "file://fixture", bytes.size.toLong(), "ffa838f6509a5a99bd5d39d5b9bd4243a9c586f359a2830c93603c1908d13a6e", minRamMb = 1, recommended = true)
        val directory = tempDir()
        val manager = ModelManager(directory) { bytes }
        val result = manager.install(model)
        assertTrue(result is ModelInstallResult.Installed)
        assertEquals(bytes.size.toLong(), File(directory, "fixture.bin").length())
        directory.deleteRecursively()
    }

    @Test fun installsOfficialCatalogModelsUsingPinnedSha1Verification() {
        val bytes = "local model".toByteArray()
        val model = WhisperModel(
            id = "fixture-sha1",
            displayName = "Fixture SHA1",
            size = ModelSize.Tiny,
            url = "file://fixture",
            bytes = bytes.size.toLong(),
            checksum = "9a826ee7498db983564a30f41fb4d39eacd97eba",
            checksumAlgorithm = ChecksumAlgorithm.SHA1,
            minRamMb = 1,
            recommended = true,
        )
        val directory = tempDir()
        val result = ModelManager(directory) { bytes }.install(model)
        assertTrue(result is ModelInstallResult.Installed)
        assertEquals(bytes.size.toLong(), File(directory, "fixture-sha1.bin").length())
        directory.deleteRecursively()
    }

    @Test fun recommendsModelsByRamAndStorage() {
        val recommended = ModelManager(tempDir()) { ByteArray(0) }.recommendedModels(DeviceProfile(availableRamMb = 3072, freeStorageBytes = 1_000_000_000))
        assertTrue(recommended.any { it.id == "tiny.en" })
        assertTrue(recommended.none { it.id == "medium" })
    }

    private fun tempDir(): File = createTempDirectory("keeb-model-manager-test").toFile()
}
