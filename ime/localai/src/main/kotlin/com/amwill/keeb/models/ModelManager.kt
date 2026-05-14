package com.amwill.keeb.models

import java.io.File
import java.security.MessageDigest

fun interface ModelDownloader {
    fun download(model: WhisperModel): ByteArray
}

data class DeviceProfile(val availableRamMb: Int, val freeStorageBytes: Long)

sealed interface ModelInstallResult {
    data class Installed(val file: File) : ModelInstallResult
    data class Failed(val reason: String) : ModelInstallResult
}

class ModelManager(private val directory: File, private val downloader: ModelDownloader) {
    fun recommendedModels(profile: DeviceProfile, catalog: List<WhisperModel> = ModelCatalog.pinned): List<WhisperModel> =
        catalog.filter { profile.availableRamMb >= it.minRamMb && profile.freeStorageBytes > it.bytes * 2 }
            .sortedWith(compareByDescending<WhisperModel> { it.recommended }.thenBy { it.bytes })

    fun install(model: WhisperModel): ModelInstallResult {
        if (model.checksum == "REQUIRES_OFFICIAL_CHECKSUM") {
            return ModelInstallResult.Failed("Model checksum must be pinned before download is enabled")
        }
        directory.mkdirs()
        val bytes = downloader.download(model)
        val actual = checksum(bytes, model.checksumAlgorithm)
        if (!actual.equals(model.checksum, ignoreCase = true)) {
            return ModelInstallResult.Failed("Checksum mismatch for ${model.id}")
        }
        val temp = File(directory, "${model.id}.tmp")
        val finalFile = File(directory, "${model.id}.bin")
        temp.writeBytes(bytes)
        if (!temp.renameTo(finalFile)) return ModelInstallResult.Failed("Could not atomically install ${model.id}")
        return ModelInstallResult.Installed(finalFile)
    }

    fun delete(modelId: String): Boolean = File(directory, "$modelId.bin").delete()

    private fun checksum(bytes: ByteArray, algorithm: ChecksumAlgorithm): String {
        val digest = MessageDigest.getInstance(
            when (algorithm) {
                ChecksumAlgorithm.SHA1 -> "SHA-1"
                ChecksumAlgorithm.SHA256 -> "SHA-256"
            },
        ).digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
