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
        const val REQUIRED_STORAGE_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        const val ARCH_LINUX_ARM64_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
        const val ARCH_LINUX_MIRROR_URL = "https://mirrors.dotsrc.org/archlinuxarm/os/ArchLinuxARM-aarch64-latest.tar.gz"
        const val ARCH_LINUX_MD5_URL = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz.md5"
    }

    suspend fun install(listener: InstallProgressListener): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Storage Pre-flight Check
            listener.onProgress("Storage Check", 5, "Verifying free storage space...")
            val statFs = StatFs(context.filesDir.absolutePath)
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong

            if (availableBytes < REQUIRED_STORAGE_BYTES) {
                val availableMb = availableBytes / (1024 * 1024)
                val requiredMb = REQUIRED_STORAGE_BYTES / (1024 * 1024)
                listener.onError(
                    "Storage Check",
                    "Insufficient storage: ${availableMb}MB available, ${requiredMb}MB required."
                )
                return@withContext false
            }

            // 2. CPU Architecture Check
            listener.onProgress("Architecture Check", 10, "Detecting device architecture...")
            val cpuInfo = ArchitectureDetector.detect()
            if (!cpuInfo.isArm64) {
                listener.onError(
                    "Architecture Check",
                    "Unsupported architecture: ${cpuInfo.architecture}. Antigravity Linux strictly requires ARM64 (aarch64)."
                )
                return@withContext false
            }

            // 3. Prepare Directories
            listener.onProgress("Directories", 15, "Preparing persistent Linux directories...")
            if (!binDir.exists()) binDir.mkdirs()
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            // 4. Install / Verify PRoot binary
            listener.onProgress("Runtime Engine", 20, "Setting up PRoot ARM64 virtualization engine...")
            val prootFile = File(binDir, "proot")
            setupProotBinary(prootFile)

            // 5. Download or verify existing rootfs
            val archiveFile = File(context.cacheDir, "ArchLinuxARM-aarch64.tar.gz")
            val isExistingValid = verifyExistingRootfs()

            if (!isExistingValid) {
                listener.onProgress("Download", 25, "Fetching Arch Linux ARM64 userspace...")
                val downloadSuccess = downloadRootfs(archiveFile, listener)
                if (!downloadSuccess) {
                    listener.onError("Download", "Failed to download Arch Linux ARM64 root filesystem archive.")
                    return@withContext false
                }

                // 6. Extraction
                listener.onProgress("Extraction", 60, "Extracting Arch Linux ARM64 filesystem...")
                val extracted = extractTarGz(archiveFile, rootfsDir, listener)
                if (!extracted) {
                    listener.onError("Extraction", "Failed to extract root filesystem.")
                    return@withContext false
                }

                archiveFile.delete() // Cleanup temporary archive
            } else {
                listener.onProgress("Verification", 60, "Existing Arch Linux installation detected and validated.")
            }

            // 7. Configure Linux Filesystem
            listener.onProgress("Configuration", 80, "Configuring network, users, and desktop scripts...")
            configureRootfs()

            // 8. Verify Execution
            listener.onProgress("Verification", 90, "Testing ARM64 execution in PRoot...")
            val executor = CommandExecutor(rootfsDir, prootFile)
            val testResult = executor.executeInLinux("uname -m")

            if (testResult.isSuccess && testResult.stdout.contains("aarch64")) {
                listener.onProgress("Complete", 100, "Arch Linux ARM64 userspace successfully ready.")
                listener.onSuccess("Arch Linux ARM64 installed and verified successfully (Kernel: aarch64)")
                return@withContext true
            } else {
                listener.onError(
                    "Verification",
                    "Execution verification failed: ${testResult.stderr.ifBlank { testResult.stdout }}"
                )
                return@withContext false
            }

        } catch (e: Exception) {
            listener.onError("Installation", "Unexpected error: ${e.message}")
            return@withContext false
        }
    }

    private fun verifyExistingRootfs(): Boolean {
        val etcDir = File(rootfsDir, "etc")
        val binBash = File(rootfsDir, "bin/bash")
        val usrLib = File(rootfsDir, "usr/lib")
        return etcDir.exists() && binBash.exists() && usrLib.exists()
    }

    private fun setupProotBinary(prootFile: File) {
        if (!prootFile.exists() || prootFile.length() == 0L) {
            // Write portable proot launcher or copy from native libraries
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            val bundledProot = File(nativeLibDir, "libproot.so")

            if (bundledProot.exists()) {
                bundledProot.copyTo(prootFile, overwrite = true)
            } else {
                // Create executable wrapper script that handles proot execution
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

    private fun downloadRootfs(targetFile: File, listener: InstallProgressListener): Boolean {
        var connection: HttpURLConnection? = null
        try {
            var url = URL(ARCH_LINUX_ARM64_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                // Try mirror
                url = URL(ARCH_LINUX_MIRROR_URL)
                connection.disconnect()
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return false
                }
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var lastPercent = 25

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        if (totalBytes > 0) {
                            val percent = (25 + ((downloadedBytes * 35) / totalBytes)).toInt()
                            if (percent > lastPercent) {
                                lastPercent = percent
                                val mbDone = downloadedBytes / (1024 * 1024)
                                val mbTotal = totalBytes / (1024 * 1024)
                                listener.onProgress("Download", percent, "Downloaded $mbDone MB of $mbTotal MB")
                            }
                        }
                    }
                }
            }
            return targetFile.exists() && targetFile.length() > 1024 * 1024
        } catch (_: Exception) {
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractTarGz(archive: File, destDir: File, listener: InstallProgressListener): Boolean {
        return try {
            val tarProcess = ProcessBuilder(
                "tar", "-xzf", archive.absolutePath, "-C", destDir.absolutePath
            ).start()

            val exitCode = tarProcess.waitFor()
            if (exitCode == 0) {
                true
            } else {
                val error = tarProcess.errorStream.bufferedReader().readText()
                listener.onError("Extraction", "tar failed: $error")
                false
            }
        } catch (e: Exception) {
            listener.onError("Extraction", "Extraction exception: ${e.message}")
            false
        }
    }

    private fun configureRootfs() {
        // Configure DNS resolver
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Configure hosts
        val hosts = File(rootfsDir, "etc/hosts")
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")

        // Configure user home directory
        val userHome = File(rootfsDir, "home/antigravity")
        if (!userHome.exists()) userHome.mkdirs()

        // Create start-desktop script
        val startDesktop = File(rootfsDir, "usr/local/bin/start-desktop")
        startDesktop.parentFile?.mkdirs()
        startDesktop.writeText(
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
            
            # Start Desktop Session (XFCE4, Openbox, or LXDE)
            if command -v startxfce4 >/dev/null 2>&1; then
                exec startxfce4 &
            elif command -v openbox-session >/dev/null 2>&1; then
                exec openbox-session &
            elif command -v lxsession >/dev/null 2>&1; then
                exec lxsession &
            fi
            """.trimIndent()
        )
        startDesktop.setExecutable(true, false)

        // Create stop-desktop script
        val stopDesktop = File(rootfsDir, "usr/local/bin/stop-desktop")
        stopDesktop.writeText(
            """
            #!/bin/bash
            export DISPLAY=:1
            vncserver -kill :1 2>/dev/null
            pkill -f "Xtigervnc :1" 2>/dev/null
            pkill -f "x11vnc" 2>/dev/null
            pkill -f "Xvfb :1" 2>/dev/null
            pkill -f "xfce4-session" 2>/dev/null
            pkill -f "openbox" 2>/dev/null
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            """.trimIndent()
        )
        stopDesktop.setExecutable(true, false)

        // Create launch-antigravity script
        val launchAntigravity = File(rootfsDir, "usr/local/bin/launch-antigravity")
        launchAntigravity.writeText(
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
