package com.antigravity.linux.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import com.antigravity.linux.data.DesktopState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopManager(private val context: Context) : VncEventListener {

    private val androidClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.Main)

    private var vncClient: VncClient? = null

    private val _desktopState = MutableStateFlow(DesktopState.NOT_STARTED)
    val desktopState: StateFlow<DesktopState> = _desktopState.asStateFlow()

    private val _framebuffer = MutableStateFlow<Bitmap?>(null)
    val framebuffer: StateFlow<Bitmap?> = _framebuffer.asStateFlow()

    private val _desktopInfo = MutableStateFlow("Disconnected")
    val desktopInfo: StateFlow<String> = _desktopInfo.asStateFlow()

    private val _linuxClipboardText = MutableStateFlow("")
    val linuxClipboardText: StateFlow<String> = _linuxClipboardText.asStateFlow()

    var autoSyncClipboard: Boolean = false

    suspend fun connect(host: String = "127.0.0.1", port: Int = 5901): Boolean = withContext(Dispatchers.IO) {
        _desktopState.value = DesktopState.STARTING
        _desktopInfo.value = "Connecting to Linux display on $host:$port..."

        val client = VncClient(host, port, this@DesktopManager)
        vncClient = client
        val connected = client.connect()

        if (!connected) {
            _desktopState.value = DesktopState.ERROR
            _desktopInfo.value = "Failed to connect to display server at $host:$port"
        }
        connected
    }

    fun disconnect() {
        vncClient?.disconnect()
        vncClient = null
        _desktopState.value = DesktopState.DISCONNECTED
        _desktopInfo.value = "Disconnected"
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        vncClient?.sendPointerEvent(x, y, buttonMask)
    }

    fun sendKeyStroke(keysym: Int) {
        vncClient?.sendKeyStroke(keysym)
    }

    fun sendKeyEvent(keysym: Int, isDown: Boolean) {
        vncClient?.sendKeyEvent(keysym, isDown)
    }

    fun sendText(text: String) {
        vncClient?.sendText(text)
    }

    fun sendShortcut(modifierKeysym: Int, keyChar: Char) {
        vncClient?.sendShortcut(modifierKeysym, keyChar)
    }

    /**
     * Android -> Linux Clipboard Transfer
     */
    fun syncAndroidToLinuxClipboard(): Boolean {
        val clip = androidClipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty()) {
                vncClient?.sendClipboardText(text)
                return true
            }
        }
        return false
    }

    /**
     * Linux -> Android Clipboard Transfer
     */
    fun syncLinuxToAndroidClipboard(): Boolean {
        val text = _linuxClipboardText.value
        if (text.isNotEmpty()) {
            val clip = ClipData.newPlainText("Linux Desktop Clipboard", text)
            androidClipboard.setPrimaryClip(clip)
            return true
        }
        return false
    }

    override fun onConnected(width: Int, height: Int, desktopName: String) {
        scope.launch {
            _desktopState.value = DesktopState.RUNNING
            _desktopInfo.value = "$desktopName (${width}x${height})"
        }
    }

    override fun onFrameUpdated(bitmap: Bitmap) {
        _framebuffer.value = bitmap
    }

    override fun onClipboardReceived(text: String) {
        scope.launch {
            _linuxClipboardText.value = text
            if (autoSyncClipboard) {
                val clip = ClipData.newPlainText("Linux Desktop Clipboard", text)
                androidClipboard.setPrimaryClip(clip)
            }
        }
    }

    override fun onDisconnected(reason: String) {
        scope.launch {
            _desktopState.value = DesktopState.DISCONNECTED
            _desktopInfo.value = "Disconnected: $reason"
        }
    }
}
