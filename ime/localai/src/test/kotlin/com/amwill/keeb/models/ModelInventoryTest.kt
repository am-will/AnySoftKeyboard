package com.amwill.keeb.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInventoryTest {
    private val verifiedTiny = WhisperModel(
        id = "verified-tiny",
        displayName = "Verified Tiny",
        size = ModelSize.Tiny,
        url = "https://example.invalid/model.bin",
        bytes = 8,
        checksum = "0123456789abcdef",
        minRamMb = 1,
        recommended = true,
    )

    @Test fun catalogModelsHavePinnedChecksums() {
        assertTrue(ModelCatalog.pinned.none { it.checksum == "REQUIRES_OFFICIAL_CHECKSUM" })
    }

    @Test fun lowMemoryBlocksUnsafeModels() {
        val inventory = ModelInventory(listOf(verifiedTiny.copy(minRamMb = 4096)))
        val status = inventory.downloadGate("verified-tiny", DeviceProfile(availableRamMb = 1024, freeStorageBytes = 1_000_000))
        assertTrue(status is ModelStatus.Blocked)
    }

    @Test fun selectionRequiresInstalledModelAndDeleteClearsSelection() {
        val inventory = ModelInventory(listOf(verifiedTiny))
        try {
            inventory.select("verified-tiny")
            throw AssertionError("Expected selection to fail before install")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
        inventory.markInstalled("verified-tiny", localPath = "/models/verified-tiny.bin")
        assertEquals("verified-tiny", inventory.select("verified-tiny")?.model?.id)
        assertEquals("/models/verified-tiny.bin", inventory.selectedModel()?.localPath)
        inventory.delete("verified-tiny")
        assertNull(inventory.selectedModel())
        assertNull(inventory.records().single().model.localPath)
    }

    @Test fun failedStateKeepsReasonVisible() {
        val inventory = ModelInventory(listOf(verifiedTiny))
        val record = inventory.markFailed("verified-tiny", "checksum mismatch")
        assertEquals(ModelStatus.Failed("checksum mismatch"), record.status)
    }

    @Test fun snapshotAndRestorePreserveInstalledSelectionAndFailures() {
        val inventory = ModelInventory(listOf(verifiedTiny))
        inventory.markInstalled("verified-tiny", localPath = "/models/verified-tiny.bin")
        inventory.select("verified-tiny")
        val snapshot = inventory.snapshot()

        val restored = ModelInventory(listOf(verifiedTiny), snapshot)

        assertEquals("/models/verified-tiny.bin", restored.selectedModel()?.localPath)

        val failedSnapshot = listOf(
            PersistedModelRecord(
                modelId = "verified-tiny",
                status = PersistedModelStatus.Failed,
                failedReason = "network unavailable",
            ),
        )
        val failed = ModelInventory(listOf(verifiedTiny), failedSnapshot).records().single()
        assertEquals(ModelStatus.Failed("network unavailable"), failed.status)
        assertNull(failed.model.localPath)
    }
}
