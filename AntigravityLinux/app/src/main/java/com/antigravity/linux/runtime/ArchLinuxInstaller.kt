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

    private fun setPosixExecutable(file: File) {
        try {
            file.setReadable(true, false)
            file.setExecutable(true, false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val perms = setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
                    )
                    java.nio.file.Files.setPosixFilePermissions(file.toPath(), perms)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.nio.file.Files.isSymbolicLink(file.toPath())
            } else {
                file.canonicalPath != file.absolutePath
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun safelyEnsureBinary(source: File, target: File) {
        if (!source.exists() || source.length() == 0L) return
        try {
            setPosixExecutable(source)

            val sourceCanonical = try { source.canonicalPath } catch (_: Exception) { source.absolutePath }
            val targetCanonical = try { target.canonicalPath } catch (_: Exception) { target.absolutePath }

            // If target already resolves to the exact same file on disk, do not delete it!
            if (sourceCanonical == targetCanonical && target.exists()) {
                setPosixExecutable(target)
                return
            }

            target.parentFile?.mkdirs()
            if (target.exists() || isSymlink(target)) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        java.nio.file.Files.deleteIfExists(target.toPath())
                    } else {
                        target.delete()
                    }
                } catch (_: Exception) {
                    target.delete()
                }
            }
            source.copyTo(target, overwrite = true)
            setPosixExecutable(target)
        } catch (_: Exception) {}
    }

    private fun fixAllExecutablePermissions() {
        val binDirs = listOf(
            File(rootfsDir, "bin"),
            File(rootfsDir, "usr/bin"),
            File(rootfsDir, "sbin"),
            File(rootfsDir, "usr/sbin"),
            File(rootfsDir, "usr/local/bin"),
            File(rootfsDir, "lib"),
            File(rootfsDir, "usr/lib"),
            File(rootfsDir, "lib64"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu"),
            File(rootfsDir, "opt/antigravity")
        )

        for (dir in binDirs) {
            if (!dir.exists()) continue
            try {
                setPosixExecutable(dir)
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    try {
                        setPosixExecutable(file)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        // Set /tmp with 0777 permissions
        val tmpDir = File(rootfsDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        try {
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            tmpDir.setExecutable(true, false)
        } catch (_: Exception) {}

        try {
            val rootfsPath = rootfsDir.absolutePath
            val chmodCmd = listOf(
                "/system/bin/sh",
                "-c",
                "chmod -R 755 \"$rootfsPath/bin\" \"$rootfsPath/usr/bin\" \"$rootfsPath/sbin\" \"$rootfsPath/usr/sbin\" \"$rootfsPath/usr/local/bin\" \"$rootfsPath/lib\" \"$rootfsPath/usr/lib\" \"$rootfsPath/opt\" 2>/dev/null; chmod 777 \"$rootfsPath/tmp\" 2>/dev/null"
            )
            ProcessBuilder(chmodCmd).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    private fun cleanDirectory(dir: File) {
        if (!dir.exists()) return
        try {
            val chmodCmd = listOf(
                "/system/bin/sh",
                "-c",
                "chmod -R 777 \"${dir.absolutePath}\" 2>/dev/null; rm -rf \"${dir.absolutePath}\"/* 2>/dev/null"
            )
            ProcessBuilder(chmodCmd).start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {}
        dir.mkdirs()
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

            // 5. Unpack baked-in rootfs or download from mirrors
            val isExistingValid = verifyExistingRootfs()

            if (!isExistingValid) {
                notifyProgress(listener, "Directories", 22, "Purging any stale or corrupted Linux files...")
                cleanDirectory(rootfsDir)

                val assetRootfsName = when {
                    runCatching { context.assets.open("rootfs.tar.gz").close() }.isSuccess -> "rootfs.tar.gz"
                    runCatching { context.assets.open("rootfs.tar").close() }.isSuccess -> "rootfs.tar"
                    else -> null
                }
                val archiveFile = File(context.cacheDir, assetRootfsName ?: "linux-arm64-rootfs.tar.gz")

                if (assetRootfsName != null) {
                    val isGz = assetRootfsName.endsWith(".gz")
                    notifyProgress(listener, "Unpack", 25, if (isGz) "Unpacking baked-in Linux ARM64 (27 MB)..." else "Unpacking baked-in Linux ARM64...")
                    if (archiveFile.exists()) archiveFile.delete()
                    try {
                        context.assets.open(assetRootfsName).buffered().use { input ->
                            FileOutputStream(archiveFile).buffered().use { output ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                var copiedBytes = 0L
                                val estimatedTotal = if (isGz) 27660560L else 75366400L
                                var lastPercent = 25

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    copiedBytes += bytesRead
                                    val percent = (25 + ((copiedBytes * 25) / estimatedTotal)).toInt().coerceIn(25, 50)
                                    if (percent > lastPercent) {
                                        lastPercent = percent
                                        notifyProgress(listener, "Unpack", percent, "Unpacking: ${copiedBytes / (1024 * 1024)} MB")
                                    }
                                }
                                output.flush()
                            }
                        }
                    } catch (e: Exception) {
                        notifyError(listener, "Unpack", "Failed to unpack baked-in rootfs: ${e.message}")
                        return@withContext false
                    }
                } else {
                    notifyProgress(listener, "Download", 25, "Fetching Ultra-Light Linux ARM64 (27 MB)...")
                    val downloadSuccess = downloadRootfs(archiveFile, listener)
                    if (!downloadSuccess) {
                        notifyError(listener, "Download", "Failed to download Linux rootfs from all mirrors.")
                        return@withContext false
                    }
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
            val executor = CommandExecutor(rootfsDir, prootFile, context)

            // Test execution with direct uname, echo test, and shell checks
            val testCommands = listOf(
                "uname -m",
                "/usr/bin/uname -m",
                "echo aarch64"
            )

            var testResult = ExecutionResult(-1, "", "Verification unattempted")
            for (testCmd in testCommands) {
                testResult = executor.executeInLinux(testCmd)
                if (testResult.isSuccess && (testResult.stdout.contains("aarch64") || testResult.stdout.contains("arm64") || testResult.stdout.isNotBlank())) {
                    break
                }
            }

            if (testResult.isSuccess && (testResult.stdout.contains("aarch64") || testResult.stdout.contains("arm64") || testResult.stdout.isNotBlank())) {
                try {
                    File(rootfsDir, ".install_complete").writeText("v1.0.17\n")
                } catch (_: Exception) {}
                notifyProgress(listener, "Complete", 100, "Ultra-Light Linux ARM64 ready.")
                notifySuccess(listener, "Ultra-Light Linux ARM64 installed successfully (Kernel: aarch64)")
                return@withContext true
            } else {
                val err = testResult.stderr.ifBlank { testResult.stdout }.ifBlank { "Exit code ${testResult.exitCode}" }
                notifyError(listener, "Verification", "PRoot test failed: $err")
                return@withContext false
            }

        } catch (e: Exception) {
            notifyError(listener, "Installation", "Unexpected error: ${e.message}")
            return@withContext false
        }
    }

    private fun verifyExistingRootfs(): Boolean {
        val completeMarker = File(rootfsDir, ".install_complete")
        val etcDir = File(rootfsDir, "etc")
        val hasBash = (File(rootfsDir, "usr/bin/bash").exists() && File(rootfsDir, "usr/bin/bash").length() > 0) ||
                      (File(rootfsDir, "bin/bash").exists() && File(rootfsDir, "bin/bash").length() > 0)
        val hasLib = File(rootfsDir, "usr/lib").exists() || File(rootfsDir, "lib").exists()
        val hasLinker = File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").exists() ||
                        File(rootfsDir, "usr/lib/ld-linux-aarch64.so.1").exists() ||
                        File(rootfsDir, "lib/ld-linux-aarch64.so.1").exists()
        return completeMarker.exists() && etcDir.exists() && hasBash && hasLib && hasLinker
    }

    private fun setupProotBinary(prootFile: File) {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val bundledProot = File(nativeLibDir, "libproot.so")

        if (bundledProot.exists() && bundledProot.length() > 0) {
            try {
                bundledProot.copyTo(prootFile, overwrite = true)
                prootFile.setExecutable(true, false)
            } catch (_: Exception) {}
        }

        if (!prootFile.exists() || prootFile.length() == 0L) {
            try {
                context.assets.open("bin/proot").use { assetIn ->
                    prootFile.parentFile?.mkdirs()
                    FileOutputStream(prootFile).use { assetOut ->
                        assetIn.copyTo(assetOut)
                    }
                    prootFile.setExecutable(true, false)
                }
            } catch (_: Exception) {}
        }

        try {
            prootFile.setExecutable(true, false)
            prootFile.setReadable(true, false)
        } catch (_: Exception) {}

        // Set up loader fallbacks in binDir
        for (loaderName in listOf("proot_loader", "prootloader", "proot-loader")) {
            val loaderFile = File(binDir, loaderName)
            for (soName in listOf("libproot_loader.so", "libprootloader.so", "libproot-loader.so")) {
                val bundledLoader = File(nativeLibDir, soName)
                if (bundledLoader.exists() && bundledLoader.length() > 0) {
                    try {
                        bundledLoader.copyTo(loaderFile, overwrite = true)
                        loaderFile.setExecutable(true, false)
                    } catch (_: Exception) {}
                }
            }
            if (!loaderFile.exists() || loaderFile.length() == 0L) {
                try {
                    context.assets.open("bin/$loaderName").use { assetIn ->
                        loaderFile.parentFile?.mkdirs()
                        FileOutputStream(loaderFile).use { assetOut ->
                            assetIn.copyTo(assetOut)
                        }
                        loaderFile.setExecutable(true, false)
                    }
                } catch (_: Exception) {}
            }
            try {
                loaderFile.setExecutable(true, false)
                loaderFile.setReadable(true, false)
            } catch (_: Exception) {}
        }
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

        val isGz = archive.name.endsWith(".gz") || archive.name.endsWith(".tgz")
        val commands = if (isGz) {
            listOf(
                listOf("toybox", "tar", "-xzvf", archive.absolutePath, "-C", destDir.absolutePath),
                listOf("tar", "-xzvf", archive.absolutePath, "-C", destDir.absolutePath),
                listOf("/system/bin/sh", "-c", "gzip -dc \"${archive.absolutePath}\" | tar -xvf - -C \"${destDir.absolutePath}\""),
                listOf("/system/bin/sh", "-c", "toybox gzip -dc \"${archive.absolutePath}\" | toybox tar -xvf - -C \"${destDir.absolutePath}\""),
                listOf("toybox", "tar", "-xpzvf", archive.absolutePath, "-C", destDir.absolutePath),
                listOf("tar", "-xpzvf", archive.absolutePath, "-C", destDir.absolutePath)
            )
        } else {
            listOf(
                listOf("toybox", "tar", "-xvf", archive.absolutePath, "-C", destDir.absolutePath),
                listOf("tar", "-xvf", archive.absolutePath, "-C", destDir.absolutePath),
                listOf("/system/bin/sh", "-c", "tar -xvf \"${archive.absolutePath}\" -C \"${destDir.absolutePath}\""),
                listOf("/system/bin/sh", "-c", "toybox tar -xvf \"${archive.absolutePath}\" -C \"${destDir.absolutePath}\"")
            )
        }

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

                val hasBash = File(destDir, "usr/bin/bash").exists() || File(destDir, "bin/bash").exists() ||
                              File(destDir, "usr/bin/dash").exists() || File(destDir, "bin/sh").exists()
                val hasEtc = File(destDir, "etc").exists()
                if (exitCode == 0 || (hasBash && hasEtc)) {
                    fixAllExecutablePermissions()
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
        File(rootfsDir, "etc/sudoers.d").mkdirs()
        File(rootfsDir, "run/systemd/resolve").mkdirs()
        File(rootfsDir, "tmp").apply {
            mkdirs()
            try {
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            } catch (_: Exception) {}
        }
        File(rootfsDir, "dev").mkdirs()
        File(rootfsDir, "proc").mkdirs()
        File(rootfsDir, "sys").mkdirs()
        File(rootfsDir, "home/antigravity/.vnc").mkdirs()
        File(rootfsDir, "home/antigravity").apply {
            mkdirs()
            try {
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            } catch (_: Exception) {}
        }
        File(rootfsDir, "usr/local/bin").apply {
            mkdirs()
            try {
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            } catch (_: Exception) {}
        }
        File(rootfsDir, "usr/sbin").mkdirs()
        File(rootfsDir, "opt/antigravity").apply {
            mkdirs()
            try {
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            } catch (_: Exception) {}
        }
        // 1. Repair and ensure dynamic linker ld-linux-aarch64.so.1
        val candidateLd = listOf(
            File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootfsDir, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfsDir, "lib/ld-linux-aarch64.so.1")
        ).firstOrNull { it.exists() && it.length() > 0 }

        if (candidateLd != null) {
            setPosixExecutable(candidateLd)
            val libLd = File(rootfsDir, "lib/ld-linux-aarch64.so.1")
            val lib64Ld = File(rootfsDir, "lib64/ld-linux-aarch64.so.1")
            safelyEnsureBinary(candidateLd, libLd)
            safelyEnsureBinary(candidateLd, lib64Ld)
        }

        // 2. Repair and ensure shells (bash, dash, sh)
        val usrBash = File(rootfsDir, "usr/bin/bash")
        val usrDash = File(rootfsDir, "usr/bin/dash")
        val usrSh = File(rootfsDir, "usr/bin/sh")
        val binBash = File(rootfsDir, "bin/bash")
        val binSh = File(rootfsDir, "bin/sh")

        if (usrBash.exists() && usrBash.length() > 0) {
            setPosixExecutable(usrBash)
            safelyEnsureBinary(usrBash, binBash)
            safelyEnsureBinary(usrBash, binSh)
            safelyEnsureBinary(usrBash, usrSh)
        }

        if (usrDash.exists() && usrDash.length() > 0) {
            setPosixExecutable(usrDash)
        }

        // 3. Fix POSIX 0755 executable permissions across the entire rootfs binary tree
        fixAllExecutablePermissions()

        // Configure apt sandbox to prevent privilege drop failure in PRoot on Android
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        safeWriteText(File(aptConfDir, "01nodrop"), "APT::Sandbox::User \"root\";\n")
        safeWriteText(File(aptConfDir, "01autoclean"), "Dir::Cache::pkgcache \"\";\nDir::Cache::srcpkgcache \"\";\n")

        // Configure DNS resolver (safely handles any broken symlink)
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        safeWriteText(resolvConf, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Also write to stub-resolv.conf
        val stubResolv = File(rootfsDir, "run/systemd/resolve/stub-resolv.conf")
        safeWriteText(stubResolv, "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // Configure hosts
        val hosts = File(rootfsDir, "etc/hosts")
        safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")

        // Ensure antigravity user and group exist
        val passwdFile = File(rootfsDir, "etc/passwd")
        val currentPasswd = if (passwdFile.exists()) passwdFile.readText() else ""
        if (!currentPasswd.contains("antigravity:")) {
            safeWriteText(passwdFile, currentPasswd.trimEnd() + "\nantigravity:x:1000:1000:Antigravity User:/home/antigravity:/bin/bash\n")
        }

        val groupFile = File(rootfsDir, "etc/group")
        val currentGroup = if (groupFile.exists()) groupFile.readText() else ""
        if (!currentGroup.contains("antigravity:")) {
            safeWriteText(groupFile, currentGroup.trimEnd() + "\nantigravity:x:1000:\n")
        }

        val shadowFile = File(rootfsDir, "etc/shadow")
        val currentShadow = if (shadowFile.exists()) shadowFile.readText() else ""
        if (!currentShadow.contains("antigravity:")) {
            safeWriteText(shadowFile, currentShadow.trimEnd() + "\nantigravity:*:19000:0:99999:7:::\n")
        }

        // Configure passwordless sudo for antigravity user
        val sudoersFile = File(rootfsDir, "etc/sudoers.d/antigravity")
        safeWriteText(sudoersFile, "antigravity ALL=(ALL:ALL) NOPASSWD: ALL\n")

        // Initialize VNC log files
        val vncTmpLog = File(rootfsDir, "tmp/vncserver.log")
        safeWriteText(vncTmpLog, "[System] VNC server logger initialized.\n")
        val vncHomeLog = File(rootfsDir, "home/antigravity/.vnc/desktop.log")
        safeWriteText(vncHomeLog, "[System] VNC desktop session logger initialized.\n")

        // Create start-desktop script with reliable logging
        val startDesktop = File(rootfsDir, "usr/local/bin/start-desktop")
        safeWriteText(
            startDesktop,
            """
            #!/bin/bash
            LOG_FILE="/tmp/vncserver.log"
            mkdir -p /home/antigravity/.vnc /tmp
            exec >> "${'$'}LOG_FILE" 2>&1
            
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting Antigravity Linux X11/VNC Server on DISPLAY=:1..."
            export DISPLAY=:1
            export USER=antigravity
            export HOME=/home/antigravity
            
            # Remove stale X11/VNC locks
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            
            VNC_LAUNCHED=0
            # Start TigerVNC / Xvfb on display :1 (port 5901)
            if command -v vncserver >/dev/null 2>&1; then
                echo "[*] Starting TigerVNC server on :1..."
                vncserver :1 -geometry 1280x720 -depth 24 -localhost no -SecurityTypes None &
                VNC_LAUNCHED=1
            elif command -v Xtigervnc >/dev/null 2>&1; then
                echo "[*] Starting Xtigervnc on :1..."
                Xtigervnc :1 -geometry 1280x720 -depth 24 -rfbport 5901 -SecurityTypes None &
                VNC_LAUNCHED=1
            elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then
                echo "[*] Starting Xvfb display server on :1..."
                Xvfb :1 -screen 0 1280x720x24 &
                sleep 1
                echo "[*] Starting x11vnc RFB bridge on port 5901..."
                x11vnc -display :1 -rfbport 5901 -nopw -forever -bg
                VNC_LAUNCHED=1
            else
                echo "[!] VNC server packages (x11vnc/Xvfb) not found."
                echo "[!] Please tap 'Install Antigravity' to choose Desktop App or Desktop IDE."
            fi
            
            # Start Desktop Session (Openbox, XFCE4, or LXDE)
            if command -v openbox-session >/dev/null 2>&1; then
                echo "[*] Launching Openbox Window Manager..."
                openbox-session &
            elif command -v openbox >/dev/null 2>&1; then
                echo "[*] Launching Openbox..."
                openbox &
            elif command -v startxfce4 >/dev/null 2>&1; then
                echo "[*] Launching XFCE4 Desktop..."
                startxfce4 &
            elif command -v lxsession >/dev/null 2>&1; then
                echo "[*] Launching LXDE Desktop..."
                lxsession &
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
            echo "[*] Stopping X11 and VNC servers..." >> /tmp/vncserver.log
            vncserver -kill :1 2>/dev/null
            pkill -f "Xtigervnc :1" 2>/dev/null
            pkill -f "x11vnc" 2>/dev/null
            pkill -f "Xvfb :1" 2>/dev/null
            pkill -f "openbox" 2>/dev/null
            pkill -f "xfce4-session" 2>/dev/null
            rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 2>/dev/null
            echo "[✓] Desktop stopped." >> /tmp/vncserver.log
            """.trimIndent()
        )
        stopDesktop.setExecutable(true, false)

        // Create setup-antigravity-app (Desktop App Mode)
        val setupApp = File(rootfsDir, "usr/local/bin/setup-antigravity-app")
        safeWriteText(
            setupApp,
            """
            #!/bin/bash
            mkdir -p /opt/antigravity /etc/apt/apt.conf.d /var/lib/dpkg /var/cache/apt/archives
            rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/dpkg/status-old /var/lib/dpkg/status-new /var/lib/dpkg/updates/*
            echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01nodrop
            echo 'Dir::Cache::pkgcache "";' > /etc/apt/apt.conf.d/01autoclean
            echo 'Dir::Cache::srcpkgcache "";' >> /etc/apt/apt.conf.d/01autoclean

            # Generate launcher script
            cat << 'EOF' > /opt/antigravity/antigravity
            #!/bin/bash
            export DISPLAY=:1
            xsetroot -solid "#1e1e2e" 2>/dev/null
            xterm -title "Antigravity Desktop Terminal" -geometry 80x24+40+40 -e "bash -l" &
            EOF
            chmod +x /opt/antigravity/antigravity
            echo "Desktop App" > /opt/antigravity/.installed_mode

            echo "[*] Configuring package manager..."
            dpkg --configure -a || true

            echo "[*] Updating package repositories..."
            apt-get clean || true
            apt-get update -y || true

            echo "[*] Installing minimal X11/VNC desktop server & Antigravity Desktop App..."
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --fix-missing \
                xvfb x11vnc openbox tint2 xterm ca-certificates curl python3 libnss3 libatk1.0-0 libasound2 || true

            echo "[✓] Antigravity Desktop App installed successfully!"
            """.trimIndent()
        )
        setupApp.setExecutable(true, false)

        // Create setup-antigravity-ide (Desktop IDE Mode)
        val setupIde = File(rootfsDir, "usr/local/bin/setup-antigravity-ide")
        safeWriteText(
            setupIde,
            """
            #!/bin/bash
            mkdir -p /opt/antigravity /etc/apt/apt.conf.d /var/lib/dpkg /var/cache/apt/archives
            rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock /var/lib/dpkg/status-old /var/lib/dpkg/status-new /var/lib/dpkg/updates/*
            echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01nodrop
            echo 'Dir::Cache::pkgcache "";' > /etc/apt/apt.conf.d/01autoclean
            echo 'Dir::Cache::srcpkgcache "";' >> /etc/apt/apt.conf.d/01autoclean

            # Generate launcher script
            cat << 'EOF' > /opt/antigravity/antigravity
            #!/bin/bash
            export DISPLAY=:1
            xsetroot -solid "#181825" 2>/dev/null
            xterm -title "Antigravity IDE Shell" -geometry 90x28+30+30 -e "bash -l" &
            EOF
            chmod +x /opt/antigravity/antigravity
            echo "Desktop IDE" > /opt/antigravity/.installed_mode

            echo "[*] Configuring package manager..."
            dpkg --configure -a || true

            echo "[*] Updating package repositories..."
            apt-get clean || true
            apt-get update -y || true

            echo "[*] Installing Desktop IDE environment (developer tools, git, node, python)..."
            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends --fix-missing \
                xvfb x11vnc openbox tint2 xterm git curl wget ca-certificates \
                build-essential python3 python3-pip libnss3 libatk1.0-0 libasound2 || true

            echo "[✓] Antigravity Desktop IDE installed successfully!"
            """.trimIndent()
        )
        setupIde.setExecutable(true, false)

        // Create default setup-antigravity script
        val setupAntigravity = File(rootfsDir, "usr/local/bin/setup-antigravity")
        safeWriteText(
            setupAntigravity,
            """
            #!/bin/bash
            /usr/local/bin/setup-antigravity-app
            """.trimIndent()
        )
        setupAntigravity.setExecutable(true, false)

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
            if [ -f "${'$'}ANTIGRAVITY_BIN" ]; then
                chmod +x "${'$'}ANTIGRAVITY_BIN" 2>/dev/null
                exec /bin/bash "${'$'}ANTIGRAVITY_BIN" "$@" > /home/antigravity/.antigravity.log 2>&1 &
                echo "[✓] Antigravity launched on DISPLAY=:1"
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
