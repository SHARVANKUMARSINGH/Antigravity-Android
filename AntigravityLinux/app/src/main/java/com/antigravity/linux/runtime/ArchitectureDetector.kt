package com.antigravity.linux.runtime

import android.os.Build
import java.io.File

object ArchitectureDetector {

    data class CpuInfo(
        val architecture: String,
        val isArm64: Boolean,
        val supportedAbis: List<String>,
        val features: List<String>,
        val kernelVersion: String
    )

    fun detect(): CpuInfo {
        val osArch = System.getProperty("os.arch") ?: "unknown"
        val abis = Build.SUPPORTED_ABIS.toList()
        val isArm64 = abis.any { it.contains("arm64-v8a", ignoreCase = true) } ||
                osArch.contains("aarch64", ignoreCase = true)

        val features = mutableListOf<String>()
        val cpuinfoFile = File("/proc/cpuinfo")
        if (cpuinfoFile.exists() && cpuinfoFile.canRead()) {
            try {
                cpuinfoFile.forEachLine { line ->
                    if (line.startsWith("Features", ignoreCase = true)) {
                        val parts = line.split(":")
                        if (parts.size > 1) {
                            features.addAll(parts[1].trim().split(" "))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val kernel = System.getProperty("os.version") ?: "unknown"

        return CpuInfo(
            architecture = if (isArm64) "aarch64" else osArch,
            isArm64 = isArm64,
            supportedAbis = abis,
            features = features.distinct(),
            kernelVersion = kernel
        )
    }
}
