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

class CommandExecutor(
    private val rootfsDir: File,
    private val prootBin: File,
    private val context: android.content.Context? = null
) {

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
            for (name in listOf("libproot-loader.so", "libprootloader.so")) {
                val f = File(libDir, name)
                if (f.exists() && f.length() > 0) {
                    try { f.setExecutable(true, false) } catch (_: Exception) {}
                    return f
                }
            }
            for (name in listOf("bin/proot-loader", "bin/prootloader")) {
                val f = File(context.filesDir, name)
                if (f.exists() && f.length() > 0) {
                    try { f.setExecutable(true, false) } catch (_: Exception) {}
                    return f
                }
            }
        }
        val parent = rootfsDir.parentFile ?: return null
        for (name in listOf("bin/proot-loader", "bin/prootloader")) {
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
        val baseDir = context?.cacheDir ?: File(rootfsDir.parentFile ?: rootfsDir, "tmp")
        val tmpDir = File(baseDir, "proot_tmp")
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
            File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
            File(rootfsDir, "bin/bash").exists() -> "/bin/bash"
            File(rootfsDir, "usr/bin/dash").exists() -> "/usr/bin/dash"
            File(rootfsDir, "bin/sh").exists() -> "/bin/sh"
            File(rootfsDir, "usr/bin/sh").exists() -> "/usr/bin/sh"
            else -> "/bin/sh"
        }
    }

    /**
     * Builds the PRoot command-line invocation list with all Android-compatible bindings.
     */
    fun buildProotCommand(
        workDir: String = "/root",
        vararg extraArgs: String
    ): List<String> {
        val effectiveProot = getEffectiveProot()
        val prootPath = effectiveProot.absolutePath
        val rootfsPath = rootfsDir.absolutePath

        val cmdList = mutableListOf<String>()
        cmdList.add(prootPath)
        cmdList.add("--link2symlink")
        cmdList.add("-0")
        cmdList.add("-r")
        cmdList.add(rootfsPath)
        cmdList.add("-b")
        cmdList.add("/dev")
        cmdList.add("-b")
        cmdList.add("/proc")
        cmdList.add("-b")
        cmdList.add("/sys")
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
    ) {
        val tmpDir = getProotTmpDir()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        // Do not force PROOT_NO_SECCOMP; pure ptrace on AArch64 Android causes signal 11 on execve.
        // PRoot auto-detects and uses native seccomp filter on Android 10+ safely.
        env.remove("PROOT_NO_SECCOMP")
        env["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
        env["PROOT_DONT_POLLUTE_ROOTFS"] = "1"

        val effectiveLoader = getEffectiveLoader()
        if (effectiveLoader != null && effectiveLoader.exists()) {
            env["PROOT_LOADER"] = effectiveLoader.absolutePath
        }

        // Prevent host preloaded libraries from breaking guest executables
        env.remove("LD_PRELOAD")

        env["HOME"] = if (asUser == "root") "/root" else "/home/$asUser"
        env["USER"] = asUser
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "en_US.UTF-8"
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
     * Executes a command inside the Linux rootfs using PRoot with real-time output callback.
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
        val cmdList = buildProotCommand(workDir, shellPath, "-c", command)

        val processBuilder = ProcessBuilder(cmdList)
        val hostWorkDir = context?.filesDir ?: rootfsDir
        if (hostWorkDir.exists()) {
            processBuilder.directory(hostWorkDir)
        }
        val env = processBuilder.environment()
        applyProotEnvironment(env, asUser, envVars)

        try {
            val process = processBuilder.start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

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
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdout.toString(),
                    stderr = "Execution timed out after $timeoutSeconds seconds"
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitVal = process.exitValue()
            if (exitVal != 0 && shellPath != "/bin/sh") {
                // Secondary fallback attempt using /bin/sh directly
                try {
                    val fallbackList = buildProotCommand(workDir, "/bin/sh", "-c", command)
                    val fallbackBuilder = ProcessBuilder(fallbackList)
                    if (hostWorkDir.exists()) {
                        fallbackBuilder.directory(hostWorkDir)
                    }
                    val fallbackEnv = fallbackBuilder.environment()
                    applyProotEnvironment(fallbackEnv, asUser, envVars)
                    val fallbackProc = fallbackBuilder.start()
                    val fStdout = StringBuilder()
                    val fStderr = StringBuilder()
                    val t1 = Thread { try { fallbackProc.inputStream.bufferedReader().forEachLine { fStdout.append(it).append("\n"); onLine(it) } } catch (_: Exception) {} }
                    val t2 = Thread { try { fallbackProc.errorStream.bufferedReader().forEachLine { fStderr.append(it).append("\n"); onLine(it) } } catch (_: Exception) {} }
                    t1.start(); t2.start()
                    if (fallbackProc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        t1.join(500); t2.join(500)
                        if (fallbackProc.exitValue() == 0) {
                            return@withContext ExecutionResult(
                                exitCode = 0,
                                stdout = fStdout.toString().trim(),
                                stderr = fStderr.toString().trim()
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            ExecutionResult(
                exitCode = exitVal,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Process execution failed: ${e.message}"
            )
        }
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

        val shellPath = resolveShellPath()
        val cmdList = buildProotCommand(workDir, shellPath, "-l")

        val processBuilder = ProcessBuilder(cmdList)
        val hostWorkDir = context?.filesDir ?: rootfsDir
        if (hostWorkDir.exists()) {
            processBuilder.directory(hostWorkDir)
        }
        val env = processBuilder.environment()
        applyProotEnvironment(env, asUser, emptyMap())

        return processBuilder.start()
    }
}
