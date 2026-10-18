package com.antigravity.linux.data

/**
 * Lifecycle states of the Arch Linux ARM64 userspace runtime.
 */
enum class RuntimeState(val label: String) {
    NOT_INSTALLED("Not Installed"),
    INITIALIZING("Initializing"),
    DOWNLOADING("Downloading"),
    EXTRACTING("Extracting"),
    READY("Ready"),
    STARTING("Starting"),
    RUNNING("Running"),
    STOPPED("Stopped"),
    ERROR("Error")
}

/**
 * States of the X11 / VNC graphical desktop session.
 */
enum class DesktopState(val label: String) {
    NOT_STARTED("Not Started"),
    STARTING("Starting"),
    RUNNING("Running"),
    DISCONNECTED("Disconnected"),
    ERROR("Error")
}

/**
 * States of Google Antigravity Desktop application.
 */
enum class AntigravityState(val label: String) {
    NOT_INSTALLED("Not Installed"),
    CHECKING("Checking"),
    STARTING("Starting"),
    RUNNING("Running"),
    NOT_RUNNING("Ready"),
    UNSUPPORTED("Unsupported"),
    ERROR("Error")
}

/**
 * Storage metrics for the Linux environment.
 */
data class StorageMetrics(
    val rootfsSizeBytes: Long = 0L,
    val homeSizeBytes: Long = 0L,
    val freeSpaceBytes: Long = 0L,
    val totalDeviceStorageBytes: Long = 0L
) {
    val rootfsFormatted: String get() = formatBytes(rootfsSizeBytes)
    val homeFormatted: String get() = formatBytes(homeSizeBytes)
    val freeFormatted: String get() = formatBytes(freeSpaceBytes)
    val totalFormatted: String get() = formatBytes(totalDeviceStorageBytes)

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1024) {
            String.format("%.2f GB", mb / 1024)
        } else {
            String.format("%.1f MB", mb)
        }
    }
}

/**
 * Installation mode for Antigravity environment.
 */
enum class AntigravityMode(val id: String, val label: String, val description: String) {
    APP("app", "Antigravity Desktop App", "Ultra-light X11 desktop (Openbox + X11VNC) with Antigravity Desktop GUI and CLI tools"),
    IDE("ide", "Antigravity Desktop IDE", "Developer Cloud/Desktop IDE (VS Code & Code-Server) with terminal, git, python & node")
}

/**
 * Diagnostics information for system architecture, runtime, and Antigravity compatibility.
 */
data class SystemDiagnostics(
    val cpuArch: String = "Unknown",
    val isArm64: Boolean = false,
    val kernelRelease: String = "",
    val androidApiLevel: Int = 0,
    val prootAvailable: Boolean = false,
    val rootfsExists: Boolean = false,
    val rootfsPath: String = "",
    val glibcVersion: String = "Unknown",
    val desktopInstalled: Boolean = false,
    val displayPort: Int = 5901,
    val vncPortOpen: Boolean = false,
    val installedMode: String = "None",
    val antigravityBinaryExists: Boolean = false,
    val antigravityArm64Verified: Boolean = false,
    val antigravityProcessRunning: Boolean = false,
    val missingLibraries: List<String> = emptyList(),
    val compatibilitySummary: String = ""
)
