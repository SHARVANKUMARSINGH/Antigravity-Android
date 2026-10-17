package com.antigravity.linux.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
}

data class ProotProfile(
    val noSeccomp: Boolean,
    val useLoader: Boolean,
    val bindSys: Boolean
)

class CommandExecutor(
    private val rootfsDir: File,
    private val prootBin: File,
    private val context: android.content.Context? = null
) {

    companion object {
        @Volatile
        var cachedWorkingProfile: ProotProfile? = null

        val CANDIDATE_PROFILES = listOf(
            // Profile 0: PROOT_NO_SECCOMP with loader, no sysfs bind (Required standard for Samsung / Android 14 ARM64)
            ProotProfile(noSeccomp = true, useLoader = true, bindSys = false),
            // Profile 1: PROOT_NO_SECCOMP with loader and sysfs bind
            ProotProfile(noSeccomp = true, useLoader = true, bindSys = true),
            // Profile 2: Native seccomp with loader, no sysfs bind (for kernels supporting seccomp trap)
            ProotProfile(noSeccomp = false, useLoader = true, bindSys = false),
            // Profile 3: Native seccomp with loader and sysfs bind
            ProotProfile(noSeccomp = false, useLoader = true, bindSys = true),
            // Profile 4: PROOT_NO_SECCOMP without loader (fallback)
            ProotProfile(noSeccomp = true, useLoader = false, bindSys = false),
            // Profile 5: Native seccomp without loader (legacy fallback)
            ProotProfile(noSeccomp = false, useLoader = false, bindSys = false)
        )
    }

    /**
     * Resolves the executable PRoot binary.
     * Prefers nativeLibraryDir/libproot.so (unrestricted execution permissions on Android 10+),
     * then filesDir/bin/proot or prootBin.
     */
    fun getEffectiveProot(): File {
        if (context != null) {
            val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            if (nativeLib.exists() && nativeLib.length() > 0) {
                try { nativeLib.setExecutable(true, false) } catch (_: Exception) {}
                return nativeLib
            }
        }
        if (prootBin.exists() && prootBin.length() > 0) {
            try { prootBin.setExecutable(true, false) } catch (_: Exception) {}
            return prootBin
        }
        if (context != null) {
            val binProot = File(context.filesDir, "bin/proot")
            if (binProot.exists() && binProot.length() > 0) {
                try { binProot.setExecutable(true, false) } catch (_: Exception) {}
                return binProot
            }
        }
        return prootBin
    }

    /**
     * Resolves the executable PRoot loader.
     * Android 10+ restricts execution from writable directories (cacheDir / filesDir).
     * Supplying PROOT_LOADER pointing to an extracted native library (.so) bypasses W^X noexec restrictions.
     */
    fun getEffectiveLoader(): File? {
        if (context != null) {
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            for (name in listOf("libproot_loader.so", "libprootloader.so", "libproot-loader.so")) {
                val f = File(libDir, name)
                if (f.exists() && f.length() > 0) {
                    try { f.setExecutable(true, false) } catch (_: Exception) {}
                    return f
                }
            }
            for (name in listOf("bin/proot_loader", "bin/prootloader", "bin/proot-loader")) {
                val f = File(context.filesDir, name)
                if (f.exists() && f.length() > 0) {
                    try { f.setExecutable(true, false) } catch (_: Exception) {}
                    return f
                }
            }
        }
        val parent = rootfsDir.parentFile ?: return null
        for (name in listOf("bin/proot_loader", "bin/prootloader", "bin/proot-loader")) {
            val f = File(parent, name)
            if (f.exists() && f.length() > 0) {
                try { f.setExecutable(true, false) } catch (_: Exception) {}
                return f
            }
        }
        return null
    }

    /**
     * Resolves an app-writable temporary directory for PRoot translation tables and sockets.
     */
    fun getProotTmpDir(): File {
        val baseDir = context?.filesDir ?: File(rootfsDir.parentFile ?: rootfsDir, "tmp")
        val tmpDir = File(baseDir, "tmp")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
        }
        try {
            tmpDir.setReadable(true, false)
            tmpDir.setWritable(true, false)
            tmpDir.setExecutable(true, false)
        } catch (_: Exception) {}
        return tmpDir
    }

    /**
     * Determines the appropriate shell binary to execute inside the rootfs.
     */
    fun resolveShellPath(): String {
        return when {
            File(rootfsDir, "bin/bash").exists() -> "/bin/bash"
            File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(rootfsDir, "bin/sh").exists() -> "/bin/sh"
            File(rootfsDir, "usr/bin/sh").exists() -> "/usr/bin/sh"
            File(rootfsDir, "usr/bin/dash").exists() -> "/usr/bin/dash"
            else -> "/bin/sh"
        }
    }

    /**
     * Builds the PRoot command-line invocation list using the active or default profile.
     */
    fun buildProotCommand(
        workDir: String = "/root",
        vararg extraArgs: String
    ): List<String> = buildProotCommandForProfile(
        profile = cachedWorkingProfile ?: CANDIDATE_PROFILES.first(),
        workDir = workDir,
        extraArgs = extraArgs
    )

    fun buildProotCommandForProfile(
        profile: ProotProfile,
        workDir: String = "/root",
        vararg extraArgs: String
    ): List<String> {
        val effectiveProot = getEffectiveProot()
        val prootPath = effectiveProot.absolutePath
        val rootfsPath = rootfsDir.absolutePath

        val cmdList = mutableListOf<String>()
        cmdList.add(prootPath)
        cmdList.add("-r")
        cmdList.add(rootfsPath)
        cmdList.add("-0")
        cmdList.add("--link2symlink")
        cmdList.add("-b")
        cmdList.add("/dev")
        cmdList.add("-b")
        cmdList.add("/proc")
        if (profile.bindSys) {
            cmdList.add("-b")
            cmdList.add("/sys")
        }
        cmdList.add("-w")
        cmdList.add(workDir)
        cmdList.addAll(extraArgs)
        return cmdList
    }

    /**
     * Applies standard PRoot environment variables for Android.
     */
    fun applyProotEnvironment(
        env: MutableMap<String, String>,
        asUser: String = "root",
        extraVars: Map<String, String> = emptyMap()
    ) = applyProotEnvironmentForProfile(
        profile = cachedWorkingProfile ?: CANDIDATE_PROFILES.first(),
        env = env,
        asUser = asUser,
        extraVars = extraVars
    )

    fun applyProotEnvironmentForProfile(
        profile: ProotProfile,
        env: MutableMap<String, String>,
        asUser: String = "root",
        extraVars: Map<String, String> = emptyMap()
    ) {
        val tmpDir = getProotTmpDir()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
        env["PROOT_DONT_POLLUTE_ROOTFS"] = "1"

        if (profile.noSeccomp) {
            env["PROOT_NO_SECCOMP"] = "1"
        } else {
            env.remove("PROOT_NO_SECCOMP")
        }

        if (profile.useLoader) {
            val effectiveLoader = getEffectiveLoader()
            if (effectiveLoader != null && effectiveLoader.exists()) {
                env["PROOT_LOADER"] = effectiveLoader.absolutePath
            } else {
                env.remove("PROOT_LOADER")
            }
        } else {
            env.remove("PROOT_LOADER")
        }

        // Strip Android Bionic host library paths to avoid GNU libc SIGSEGV (exit code 255)
        env.remove("LD_LIBRARY_PATH")
        env.remove("LD_PRELOAD")

        env["HOME"] = if (asUser == "root") "/root" else "/home/$asUser"
        env["USER"] = asUser
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "C.UTF-8"
        env["SHELL"] = "/bin/bash"
        env["DISPLAY"] = ":1"
        env.putAll(extraVars)
    }

    /**
     * Executes a command inside the Linux rootfs using PRoot.
     */
    suspend fun executeInLinux(
        command: String,
        workDir: String = "/root",
        asUser: String = "root",
        timeoutSeconds: Long = 60,
        envVars: Map<String, String> = emptyMap()
    ): ExecutionResult = executeInLinuxStream(
        command = command,
        workDir = workDir,
        asUser = asUser,
        timeoutSeconds = timeoutSeconds,
        envVars = envVars,
        onLine = {}
    )

    /**
     * Executes a command inside the Linux rootfs using PRoot with real-time output callback and adaptive profile discovery.
     */
    suspend fun executeInLinuxStream(
        command: String,
        workDir: String = "/root",
        asUser: String = "root",
        timeoutSeconds: Long = 60,
        envVars: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val effectiveProot = getEffectiveProot()
        if (!effectiveProot.exists() || effectiveProot.length() == 0L) {
            return@withContext ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "PRoot engine binary not found. Please initialize the environment."
            )
        }

        try {
            effectiveProot.setExecutable(true, false)
        } catch (_: Exception) {}

        val shellPath = resolveShellPath()
        val hostWorkDir = context?.filesDir ?: rootfsDir

        // Helper function to execute under a specific profile and shell
        fun runAttempt(profile: ProotProfile, shell: String): ExecutionResult {
            val cmdList = buildProotCommandForProfile(profile, workDir, shell, "-c", command)
            val processBuilder = ProcessBuilder(cmdList)
            if (hostWorkDir.exists()) {
                processBuilder.directory(hostWorkDir)
            }
            val env = processBuilder.environment()
            applyProotEnvironmentForProfile(profile, env, asUser, envVars)

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            return try {
                val process = processBuilder.start()
                val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
                val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

                val stdoutThread = Thread {
                    try {
                        var line: String?
                        while (stdoutReader.readLine().also { line = it } != null) {
                            stdout.append(line).append("\n")
                            line?.let { onLine(it) }
                        }
                    } catch (_: Exception) {}
                }
                val stderrThread = Thread {
                    try {
                        var line: String?
                        while (stderrReader.readLine().also { line = it } != null) {
                            stderr.append(line).append("\n")
                            line?.let { onLine(it) }
                        }
                    } catch (_: Exception) {}
                }

                stdoutThread.start()
                stderrThread.start()

                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return ExecutionResult(
                        exitCode = -1,
                        stdout = stdout.toString().trim(),
                        stderr = "Execution timed out after $timeoutSeconds seconds"
                    )
                }

                stdoutThread.join(1000)
                stderrThread.join(1000)

                ExecutionResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.toString().trim(),
                    stderr = stderr.toString().trim()
                )
            } catch (e: Exception) {
                ExecutionResult(
                    exitCode = -1,
                    stdout = stdout.toString().trim(),
                    stderr = "Process execution failed: ${e.message}"
                )
            }
        }

        // 1. If we already found a working profile, use it directly (prevents repeating long commands)
        val cached = cachedWorkingProfile
        if (cached != null) {
            val result = runAttempt(cached, shellPath)
            if (!result.isSuccess && shellPath != "/bin/sh") {
                val shResult = runAttempt(cached, "/bin/sh")
                if (shResult.isSuccess) return@withContext shResult
            }
            return@withContext result
        }

        // 2. Discover working profile across candidate profiles
        var lastResult: ExecutionResult? = null
        for (candidate in CANDIDATE_PROFILES) {
            val result = runAttempt(candidate, shellPath)
            if (result.isSuccess) {
                cachedWorkingProfile = candidate
                return@withContext result
            }
            if (shellPath != "/bin/sh") {
                val shResult = runAttempt(candidate, "/bin/sh")
                if (shResult.isSuccess) {
                    cachedWorkingProfile = candidate
                    return@withContext shResult
                }
            }
            lastResult = result
        }

        return@withContext lastResult ?: ExecutionResult(
            exitCode = 255,
            stdout = "",
            stderr = "All PRoot execution profiles failed."
        )
    }

    /**
     * Executes a host-level command directly on Android.
     */
    suspend fun executeOnHost(
        command: List<String>,
        workDir: File? = null,
        timeoutSeconds: Long = 30
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder(command)
        if (workDir != null) processBuilder.directory(workDir)

        try {
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdout,
                    stderr = "Host command timed out"
                )
            }

            ExecutionResult(
                exitCode = process.exitValue(),
                stdout = stdout.trim(),
                stderr = stderr.trim()
            )
        } catch (e: Exception) {
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Host execution failed: ${e.message}"
            )
        }
    }

    /**
     * Spawns an interactive Process inside PRoot for terminal sessions.
     */
    fun createInteractiveLinuxProcess(
        workDir: String = "/home/antigravity",
        asUser: String = "antigravity"
    ): Process {
        val effectiveProot = getEffectiveProot()
        try {
            effectiveProot.setExecutable(true, false)
        } catch (_: Exception) {}

        val profile = cachedWorkingProfile ?: CANDIDATE_PROFILES.first()
        val shellPath = resolveShellPath()
        val cmdList = buildProotCommandForProfile(profile, workDir, shellPath, "-l")

        val processBuilder = ProcessBuilder(cmdList)
        val hostWorkDir = context?.filesDir ?: rootfsDir
        if (hostWorkDir.exists()) {
            processBuilder.directory(hostWorkDir)
        }
        val env = processBuilder.environment()
        applyProotEnvironmentForProfile(profile, env, asUser, emptyMap())

        return processBuilder.start()
    }
}
