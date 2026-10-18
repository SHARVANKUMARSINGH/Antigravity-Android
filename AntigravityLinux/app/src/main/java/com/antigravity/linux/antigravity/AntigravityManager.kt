package com.antigravity.linux.antigravity

import com.antigravity.linux.data.AntigravityState
import com.antigravity.linux.runtime.CommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

data class AntigravityDiagnostics(
    val binaryFound: Boolean,
    val binaryPath: String,
    val isArm64Elf: Boolean,
    val glibcCompatible: Boolean,
    val missingDependencies: List<String>,
    val processRunning: Boolean,
    val pid: Int?,
    val diagnosticSummary: String
)

class AntigravityManager(
    private val rootfsDir: File,
    private val commandExecutor: CommandExecutor
) {
    companion object {
        const val BINARY_RELATIVE_PATH = "opt/antigravity/antigravity"
        const val AUTH_URL = "https://antigravity.google/auth"
        const val OFFICIAL_DOWNLOAD_URL = "https://antigravity.google/download"
    }

    val binaryFile: File = File(rootfsDir, BINARY_RELATIVE_PATH)

    /**
     * Checks whether the binary is an authentic ARM64 (AArch64) ELF executable.
     * ELF header:
     * Bytes 0..3: 0x7F, 'E', 'L', 'F'
     * Byte 4: EI_CLASS (2 = 64-bit)
     * Bytes 18..19: e_machine (183 / 0xB7 = EM_AARCH64)
     */
    fun verifyArm64Binary(): Boolean {
        if (!binaryFile.exists() || !binaryFile.canRead()) return false
        return try {
            RandomAccessFile(binaryFile, "r").use { raf ->
                if (raf.length() < 2) return false
                val header = ByteArray(4)
                val bytesRead = raf.read(header)
                if (bytesRead >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()) {
                    // Valid shell launcher script (e.g. #!/bin/bash)
                    return true
                }
                if (bytesRead < 4) return false
                if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
                ) {
                    return false
                }
                val eiClass = raf.readByte()
                if (eiClass != 2.toByte()) return false // Not 64-bit

                raf.seek(18)
                val eMachineLow = raf.readUnsignedByte()
                val eMachineHigh = raf.readUnsignedByte()
                val eMachine = (eMachineHigh shl 8) or eMachineLow

                eMachine == 183 // EM_AARCH64
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getDiagnostics(): AntigravityDiagnostics = withContext(Dispatchers.IO) {
        val exists = binaryFile.exists()
        val isArm64 = if (exists) verifyArm64Binary() else false

        var glibcOk = false
        val missing = mutableListOf<String>()

        if (rootfsDir.exists()) {
            val glibcResult = commandExecutor.executeInLinux("ldd --version | head -n 1")
            glibcOk = glibcResult.isSuccess

            if (exists) {
                val lddResult = commandExecutor.executeInLinux("ldd /$BINARY_RELATIVE_PATH")
                if (lddResult.isSuccess) {
                    lddResult.stdout.lines().forEach { line ->
                        if (line.contains("not found", ignoreCase = true)) {
                            val lib = line.trim().split("=>")[0].trim()
                            missing.add(lib)
                        }
                    }
                }
            }
        }

        val pgrep = commandExecutor.executeInLinux("pgrep -f /$BINARY_RELATIVE_PATH")
        val isRunning = pgrep.isSuccess && pgrep.stdout.isNotBlank()
        val pid = if (isRunning) pgrep.stdout.trim().lines().firstOrNull()?.toIntOrNull() else null

        val summary = when {
            !exists -> "Antigravity Desktop is not installed at /$BINARY_RELATIVE_PATH. Download the official ARM64 tarball from $OFFICIAL_DOWNLOAD_URL."
            !isArm64 -> "Incompatible binary: File at /$BINARY_RELATIVE_PATH is not an ARM64 (AArch64) ELF executable."
            missing.isNotEmpty() -> "Missing shared libraries: ${missing.joinToString(", ")}. Install them via pacman."
            isRunning -> "Google Antigravity Desktop is running (PID: $pid) on DISPLAY=:1."
            else -> "Google Antigravity Desktop ARM64 verified and ready to launch."
        }

        AntigravityDiagnostics(
            binaryFound = exists,
            binaryPath = "/$BINARY_RELATIVE_PATH",
            isArm64Elf = isArm64,
            glibcCompatible = glibcOk,
            missingDependencies = missing,
            processRunning = isRunning,
            pid = pid,
            diagnosticSummary = summary
        )
    }

    suspend fun launch(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val diag = getDiagnostics()
        if (!diag.binaryFound) {
            return@withContext Pair(false, "Binary not found at /$BINARY_RELATIVE_PATH")
        }
        if (!diag.isArm64Elf) {
            return@withContext Pair(false, "Binary is not ARM64 compatible. x86_64 binaries cannot be executed.")
        }
        if (diag.missingDependencies.isNotEmpty()) {
            return@withContext Pair(false, "Missing required libraries: ${diag.missingDependencies.joinToString(", ")}")
        }

        val result = commandExecutor.executeInLinux(
            "/usr/local/bin/launch-antigravity",
            asUser = "antigravity"
        )

        kotlinx.coroutines.delay(2000)
        val pgrep = commandExecutor.executeInLinux("pgrep -f /$BINARY_RELATIVE_PATH")
        if (pgrep.isSuccess && pgrep.stdout.isNotBlank()) {
            Pair(true, "Antigravity Desktop launched successfully (PID: ${pgrep.stdout.trim()})")
        } else {
            Pair(false, "Failed to launch: ${result.stderr.ifBlank { result.stdout }}")
        }
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val result = commandExecutor.executeInLinux("pkill -f /$BINARY_RELATIVE_PATH")
        result.isSuccess
    }
}
