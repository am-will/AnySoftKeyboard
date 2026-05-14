package com.amwill.keeb.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempDirectory

class StreamingModelInstallerTest {
    @Test fun streamsModelToDiskWithProgressAndChecksumVerification() {
        val bytes = "streamed model bytes".toByteArray()
        val model = model(bytes, checksum = "6a2f2277fac614b3a8ace8c7355c970cb055f15987c04c041dc3610a2896c197")
        val directory = createTempDirectory("keeb-stream-installer").toFile()
        val progress = mutableListOf<ModelDownloadProgress>()

        val result = StreamingModelInstaller(
            directory = directory,
            downloader = { ByteArrayInputStream(bytes) },
            bufferSize = 5,
        ).install(model, progress::add)

        assertTrue(result is ModelInstallResult.Installed)
        assertEquals(bytes.size.toLong(), directory.resolve("fixture.bin").length())
        assertFalse(directory.resolve("fixture.tmp").exists())
        assertTrue(progress.size > 1)
        assertEquals(1f, progress.last().fraction)
        directory.deleteRecursively()
    }

    @Test fun checksumMismatchDeletesPartialFile() {
        val bytes = "bad model bytes".toByteArray()
        val model = model(bytes, checksum = "0000000000000000000000000000000000000000000000000000000000000000")
        val directory = createTempDirectory("keeb-stream-installer").toFile()

        val result = StreamingModelInstaller(directory, { ByteArrayInputStream(bytes) }).install(model)

        assertTrue(result is ModelInstallResult.Failed)
        assertFalse(directory.resolve("fixture.tmp").exists())
        assertFalse(directory.resolve("fixture.bin").exists())
        directory.deleteRecursively()
    }

    @Test fun existingVerifiedModelIsReusedWithoutOpeningNetworkStream() {
        val bytes = "streamed model bytes".toByteArray()
        val checksum = "6a2f2277fac614b3a8ace8c7355c970cb055f15987c04c041dc3610a2896c197"
        val model = model(bytes, checksum = checksum)
        val directory = createTempDirectory("keeb-stream-installer").toFile()
        directory.resolve("fixture.bin").writeBytes(bytes)
        var opened = false

        val result = StreamingModelInstaller(directory, {
            opened = true
            ByteArrayInputStream(bytes)
        }).install(model)

        assertTrue(result is ModelInstallResult.Installed)
        assertFalse(opened)
        assertEquals(bytes.size.toLong(), (result as ModelInstallResult.Installed).file.length())
        directory.deleteRecursively()
    }

    @Test fun scannerRecoversVerifiedInstalledModelFiles() {
        val bytes = "streamed model bytes".toByteArray()
        val checksum = "6a2f2277fac614b3a8ace8c7355c970cb055f15987c04c041dc3610a2896c197"
        val model = model(bytes, checksum = checksum)
        val directory = createTempDirectory("keeb-stream-scanner").toFile()
        directory.resolve("fixture.bin").writeBytes(bytes)

        val records = LocalModelFileScanner(directory, listOf(model)).installedRecords()

        assertEquals(
            listOf(PersistedModelRecord("fixture", PersistedModelStatus.Installed, directory.resolve("fixture.bin").absolutePath)),
            records,
        )
        directory.deleteRecursively()
    }

    @Test fun placeholderChecksumBlocksBeforeOpeningNetworkStream() {
        var opened = false
        val model = model(ByteArray(0), checksum = "REQUIRES_OFFICIAL_CHECKSUM")
        val directory = createTempDirectory("keeb-stream-installer").toFile()

        val result = StreamingModelInstaller(directory, {
            opened = true
            ByteArrayInputStream(ByteArray(0))
        }).install(model)

        assertTrue(result is ModelInstallResult.Failed)
        assertFalse(opened)
        directory.deleteRecursively()
    }

    private fun model(bytes: ByteArray, checksum: String): WhisperModel = WhisperModel(
        id = "fixture",
        displayName = "Fixture",
        size = ModelSize.Tiny,
        url = "file://fixture",
        bytes = bytes.size.toLong(),
        checksum = checksum,
        checksumAlgorithm = ChecksumAlgorithm.SHA256,
        minRamMb = 1,
        recommended = true,
    )
}
