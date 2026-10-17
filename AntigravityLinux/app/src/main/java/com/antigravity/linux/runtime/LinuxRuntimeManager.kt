package com.antigravity.linux.runtime

import android.content.Context
import com.antigravity.linux.data.AntigravityState
import com.antigravity.linux.data.DesktopState
import com.antigravity.linux.data.RuntimeState
import com.antigravity.linux.data.StorageMetrics
import com.antigravity.linux.data.SystemDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class LinuxRuntimeManager(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "archlinux")
    val binDir: File = File(context.filesDir, "bin")
    val prootBin: File = File(binDir, "proot")

    val commandExecutor = CommandExecutor(rootfsDir, prootBin, context)
    val installer = ArchLinuxInstaller(context, rootfsDir, binDir)

    private val _runtimeState = MutableStateFlow(RuntimeState.NOT_INSTALLED)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _desktopState = MutableStateFlow(DesktopState.NOT_STARTED)
    val desktopState: StateFlow<DesktopState> = _desktopState.asStateFlow()

    private val _antigravityState = MutableStateFlow(AntigravityState.NOT_INSTALLED)
    val antigravityState: StateFlow<AntigravityState> = _antigravityState.asStateFlow()

    private val _statusMessage = MutableStateFlow("System Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _storageMetrics = MutableStateFlow(StorageMetrics())
    val storageMetrics: StateFlow<StorageMetrics> = _storageMetrics.asStateFlow()

    private val _diagnostics = MutableStateFlow(SystemDiagnostics())
    val diagnostics: StateFlow<SystemDiagnostics> = _diagnostics.asStateFlow()

    private val _vncLogs = MutableStateFlow("")
    val vncLogs: StateFlow<String> = _vncLogs.asStateFlow()

    private val _installedMode = MutableStateFlow("None")
    val installedMode: StateFlow<String> = _installedMode.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            refreshStatus()
        }
    }

    fun readVncLogs(): String {
        val candidates = listOf(
            File(rootfsDir, "tmp/vncserver.log"),
            File(rootfsDir, "home/antigravity/.vnc/desktop.log")
        )
        for (f in candidates) {
            if (f.exists() && f.length() > 0) {
                try {
                    val lines = f.readLines()
                    val text = lines.takeLast(120).joinToString("\n")
                    _vncLogs.value = text
                    return text
                } catch (_: Exception) {}
            }
        }
        return _vncLogs.value.ifBlank { "VNC server has not generated log output yet." }
    }

    fun clearVncLogs() {
        try {
            File(rootfsDir, "tmp/vncserver.log").writeText("")
            File(rootfsDir, "home/antigravity/.vnc/desktop.log").writeText("")
        } catch (_: Exception) {}
        _vncLogs.value = ""
    }

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val rootfsExists = rootfsDir.exists() && (File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists())
        val cpuInfo = ArchitectureDetector.detect()

        // Read installed Antigravity mode if present
        val modeFile = File(rootfsDir, "opt/antigravity/.installed_mode")
        val currentMode = if (modeFile.exists()) modeFile.readText().trim() else "None"
        _installedMode.value = currentMode

        if (!rootfsExists) {
            _runtimeState.value = RuntimeState.NOT_INSTALLED
            _desktopState.value = DesktopState.NOT_STARTED
            _antigravityState.value = AntigravityState.NOT_INSTALLED
        } else {
            // Check if VNC is responding on 127.0.0.1:5901
            val vncRunning = isPortOpen("127.0.0.1", 5901)
            _desktopState.value = if (vncRunning) DesktopState.RUNNING else DesktopState.NOT_STARTED
            _runtimeState.value = if (vncRunning) RuntimeState.RUNNING else RuntimeState.READY

            // Check Antigravity Desktop
            val antigravityFile = File(rootfsDir, "opt/antigravity/antigravity")
            if (!antigravityFile.exists()) {
                _antigravityState.value = AntigravityState.NOT_INSTALLED
            } else {
                val pgrep = commandExecutor.executeInLinux("pgrep -f /opt/antigravity/antigravity")
                _antigravityState.value = if (pgrep.isSuccess && pgrep.stdout.isNotBlank()) {
                    AntigravityState.RUNNING
                } else {
                    AntigravityState.NOT_RUNNING
                }
            }
        }

        readVncLogs()
        updateStorageMetrics()
        updateDiagnostics(cpuInfo, rootfsExists)
    }

    suspend fun initializeEnvironment(listener: InstallProgressListener): Boolean {
        _runtimeState.value = RuntimeState.INITIALIZING
        _statusMessage.value = "Starting Arch Linux installation..."

        val success = installer.install(object : InstallProgressListener {
            override fun onProgress(stage: String, percent: Int, detail: String) {
                _statusMessage.value = "[$percent%] $detail"
                listener.onProgress(stage, percent, detail)
            }

            override fun onError(stage: String, error: String) {
                _runtimeState.value = RuntimeState.ERROR
                _statusMessage.value = "Error ($stage): $error"
                listener.onError(stage, error)
            }

            override fun onSuccess(detail: String) {
                _runtimeState.value = RuntimeState.READY
                _statusMessage.value = detail
                listener.onSuccess(detail)
            }
        })

        refreshStatus()
        return success
    }

    suspend fun installAntigravity(mode: com.antigravity.linux.data.AntigravityMode, listener: InstallProgressListener): Boolean = withContext(Dispatchers.IO) {
        if (_runtimeState.value == RuntimeState.NOT_INSTALLED) {
            withContext(Dispatchers.Main) {
                listener.onError("Prerequisites", "Linux rootfs is not installed. Tap '1. Initialize Ultra-Light Linux' first.")
            }
            return@withContext false
        }

        _antigravityState.value = AntigravityState.STARTING
        _statusMessage.value = "Starting setup of ${mode.label}..."
        withContext(Dispatchers.Main) {
            listener.onProgress("Setup", 10, "Preparing package manager for ${mode.label}...")
        }

        val script = if (mode == com.antigravity.linux.data.AntigravityMode.IDE) "/usr/local/bin/setup-antigravity-ide" else "/usr/local/bin/setup-antigravity-app"

        var stepPercent = 15
        val result = commandExecutor.executeInLinuxStream(
            command = script,
            workDir = "/root",
            asUser = "root",
            timeoutSeconds = 600,
            onLine = { line ->
                val clean = line.trim()
                if (clean.isNotBlank()) {
                    stepPercent = (stepPercent + 1).coerceAtMost(92)
                    scope.launch(Dispatchers.Main) {
                        _statusMessage.value = clean
                        listener.onProgress("Setup", stepPercent, clean)
                    }
                }
            }
        )

        refreshStatus()

        val launcherExists = File(rootfsDir, "opt/antigravity/antigravity").exists() && File(rootfsDir, "opt/antigravity/antigravity").length() > 0
        if (result.isSuccess || launcherExists) {
            _antigravityState.value = AntigravityState.NOT_RUNNING
            withContext(Dispatchers.Main) {
                listener.onProgress("Complete", 100, "${mode.label} ready!")
                listener.onSuccess("${mode.label} installed successfully! Ready to launch.")
            }
            true
        } else {
            _antigravityState.value = AntigravityState.NOT_INSTALLED
            val err = result.stderr.ifBlank { result.stdout }.ifBlank { "Exit code ${result.exitCode}" }
            withContext(Dispatchers.Main) {
                listener.onError("Setup", "Installation failed: $err")
            }
            false
        }
    }

    suspend fun startDesktop(): Boolean = withContext(Dispatchers.IO) {
        if (_runtimeState.value == RuntimeState.NOT_INSTALLED) {
            _statusMessage.value = "Cannot start desktop: Linux is not installed."
            return@withContext false
        }

        _desktopState.value = DesktopState.STARTING
        _statusMessage.value = "Starting Linux X11/VNC desktop session..."

        // Launch start-desktop concurrently
        val desktopJob = scope.launch(Dispatchers.IO) {
            commandExecutor.executeInLinux("/usr/local/bin/start-desktop", asUser = "antigravity", timeoutSeconds = 30)
        }

        // Wait up to 10 seconds for port 5901 to become open
        var attempts = 0
        while (attempts < 20) {
            kotlinx.coroutines.delay(500)
            if (isPortOpen("127.0.0.1", 5901)) {
                _desktopState.value = DesktopState.RUNNING
                _runtimeState.value = RuntimeState.RUNNING
                _statusMessage.value = "Desktop session active on localhost:5901 (:1)"
                readVncLogs()
                return@withContext true
            }
            attempts++
        }

        readVncLogs()
        val currentLogs = _vncLogs.value
        _desktopState.value = DesktopState.ERROR
        if (currentLogs.contains("not found", ignoreCase = true) || currentLogs.contains("Install Antigravity", ignoreCase = true)) {
            _statusMessage.value = "Desktop not installed. Tap 'Install Antigravity' to choose Desktop App or IDE."
        } else {
            _statusMessage.value = "Desktop failed to start: $currentLogs"
        }
        return@withContext false
    }

    suspend fun stopDesktop(): Boolean = withContext(Dispatchers.IO) {
        _statusMessage.value = "Stopping Linux desktop session..."
        val result = commandExecutor.executeInLinux("/usr/local/bin/stop-desktop")

        _desktopState.value = DesktopState.NOT_STARTED
        _runtimeState.value = RuntimeState.READY
        _statusMessage.value = "Desktop session stopped."
        refreshStatus()
        return@withContext true
    }

    suspend fun launchAntigravity(): Boolean = withContext(Dispatchers.IO) {
        val antigravityFile = File(rootfsDir, "opt/antigravity/antigravity")
        if (!antigravityFile.exists()) {
            _antigravityState.value = AntigravityState.NOT_INSTALLED
            _statusMessage.value = "Antigravity Desktop is not installed in /opt/antigravity"
            return@withContext false
        }

        if (_desktopState.value != DesktopState.RUNNING) {
            _statusMessage.value = "Starting desktop session first..."
            val desktopStarted = startDesktop()
            if (!desktopStarted) {
                _statusMessage.value = "Failed to start desktop for Antigravity"
                return@withContext false
            }
        }

        _antigravityState.value = AntigravityState.STARTING
        _statusMessage.value = "Launching Antigravity Desktop on DISPLAY=:1..."

        // Launch in background
        val launchJob = scope.launch(Dispatchers.IO) {
            commandExecutor.executeInLinux("/usr/local/bin/launch-antigravity", asUser = "antigravity", timeoutSeconds = 30)
        }

        var attempts = 0
        while (attempts < 10) {
            kotlinx.coroutines.delay(500)
            val pgrep = commandExecutor.executeInLinux("pgrep -f /opt/antigravity/antigravity")
            if (pgrep.isSuccess && pgrep.stdout.isNotBlank()) {
                _antigravityState.value = AntigravityState.RUNNING
                _statusMessage.value = "Antigravity Desktop is running (PID ${pgrep.stdout.trim()})"
                return@withContext true
            }
            attempts++
        }

        _antigravityState.value = AntigravityState.ERROR
        _statusMessage.value = "Antigravity failed to launch."
        return@withContext false
    }

    suspend fun stopAntigravity(): Boolean = withContext(Dispatchers.IO) {
        _statusMessage.value = "Stopping Antigravity Desktop..."
        commandExecutor.executeInLinux("pkill -f /opt/antigravity/antigravity")
        _antigravityState.value = AntigravityState.NOT_RUNNING
        _statusMessage.value = "Antigravity stopped."
        return@withContext true
    }

    suspend fun stopAll(): Boolean = withContext(Dispatchers.IO) {
        _statusMessage.value = "Stopping all Linux processes..."
        stopAntigravity()
        stopDesktop()
        commandExecutor.executeInLinux("pkill -u antigravity")
        _runtimeState.value = RuntimeState.STOPPED
        _statusMessage.value = "Linux environment stopped."
        return@withContext true
    }

    suspend fun deleteEnvironment(): Boolean = withContext(Dispatchers.IO) {
        stopAll()
        val deleted = installer.deleteEnvironment()
        if (deleted) {
            _runtimeState.value = RuntimeState.NOT_INSTALLED
            _desktopState.value = DesktopState.NOT_STARTED
            _antigravityState.value = AntigravityState.NOT_INSTALLED
            _statusMessage.value = "Linux environment successfully removed."
        }
        updateStorageMetrics()
        return@withContext deleted
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateStorageMetrics() {
        try {
            val rootfsSize = if (rootfsDir.exists()) calculateDirSize(rootfsDir) else 0L
            val homeDir = File(rootfsDir, "home/antigravity")
            val homeSize = if (homeDir.exists()) calculateDirSize(homeDir) else 0L

            val statFs = android.os.StatFs(context.filesDir.absolutePath)
            val freeBytes = statFs.availableBlocksLong * statFs.blockSizeLong
            val totalBytes = statFs.blockCountLong * statFs.blockSizeLong

            _storageMetrics.value = StorageMetrics(
                rootfsSizeBytes = rootfsSize,
                homeSizeBytes = homeSize,
                freeSpaceBytes = freeBytes,
                totalDeviceStorageBytes = totalBytes
            )
        } catch (_: Exception) {}
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .fold(0L) { acc, file -> acc + file.length() }
        } catch (_: Exception) {
            0L
        }
    }

    private suspend fun updateDiagnostics(cpuInfo: ArchitectureDetector.CpuInfo, rootfsExists: Boolean) {
        var glibc = "Not detected"
        var missingLibs = mutableListOf<String>()

        if (rootfsExists) {
            val glibcResult = commandExecutor.executeInLinux("ldd --version | head -n 1")
            if (glibcResult.isSuccess) {
                glibc = glibcResult.stdout.trim()
            }

            // Check desktop libraries
            val checkLibs = listOf(
                "libgtk-3.so.0", "libnss3.so", "libasound.so.2", "libX11.so.6", "libgbm.so.1"
            )
            for (lib in checkLibs) {
                val libCheck = commandExecutor.executeInLinux("ldconfig -p | grep $lib")
                if (!libCheck.isSuccess || libCheck.stdout.isBlank()) {
                    missingLibs.add(lib)
                }
            }
        }

        val antigravityFile = File(rootfsDir, "opt/antigravity/antigravity")
        val isArm64Bin = if (antigravityFile.exists()) {
            val fileCheck = commandExecutor.executeInLinux("file /opt/antigravity/antigravity")
            fileCheck.stdout.contains("ARM aarch64", ignoreCase = true)
        } else false

        _diagnostics.value = SystemDiagnostics(
            cpuArch = cpuInfo.architecture,
            isArm64 = cpuInfo.isArm64,
            kernelRelease = cpuInfo.kernelVersion,
            androidApiLevel = android.os.Build.VERSION.SDK_INT,
            prootAvailable = commandExecutor.getEffectiveProot().exists(),
            rootfsExists = rootfsExists,
            rootfsPath = rootfsDir.absolutePath,
            glibcVersion = glibc,
            desktopInstalled = File(rootfsDir, "usr/bin/xfce4-session").exists() || File(rootfsDir, "usr/bin/openbox").exists(),
            displayPort = 5901,
            vncPortOpen = isPortOpen("127.0.0.1", 5901),
            installedMode = _installedMode.value,
            antigravityBinaryExists = antigravityFile.exists(),
            antigravityArm64Verified = isArm64Bin,
            antigravityProcessRunning = _antigravityState.value == AntigravityState.RUNNING,
            missingLibraries = missingLibs,
            compatibilitySummary = if (!cpuInfo.isArm64) {
                "Incompatible: Host device is ${cpuInfo.architecture}, requires aarch64."
            } else if (!rootfsExists) {
                "Rootfs not installed. Run initialization."
            } else if (!antigravityFile.exists()) {
                "Linux ARM64 ready. Antigravity Desktop not yet installed."
            } else if (!isArm64Bin) {
                "Antigravity binary architecture mismatch: not ARM64."
            } else {
                "Fully compatible: ARM64 userspace, glibc, and Antigravity binary verified (${_installedMode.value})."
            }
        )
    }
}
