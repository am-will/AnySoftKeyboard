package com.amwill.keeb.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class DeviceCapabilitySummary(
    val profile: DeviceProfile,
    val primaryAbi: String,
    val cpuCores: Int,
) {
    fun recommendationFor(catalog: List<WhisperModel> = ModelCatalog.pinned): List<WhisperModel> =
        catalog
            .filter { profile.availableRamMb >= it.minRamMb && profile.freeStorageBytes >= it.bytes * 2 }
            .sortedWith(compareByDescending<WhisperModel> { it.recommended }.thenBy { it.bytes })
}

class AndroidDeviceCapabilityDetector(private val context: Context) {
    fun detect(): DeviceCapabilitySummary {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val availableRamMb = (memoryInfo.totalMem / BYTES_PER_MIB).toInt().takeIf { it > 0 }
            ?: (activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB)
        return DeviceCapabilitySummary(
            profile = DeviceProfile(
                availableRamMb = availableRamMb,
                freeStorageBytes = context.filesDir.usableSpace,
            ),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        )
    }

    private companion object {
        const val BYTES_PER_MIB = 1024L * 1024L
        const val DEFAULT_MEMORY_CLASS_MB = 1024
    }
}
