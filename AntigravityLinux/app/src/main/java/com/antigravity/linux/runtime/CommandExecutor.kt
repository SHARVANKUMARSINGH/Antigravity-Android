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

class CommandExecutor(private val rootfsDir: File, private val prootBin: File) {

    /**
     * Executes a command inside the Arch Linux rootfs using PRoot.
     */
    suspend fun executeInLinux(
        command: String,
        workDir: String = "/root",
        asUser: String = "root",
        timeoutSeconds: Long = 60,
        envVars: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val prootPath = prootBin.absolutePath
        val rootfsPath = rootfsDir.absolutePath

        val cmdList = mutableListOf<String>()
        cmdList.add(prootPath)
        cmdList.add("-r")
        cmdList.add(rootfsPath)
        cmdList.add("-0")
        cmdList.add("-b")
        cmdList.add("/dev")
        cmdList.add("-b")
        cmdList.add("/proc")
        cmdList.add("-b")
        cmdList.add("/sys")
        cmdList.add("-w")
        cmdList.add(workDir)

        cmdList.add("/bin/bash")
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
                    }
                } catch (_: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    var line: String?
                    while (stderrReader.readLine().also { line = it } != null) {
                        stderr.append(line).append("\n")
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

        val cmdList = listOf(
            prootPath,
            "-r", rootfsPath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", workDir,
            "/bin/bash", "-l"
        )

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
