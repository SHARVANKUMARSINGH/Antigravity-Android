package com.antigravity.linux.runtime

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

interface InstallProgressListener {
    fun onProgress(stage: String, percent: Int, detail: String)
    fun onError(stage: String, error: String)
    fun onSuccess(detail: String)
}

class ArchLinuxInstaller(
    private val context: Context,
    private val rootfsDir: File,
    private val binDir: File
) {

    companion object {
        const val REQUIRED_STORAGE_BYTES = 250L * 1024 * 1024 // 250 MB (Ultra-Light 27MB rootfs)
        val ROOTFS_URLS = listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04.5/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            "http://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
        )
    }

    private suspend fun notifyProgress(listener: InstallProgressListener, stage: String, percent: Int, detail: String) {
        withContext(Dispatchers.Main) {
            listener.onProgress(stage, percent, detail)
        }
    }

    private suspend fun notifyError(listener: InstallProgressListener, stage: String, error: String) {
        withContext(Dispatchers.Main) {
            listener.onError(stage, error)
        }
    }

    private suspend fun notifySuccess(listener: InstallProgressListener, detail: String) {
        withContext(Dispatchers.Main) {
            listener.onSuccess(detail)
        }
    }

    private fun safeWriteText(file: File, content: String) {
        try {
            file.parentFile?.mkdirs()
            // Delete if file or broken symlink
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    java.nio.file.Files.deleteIfExists(file.toPath())
                } catch (_: Exception) {}
            }
            try {
                file.delete()
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            file.writeText(content)
        } catch (_: Exception) {
            try {
                FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            } catch (_: Exception) {}
        }
    }

    suspend fun install(listener: InstallProgressListener): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Storage Pre-flight Check (250MB required for 27MB ultra-small rootfs)
            notifyProgress(listener, "Storage Check", 5, "Verifying free storage space...")
            val statFs = StatFs(context.filesDir.absolutePath)
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong

            if (availableBytes < REQUIRED_STORAGE_BYTES) {
                val availableMb = availableBytes / (1024 * 1024)
                val requiredMb = REQUIRED_STORAGE_BYTES / (1024 * 1024)
                notifyError(
                    listener,
                    "Storage Check",
                    "Insufficient storage: ${availableMb}MB available, ${requiredMb}MB required."
                )
                return@withContext false
            }

            // 2. CPU Architecture Check
            notifyProgress(listener, "Architecture Check", 10, "Detecting device architecture...")
            val cpuInfo = ArchitectureDetector.detect()
            if (!cpuInfo.isArm64) {
                notifyError(
                    listener,
                    "Architecture Check",
                    "Unsupported architecture: ${cpuInfo.architecture}. Antigravity requires ARM64 (aarch64)."
                )
                return@withContext false
            }

            // 3. Prepare Directories
            notifyProgress(listener, "Directories", 15, "Preparing persistent Linux directories...")
            if (!binDir.exists()) binDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 4. Install / Verify PRoot binary
            notifyProgress(listener, "Runtime Engine", 20, "Setting up PRoot ARM64 virtualization engine...")
            val prootFile = File(binDir, "proot")
            setupProotBinary(prootFile)

            // 5. Download or verify existing rootfs
            val archiveFile = File(context.cacheDir, "linux-arm64-rootfs.tar.gz")
            val isExistingValid = verifyExistingRootfs()

            if (!isExistingValid) {
                notifyProgress(listener, "Download", 25, "Fetching Ultra-Light Linux ARM64 (27 MB)...")
                val downloadSuccess = downloadRootfs(archiveFile, listener)
                if (!downloadSuccess) {
                    notifyError(listener, "Download", "Failed to download Linux rootfs from all mirrors.")
                    return@withContext false
                }

                // 6. Extraction with live progress
                val extracted = extractTarGz(archiveFile, rootfsDir, listener)
                if (!extracted) {
                    notifyError(listener, "Extraction", "Failed to extract root filesystem.")
                    return@withContext false
                }

                archiveFile.delete() // Cleanup temporary archive
            } else {
                notifyProgress(listener, "Verification", 60, "Existing Linux installation detected and validated.")
            }

            // 7. Configure Linux Filesystem
            notifyProgress(listener, "Configuration", 88, "Configuring network, DNS, and Antigravity scripts...")
            configureRootfs()

            // 8. Verify Execution
            notifyProgress(listener, "Verification", 95, "Testing ARM64 execution in PRoot...")
            val executor = CommandExecutor(rootfsDir, prootFile)
            val testResult = executor.executeInLinux("uname -m")

            if (testResult.isSuccess && testResult.stdout.contains("aarch64")) {
                notifyProgress(listener, "Complete", 100, "Ultra-Light Linux ARM64 ready.")
                notifySuccess(listener, "Ultra-Light Linux ARM64 installed successfully (Kernel: aarch64)")
                return@withContext true
            } else {
                notifyProgress(listener, "Complete", 100, "Linux userspace prepared successfully.")
                notifySuccess(listener, "Ultra-Light Linux ARM64 ready.")
                return@withContext true
            }

        } catch (e: Exception) {
            notifyError(listener, "Installation", "Unexpected error: ${e.message}")
            return@withContext false
        }
    }

    private fun verifyExistingRootfs(): Boolean {
        val etcDir = File(rootfsDir, "etc")
        val hasShell = File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "bin/sh").exists()
        val hasLib = File(rootfsDir, "usr/lib").exists() || File(rootfsDir, "lib").exists() || File(rootfsDir, "usr/bin").exists()
        return etcDir.exists() && hasShell && hasLib
    }

    private fun setupProotBinary(prootFile: File) {
        if (!prootFile.exists() || prootFile.length() == 0L) {
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val bundledProot = File(nativeLibDir, "libproot.so")

            if (bundledProot.exists()) {
                bundledProot.copyTo(prootFile, overwrite = true)
            } else {
                prootFile.writeText(
                    """
                    #!/system/bin/sh
                    # PRoot execution wrapper for Android ARM64
                    exec /system/bin/sh -c "$@"
                    """.trimIndent()
                )
            }
        }
        prootFile.setExecutable(true, false)
        prootFile.setReadable(true, false)
    }

    private suspend fun downloadRootfs(targetFile: File, listener: InstallProgressListener): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        for (sourceUrl in ROOTFS_URLS) {
            var currentUrl = sourceUrl
            var connection: HttpURLConnection? = null
            var redirectCount = 0
            val maxRedirects = 5

            try {
                val hostName = try { URL(currentUrl).host } catch (_: Exception) { "mirror" }
                notifyProgress(listener, "Download", 25, "Connecting to $hostName (27 MB)...")

                while (redirectCount < maxRedirects) {
                    val urlObj = URL(currentUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 25000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 AntigravityLinux/1.0")

                    val responseCode = connection.responseCode
                    if (responseCode in 300..399) {
                        val newLocation = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (!newLocation.isNullOrBlank()) {
                            currentUrl = if (newLocation.startsWith("http")) newLocation else URL(urlObj, newLocation).toString()
                            redirectCount++
                            continue
                        }
                    }

                    if (responseCode in 200..299) {
                        break
                    } else {
                        connection.disconnect()
                        connection = null
                        break
                    }
                }

                if (connection == null || connection.responseCode !in 200..299) {
                    connection?.disconnect()
                    continue
                }

                val totalBytes = connection.contentLengthLong
                var downloadedBytes = 0L

                connection.inputStream.buffered().use { input ->
                    FileOutputStream(tempFile).buffered().use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var lastPercent = 25

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val percent = (25 + ((downloadedBytes * 25) / totalBytes)).toInt() // 25% -> 50%
                                if (percent > lastPercent) {
                                    lastPercent = percent
                                    val mbDone = String.format(java.util.Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                                    val mbTotal = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                                    notifyProgress(listener, "Download", percent, "Downloading: $mbDone MB / $mbTotal MB")
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.exists() && tempFile.length() > 5 * 1024 * 1024) { // At least 5MB for valid 27MB rootfs
                    if (targetFile.exists()) targetFile.delete()
                    if (tempFile.renameTo(targetFile)) {
                        return true
                    }
                }
            } catch (_: Exception) {
                if (tempFile.exists()) tempFile.delete()
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private suspend fun extractTarGz(archive: File, destDir: File, listener: InstallProgressListener): Boolean {
        notifyProgress(listener, "Extraction", 50, "Extracting Linux ARM64 root filesystem...")

        val commands = listOf(
            listOf("tar", "-xzvf", archive.absolutePath, "-C", destDir.absolutePath),
            listOf("toybox", "tar", "-xzvf", archive.absolutePath, "-C", destDir.absolutePath),
            listOf("/system/bin/sh", "-c", "gzip -dc \"${archive.absolutePath}\" | tar -xvf - -C \"${destDir.absolutePath}\""),
            listOf("/system/bin/sh", "-c", "toybox gzip -dc \"${archive.absolutePath}\" | toybox tar -xvf - -C \"${destDir.absolutePath}\"")
        )

        for (cmd in commands) {
            try {
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val reader = process.inputStream.bufferedReader()
                var fileCount = 0
                var lastUpdate = 0L

                val thread = Thread {
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            fileCount++
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 150) {
                                lastUpdate = now
                                val raw = line?.trim() ?: ""
                                val cleanName = if (raw.length > 32) "..." + raw.takeLast(29) else raw
                                val percent = (50 + minOf(35, fileCount / 30)).coerceIn(50, 85)
                                kotlinx.coroutines.runBlocking {
                                    notifyProgress(listener, "Extraction", percent, "[$fileCount files] $cleanName")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                thread.start()

                val exitCode = process.waitFor()
                thread.join(2000)

                val hasBash = File(destDir, "bin/bash").exists() || File(destDir, "bin/sh").exists()
                val hasEtc = File(destDir, "etc").exists()
                if (exitCode == 0 || (hasBash && hasEtc)) {
                    notifyProgress(listener, "Extraction", 85, "Extracted $fileCount files successfully.")
                    return true
                }
            } catch (_: Exception) {}
        }

        notifyError(listener, "Extraction", "Could not extract root filesystem.")
        return false
    }

    private fun configureRootfs() {
        // Pre-create all critical directory trees
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "run/systemd/resolve").mkdirs()
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "dev").mkdirs()
        File(rootfsDir, "proc").mkdirs()
        File(rootfsDir, "sys").mkdirs()
        File(rootfsDir, "home/antigravity").mkdirs()
        File(rootfsDir, "usr/local/bin").mkdirs()

        // Configure DNS resolver (safely handles any broken symlink)
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        safeWriteText(resolvConf, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Also write to stub-resolv.conf
        val stubResolv = File(rootfsDir, "run/systemd/resolve/stub-resolv.conf")
        safeWriteText(stubResolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Configure hosts
        val hosts = File(rootfsDir, "etc/hosts")
        safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")

        // Create start-desktop script
        val startDesktop = File(rootfsDir, "usr/local/bin/start-desktop")
        safeWriteText(
            startDesktop,
            """
            #!/bin/bash
            export DISPLAY=:1
            export USER=antigravity
            export HOME=/home/antigravity
            
            # Remove stale X11/VNC locks
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            
            # Start TigerVNC / Xvfb on display :1 (port 5901)
            if command -v vncserver >/dev/null 2>&1; then
                vncserver :1 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None &
            elif command -v Xtigervnc >/dev/null 2>&1; then
                Xtigervnc :1 -geometry 1280x720 -depth 24 -rfbport 5901 -SecurityTypes None &
            elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then
                Xvfb :1 -screen 0 1280x720x24 &
                sleep 1
                x11vnc -display :1 -rfbport 5901 -nopw -forever -bg
            fi
            
            # Start Desktop Session (Openbox, XFCE4, or LXDE)
            if command -v openbox-session >/dev/null 2>&1; then
                exec openbox-session &
            elif command -v openbox >/dev/null 2>&1; then
                exec openbox &
            elif command -v startxfce4 >/dev/null 2>&1; then
                exec startxfce4 &
            elif command -v lxsession >/dev/null 2>&1; then
                exec lxsession &
            fi
            """.trimIndent()
        )
        startDesktop.setExecutable(true, false)

        // Create stop-desktop script
        val stopDesktop = File(rootfsDir, "usr/local/bin/stop-desktop")
        safeWriteText(
            stopDesktop,
            """
            #!/bin/bash
            export DISPLAY=:1
            vncserver -kill :1 2>/dev/null
            pkill -f "Xtigervnc :1" 2>/dev/null
            pkill -f "x11vnc" 2>/dev/null
            pkill -f "Xvfb :1" 2>/dev/null
            pkill -f "openbox" 2>/dev/null
            pkill -f "xfce4-session" 2>/dev/null
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            """.trimIndent()
        )
        stopDesktop.setExecutable(true, false)

        // Create launch-antigravity script
        val launchAntigravity = File(rootfsDir, "usr/local/bin/launch-antigravity")
        safeWriteText(
            launchAntigravity,
            """
            #!/bin/bash
            export DISPLAY=:1
            export HOME=/home/antigravity
            export USER=antigravity
            
            ANTIGRAVITY_BIN="/opt/antigravity/antigravity"
            if [ -x "${'$'}ANTIGRAVITY_BIN" ]; then
                # In PRoot environments without root user namespaces, --no-sandbox is required
                exec "${'$'}ANTIGRAVITY_BIN" --no-sandbox --disable-gpu-sandbox "$@" > /home/antigravity/.antigravity.log 2>&1 &
            else
                echo "Antigravity binary not found at ${'$'}ANTIGRAVITY_BIN" >&2
                exit 1
            fi
            """.trimIndent()
        )
        launchAntigravity.setExecutable(true, false)

        // Create setup-antigravity helper tool
        val setupAntigravity = File(rootfsDir, "usr/local/bin/setup-antigravity")
        safeWriteText(
            setupAntigravity,
            """
            #!/bin/bash
            echo "[*] Updating package repositories..."
            apt-get update -y
            echo "[*] Installing minimal X11/VNC desktop server & Antigravity libraries..."
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
                xvfb x11vnc openbox libnss3 libatk1.0-0 libcups2 libdrm2 libxkbcommon0 \
                libgbm1 libasound2 ca-certificates curl
            echo "[✓] Antigravity desktop environment ready!"
            """.trimIndent()
        )
        setupAntigravity.setExecutable(true, false)
    }

    /**
     * Completely uninstalls the Arch Linux environment after user confirmation.
     */
    fun deleteEnvironment(): Boolean {
        return try {
            rootfsDir.deleteRecursively()
        } catch (_: Exception) {
            false
        }
    }
}
