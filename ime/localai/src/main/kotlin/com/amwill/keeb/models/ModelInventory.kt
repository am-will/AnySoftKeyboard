package com.amwill.keeb.models

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data object Installed : ModelStatus
    data class Blocked(val reason: String) : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

data class ModelRecord(
    val model: WhisperModel,
    val status: ModelStatus = ModelStatus.NotDownloaded,
    val selected: Boolean = false,
)

data class PersistedModelRecord(
    val modelId: String,
    val status: PersistedModelStatus,
    val localPath: String? = null,
    val selected: Boolean = false,
    val failedReason: String? = null,
)

enum class PersistedModelStatus { NotDownloaded, Installed, Failed }

class ModelInventory(
    catalog: List<WhisperModel> = ModelCatalog.pinned,
    persistedRecords: List<PersistedModelRecord> = emptyList(),
) {
    private val records = catalog.associate { it.id to ModelRecord(it) }.toMutableMap()

    init {
        persistedRecords.forEach { persisted ->
            val current = records[persisted.modelId] ?: return@forEach
            val status = when (persisted.status) {
                PersistedModelStatus.NotDownloaded -> ModelStatus.NotDownloaded
                PersistedModelStatus.Installed -> ModelStatus.Installed
                PersistedModelStatus.Failed -> ModelStatus.Failed(persisted.failedReason ?: "Previous download failed")
            }
            records[persisted.modelId] = current.copy(
                model = current.model.copy(localPath = persisted.localPath),
                status = status,
                selected = persisted.selected && status == ModelStatus.Installed,
            )
        }
        if (records.values.count { it.selected } > 1) {
            val firstSelected = records.values.first { it.selected }.model.id
            records.replaceAll { id, record -> record.copy(selected = id == firstSelected) }
        }
    }

    fun records(): List<ModelRecord> = records.values.sortedBy { it.model.bytes }

    fun selectedModel(): WhisperModel? = records.values.firstOrNull { it.selected && it.status == ModelStatus.Installed }?.model

    fun markInstalled(modelId: String, localPath: String? = null): ModelRecord {
        val record = record(modelId)
        val next = record.copy(
            model = if (localPath == null) record.model else record.model.copy(localPath = localPath),
            status = ModelStatus.Installed,
        )
        records[modelId] = next
        return next
    }

    fun markFailed(modelId: String, reason: String): ModelRecord {
        val record = record(modelId)
        val next = record.copy(status = ModelStatus.Failed(reason))
        records[modelId] = next
        return next
    }

    fun select(modelId: String?): ModelRecord? {
        if (modelId == null) {
            records.replaceAll { _, record -> record.copy(selected = false) }
            return null
        }
        val target = record(modelId)
        require(target.status == ModelStatus.Installed) { "Only installed models can be selected" }
        records.replaceAll { id, record -> record.copy(selected = id == modelId) }
        return records.getValue(modelId)
    }

    fun delete(modelId: String): ModelRecord {
        val target = record(modelId)
        val next = target.copy(
            model = target.model.copy(localPath = null),
            status = ModelStatus.NotDownloaded,
            selected = false,
        )
        records[modelId] = next
        return next
    }

    fun downloadGate(modelId: String, profile: DeviceProfile): ModelStatus {
        val model = record(modelId).model
        return when {
            model.checksum == "REQUIRES_OFFICIAL_CHECKSUM" -> ModelStatus.Blocked("Official checksum is not pinned")
            profile.availableRamMb < model.minRamMb -> ModelStatus.Blocked("Device RAM is below recommended minimum")
            profile.freeStorageBytes < model.bytes * 2 -> ModelStatus.Blocked("Not enough free storage for atomic download")
            else -> ModelStatus.NotDownloaded
        }
    }

    fun snapshot(): List<PersistedModelRecord> = records.values
        .filter { it.status != ModelStatus.NotDownloaded || it.selected || it.model.localPath != null }
        .map { record ->
            when (val status = record.status) {
                ModelStatus.Installed -> PersistedModelRecord(
                    modelId = record.model.id,
                    status = PersistedModelStatus.Installed,
                    localPath = record.model.localPath,
                    selected = record.selected,
                )
                is ModelStatus.Failed -> PersistedModelRecord(
                    modelId = record.model.id,
                    status = PersistedModelStatus.Failed,
                    failedReason = status.reason,
                )
                else -> PersistedModelRecord(
                    modelId = record.model.id,
                    status = PersistedModelStatus.NotDownloaded,
                )
            }
        }

    private fun record(modelId: String): ModelRecord = records[modelId] ?: error("Unknown model: $modelId")
}
