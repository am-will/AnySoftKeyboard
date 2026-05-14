package com.amwill.keeb.models

import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun interface ModelStreamDownloader {
    fun open(model: WhisperModel): InputStream
}

class UrlModelStreamDownloader : ModelStreamDownloader {
    override fun open(model: WhisperModel): InputStream {
        val connection = URL(model.url).openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Keeb/dev")
        }
        return connection.getInputStream()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

data class ModelDownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
}

class StreamingModelInstaller(
    private val directory: File,
    private val downloader: ModelStreamDownloader,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {
    fun install(
        model: WhisperModel,
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): ModelInstallResult {
        if (model.checksum == "REQUIRES_OFFICIAL_CHECKSUM") {
            return ModelInstallResult.Failed("Model checksum must be pinned before download is enabled")
        }

        directory.mkdirs()
        val temp = tempFile(model)
        val finalFile = installedModelFile(directory, model)
        when (val existing = verifyInstalledModelFile(model, finalFile)) {
            is ModelFileCheck.Valid -> return ModelInstallResult.Installed(existing.file)
            ModelFileCheck.Missing,
            is ModelFileCheck.Invalid,
            -> Unit
        }
        val digest = MessageDigest.getInstance(model.checksumAlgorithm.messageDigestName())
        var written = 0L

        try {
            temp.delete()
            downloader.open(model).use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(bufferSize)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(ModelDownloadProgress(model.id, written, model.bytes))
                        }
                    }
                }
            }

            val actual = digest.digest().toHex()
            if (!actual.equals(model.checksum, ignoreCase = true)) {
                temp.delete()
                return ModelInstallResult.Failed("Checksum mismatch for ${model.id}")
            }
            if (!moveIntoPlace(temp, finalFile)) {
                temp.delete()
                return ModelInstallResult.Failed("Could not atomically install ${model.id}")
            }
            return ModelInstallResult.Installed(finalFile)
        } catch (error: RuntimeException) {
            temp.delete()
            return ModelInstallResult.Failed(error.message ?: "Could not download ${model.id}")
        }
    }

    private fun tempFile(model: WhisperModel): File = File(directory, "${model.id}.tmp")

    private fun moveIntoPlace(temp: File, finalFile: File): Boolean {
        return runCatching {
            Files.move(
                temp.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }.getOrElse {
            if (finalFile.exists() && !finalFile.delete()) return false
            temp.renameTo(finalFile)
        }
    }

    private fun ChecksumAlgorithm.messageDigestName(): String = when (this) {
        ChecksumAlgorithm.SHA1 -> "SHA-1"
        ChecksumAlgorithm.SHA256 -> "SHA-256"
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}

sealed interface ModelFileCheck {
    data class Valid(val file: File) : ModelFileCheck
    data object Missing : ModelFileCheck
    data class Invalid(val reason: String) : ModelFileCheck
}

class LocalModelFileScanner(
    private val directory: File,
    private val catalog: List<WhisperModel> = ModelCatalog.pinned,
) {
    fun installedRecords(): List<PersistedModelRecord> = catalog.mapNotNull { model ->
        val file = installedModelFile(directory, model)
        when (verifyInstalledModelFile(model, file)) {
            is ModelFileCheck.Valid -> PersistedModelRecord(
                modelId = model.id,
                status = PersistedModelStatus.Installed,
                localPath = file.absolutePath,
            )
            ModelFileCheck.Missing,
            is ModelFileCheck.Invalid,
            -> null
        }
    }
}

fun installedModelFile(directory: File, model: WhisperModel): File = File(directory, "${model.id}.bin")

fun verifyInstalledModelFile(model: WhisperModel, file: File): ModelFileCheck {
    if (!file.exists()) return ModelFileCheck.Missing
    if (!file.isFile || !file.canRead()) return ModelFileCheck.Invalid("Model file is not readable: ${file.absolutePath}")
    if (file.length() <= 0L) return ModelFileCheck.Invalid("Model file is empty: ${file.absolutePath}")
    if (model.checksum == "REQUIRES_OFFICIAL_CHECKSUM") {
        return ModelFileCheck.Invalid("Model checksum is not pinned for ${model.id}")
    }
    val actual = fileChecksum(file, model.checksumAlgorithm)
    return if (actual.equals(model.checksum, ignoreCase = true)) {
        ModelFileCheck.Valid(file)
    } else {
        ModelFileCheck.Invalid("Checksum mismatch for existing ${model.id}")
    }
}

fun fileChecksum(file: File, algorithm: ChecksumAlgorithm): String {
    val digest = MessageDigest.getInstance(
        when (algorithm) {
            ChecksumAlgorithm.SHA1 -> "SHA-1"
            ChecksumAlgorithm.SHA256 -> "SHA-256"
        },
    )
    file.inputStream().use { raw ->
        DigestInputStream(raw, digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) {
                // DigestInputStream updates the digest as bytes are consumed.
            }
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
