package com.antigravity.linux.terminal

import com.antigravity.linux.runtime.CommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class TerminalSession(private val commandExecutor: CommandExecutor) {

    private var process: Process? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _terminalOutput = MutableStateFlow<List<String>>(emptyList())
    val terminalOutput: StateFlow<List<String>> = _terminalOutput.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    fun startSession(workDir: String = "/home/antigravity", asUser: String = "antigravity") {
        if (_isRunning.value) return

        try {
            val proc = commandExecutor.createInteractiveLinuxProcess(workDir, asUser)
            process = proc
            outputStream = proc.outputStream
            _isRunning.value = true

            appendLine("\u001B[32m[+] Starting Arch Linux ARM64 Terminal Session...\u001B[0m")
            appendLine("\u001B[36m[+] User: $asUser | Directory: $workDir | Arch: aarch64\u001B[0m")
            appendLine("")

            // Read stdout
            scope.launch {
                readStream(proc.inputStream)
            }
            // Read stderr
            scope.launch {
                readStream(proc.errorStream)
            }

            // Monitor process exit
            scope.launch {
                val exitCode = proc.waitFor()
                _isRunning.value = false
                appendLine("\n\u001B[31m[-] Process exited with code $exitCode\u001B[0m")
            }

        } catch (e: Exception) {
            _isRunning.value = false
            appendLine("\u001B[31m[-] Failed to start terminal: ${e.message}\u001B[0m")
        }
    }

    private suspend fun readStream(stream: InputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(4096)
        try {
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                val text = String(buffer, 0, bytesRead)
                appendRawText(text)
            }
        } catch (_: Exception) {}
    }

    private fun appendRawText(text: String) {
        val lines = text.split("\n")
        val currentList = _terminalOutput.value.toMutableList()

        if (lines.isNotEmpty()) {
            if (currentList.isNotEmpty() && !text.startsWith("\n")) {
                val lastIdx = currentList.size - 1
                currentList[lastIdx] = currentList[lastIdx] + lines[0]
                for (i in 1 until lines.size) {
                    currentList.add(lines[i])
                }
            } else {
                currentList.addAll(lines)
            }
        }

        // Limit buffer to last 1000 lines
        if (currentList.size > 1000) {
            val trimmed = currentList.subList(currentList.size - 1000, currentList.size)
            _terminalOutput.value = trimmed.toList()
        } else {
            _terminalOutput.value = currentList.toList()
        }
    }

    fun appendLine(line: String) {
        val currentList = _terminalOutput.value.toMutableList()
        currentList.add(line)
        if (currentList.size > 1000) {
            _terminalOutput.value = currentList.subList(currentList.size - 1000, currentList.size).toList()
        } else {
            _terminalOutput.value = currentList.toList()
        }
    }

    fun sendCommand(command: String) {
        if (!_isRunning.value || outputStream == null) return

        commandHistory.add(command)
        historyIndex = commandHistory.size

        try {
            outputStream?.write((command + "\n").toByteArray())
            outputStream?.flush()
        } catch (e: Exception) {
            appendLine("\u001B[31m[-] Send failed: ${e.message}\u001B[0m")
        }
    }

    fun sendCtrlC() {
        try {
            outputStream?.write(byteArrayOf(3)) // ASCII ETX (Ctrl+C)
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    fun sendCtrlD() {
        try {
            outputStream?.write(byteArrayOf(4)) // ASCII EOT (Ctrl+D)
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    fun sendTab() {
        try {
            outputStream?.write(byteArrayOf(9)) // ASCII HT (Tab)
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    fun getPreviousHistory(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex > 0) {
            historyIndex--
            return commandHistory[historyIndex]
        }
        return commandHistory.firstOrNull()
    }

    fun getNextHistory(): String? {
        if (commandHistory.isEmpty()) return null
        if (historyIndex < commandHistory.size - 1) {
            historyIndex++
            return commandHistory[historyIndex]
        }
        historyIndex = commandHistory.size
        return ""
    }

    fun close() {
        _isRunning.value = false
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        outputStream = null
    }
}
