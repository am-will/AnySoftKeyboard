package com.amwill.keeb.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInventoryCodecTest {
    @Test fun roundTripsRecordsWithPathsReasonsAndDelimiters() {
        val records = listOf(
            PersistedModelRecord(
                modelId = "tiny.en",
                status = PersistedModelStatus.Installed,
                localPath = "/models/tiny|with delimiter.bin",
                selected = true,
            ),
            PersistedModelRecord(
                modelId = "base",
                status = PersistedModelStatus.Failed,
                failedReason = "checksum | mismatch",
            ),
        )

        val decoded = ModelInventoryCodec.decode(ModelInventoryCodec.encode(records))

        assertEquals(records, decoded)
    }

    @Test fun ignoresMalformedLines() {
        assertEquals(emptyList<PersistedModelRecord>(), ModelInventoryCodec.decode("not|valid"))
    }
}
