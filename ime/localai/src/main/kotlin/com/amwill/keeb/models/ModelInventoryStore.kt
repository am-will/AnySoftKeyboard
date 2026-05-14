package com.amwill.keeb.models

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64

interface ModelInventoryStore {
    fun load(): List<PersistedModelRecord>
    fun save(records: List<PersistedModelRecord>)
}

class SharedPreferencesModelInventoryStore(context: Context) : ModelInventoryStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): List<PersistedModelRecord> =
        ModelInventoryCodec.decode(preferences.getString(KEY_RECORDS, "").orEmpty())

    override fun save(records: List<PersistedModelRecord>) {
        preferences.edit().putString(KEY_RECORDS, ModelInventoryCodec.encode(records)).apply()
    }

    private companion object {
        const val FILE_NAME = "keeb_model_inventory"
        const val KEY_RECORDS = "records"
    }
}

object ModelInventoryCodec {
    fun encode(records: List<PersistedModelRecord>): String = records.joinToString("\n") { record ->
        listOf(
            record.modelId,
            record.status.name,
            record.localPath.orEmpty(),
            record.selected.toString(),
            record.failedReason.orEmpty(),
        ).joinToString("|") { it.b64() }
    }

    fun decode(value: String): List<PersistedModelRecord> = value
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            try {
                val parts = line.split("|").map { it.unb64() }
                if (parts.size != 5) return@mapNotNull null
                val status = PersistedModelStatus.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
                PersistedModelRecord(
                    modelId = parts[0],
                    status = status,
                    localPath = parts[2].ifBlank { null },
                    selected = parts[3].toBooleanStrictOrNull() ?: false,
                    failedReason = parts[4].ifBlank { null },
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        .toList()

    private fun String.b64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))
    private fun String.unb64(): String = String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
}
