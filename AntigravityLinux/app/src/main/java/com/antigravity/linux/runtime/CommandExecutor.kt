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

        val hasBash = File(rootfsDir, "usr/bin/bash").exists() || File(rootfsDir, "bin/bash").exists()
        cmdList.add(if (hasBash) "/bin/bash" else "/bin/sh")
        cmdList.add("-c")
        cmdList.add(command)

        val processBuilder = ProcessBuilder(cmdList)
        val env = processBuilder.environment()
        env["HOME"] = if (asUser == "root") "/root" else "/home/$asUser"
        env["USER"] = asUser
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "en_US.UTF-8"
        env["DISPLAY"] = ":1"
        env.putAll(envVars)

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

            ExecutionResult(
                exitCode = process.exitValue(),
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
        val prootPath = prootBin.absolutePath
        val rootfsPath = rootfsDir.absolutePath

        val isScript = prootBin.exists() && try {
            prootBin.inputStream().use { it.read() == '#'.code && it.read() == '!'.code }
        } catch (_: Exception) { false }

        val cmdList = mutableListOf<String>()
        if (isScript) {
            cmdList.add("/system/bin/sh")
        }
        cmdList.addAll(listOf(
            prootPath,
            "-r", rootfsPath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", workDir,
            "/bin/bash", "-l"
        ))

        val processBuilder = ProcessBuilder(cmdList)
        val env = processBuilder.environment()
        env["HOME"] = if (asUser == "root") "/root" else "/home/$asUser"
        env["USER"] = asUser
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["LANG"] = "en_US.UTF-8"
        env["DISPLAY"] = ":1"

        return processBuilder.start()
    }
}
