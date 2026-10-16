package com.antigravity.linux.desktop

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

interface VncEventListener {
    fun onConnected(width: Int, height: Int, desktopName: String)
    fun onFrameUpdated(bitmap: Bitmap)
    fun onClipboardReceived(text: String)
    fun onDisconnected(reason: String)
}

class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901,
    private val listener: VncEventListener
) {
    @Volatile
    private var isRunning = false
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    var width: Int = 1280
        private set
    var height: Int = 720
        private set
    var desktopName: String = "Antigravity Linux"
        private set

    private var framebufferBitmap: Bitmap? = null
    private val bitmapLock = Any()

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.soTimeout = 15000
            sock.connect(InetSocketAddress(host, port), 5000)
            socket = sock

            val dis = DataInputStream(sock.getInputStream())
            val dos = DataOutputStream(sock.getOutputStream())
            input = dis
            output = dos

            // 1. Handshake: Server Protocol Version (e.g., "RFB 003.008\n")
            val versionBytes = ByteArray(12)
            dis.readFully(versionBytes)
            val serverVersion = String(versionBytes, StandardCharsets.US_ASCII)

            // Respond with RFB 003.008
            dos.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
            dos.flush()

            // 2. Security Handshake
            val numSecTypes = dis.readUnsignedByte()
            if (numSecTypes == 0) {
                val reasonLength = dis.readInt()
                val reasonBytes = ByteArray(reasonLength)
                dis.readFully(reasonBytes)
                val reason = String(reasonBytes, StandardCharsets.UTF_8)
                listener.onDisconnected("Security negotiation failed: $reason")
                return@withContext false
            }

            val secTypes = ByteArray(numSecTypes)
            dis.readFully(secTypes)

            // Select SecurityType: 1 (None)
            val chosenType = if (secTypes.contains(1.toByte())) 1.toByte() else secTypes[0]
            dos.writeByte(chosenType.toInt())
            dos.flush()

            // SecurityResult (for version 3.8, even None receives 4-byte 0 = OK)
            val secResult = dis.readInt()
            if (secResult != 0) {
                listener.onDisconnected("Security authentication failed (code: $secResult)")
                return@withContext false
            }

            // 3. ClientInit (shared flag = 1)
            dos.writeByte(1)
            dos.flush()

            // 4. ServerInit
            width = dis.readUnsignedShort()
            height = dis.readUnsignedShort()

            // Pixel format (16 bytes)
            val bpp = dis.readUnsignedByte()       // bits per pixel
            val depth = dis.readUnsignedByte()     // depth
            val bigEndian = dis.readUnsignedByte() // big endian flag
            val trueColor = dis.readUnsignedByte() // true color flag
            val redMax = dis.readUnsignedShort()
            val greenMax = dis.readUnsignedShort()
            val blueMax = dis.readUnsignedShort()
            val redShift = dis.readUnsignedByte()
            val greenShift = dis.readUnsignedByte()
            val blueShift = dis.readUnsignedByte()
            dis.skipBytes(3) // padding

            val nameLength = dis.readInt()
            val nameBytes = ByteArray(nameLength)
            dis.readFully(nameBytes)
            desktopName = String(nameBytes, StandardCharsets.UTF_8)

            synchronized(bitmapLock) {
                framebufferBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }

            // Set encodings: Raw (0), CopyRect (1)
            sendSetEncodings(listOf(0, 1))

            isRunning = true
            listener.onConnected(width, height, desktopName)

            // Request initial full framebuffer
            requestFramebufferUpdate(false, 0, 0, width, height)

            // Start message listener thread
            Thread { runMessageLoop() }.start()

            true
        } catch (e: Exception) {
            listener.onDisconnected("Connection failed: ${e.message}")
            false
        }
    }

    private fun runMessageLoop() {
        try {
            val dis = input ?: return

            while (isRunning) {
                val messageType = dis.readUnsignedByte()
                when (messageType) {
                    0 -> handleFramebufferUpdate(dis)
                    2 -> {
                        // Bell
                    }
                    3 -> handleServerCutText(dis)
                    else -> {
                        // Unknown message, continue
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                listener.onDisconnected("Connection error: ${e.message}")
            }
        } finally {
            disconnect()
        }
    }

    private fun handleFramebufferUpdate(dis: DataInputStream) {
        dis.readByte() // padding
        val numRects = dis.readUnsignedShort()

        val bitmap = synchronized(bitmapLock) { framebufferBitmap } ?: return

        for (i in 0 until numRects) {
            val rx = dis.readUnsignedShort()
            val ry = dis.readUnsignedShort()
            val rw = dis.readUnsignedShort()
            val rh = dis.readUnsignedShort()
            val encoding = dis.readInt()

            if (encoding == 0) {
                // Raw encoding: 4 bytes per pixel (BGRA or RGBA in 32bpp)
                val pixelCount = rw * rh
                val pixelData = IntArray(pixelCount)
                val buffer = ByteArray(pixelCount * 4)
                dis.readFully(buffer)

                var bIdx = 0
                for (p in 0 until pixelCount) {
                    val b = buffer[bIdx].toInt() and 0xFF
                    val g = buffer[bIdx + 1].toInt() and 0xFF
                    val r = buffer[bIdx + 2].toInt() and 0xFF
                    pixelData[p] = Color.rgb(r, g, b)
                    bIdx += 4
                }

                synchronized(bitmapLock) {
                    bitmap.setPixels(pixelData, 0, rw, rx, ry, rw, rh)
                }
            } else if (encoding == 1) {
                // CopyRect encoding
                val srcX = dis.readUnsignedShort()
                val srcY = dis.readUnsignedShort()
                // Copy rect pixels within bitmap
                synchronized(bitmapLock) {
                    val copyPixels = IntArray(rw * rh)
                    bitmap.getPixels(copyPixels, 0, rw, srcX, srcY, rw, rh)
                    bitmap.setPixels(copyPixels, 0, rw, rx, ry, rw, rh)
                }
            }
        }

        listener.onFrameUpdated(bitmap)

        // Request next incremental update
        requestFramebufferUpdate(true, 0, 0, width, height)
    }

    private fun handleServerCutText(dis: DataInputStream) {
        dis.skipBytes(3) // padding
        val length = dis.readInt()
        if (length > 0 && length < 1000000) {
            val textBytes = ByteArray(length)
            dis.readFully(textBytes)
            val text = String(textBytes, StandardCharsets.ISO_8859_1)
            listener.onClipboardReceived(text)
        }
    }

    private fun sendSetEncodings(encodings: List<Int>) {
        try {
            val dos = output ?: return
            synchronized(dos) {
                dos.writeByte(2) // SetEncodings message type
                dos.writeByte(0) // padding
                dos.writeShort(encodings.size)
                for (enc in encodings) {
                    dos.writeInt(enc)
                }
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    fun requestFramebufferUpdate(incremental: Boolean, x: Int, y: Int, w: Int, h: Int) {
        try {
            val dos = output ?: return
            synchronized(dos) {
                dos.writeByte(3) // FramebufferUpdateRequest message type
                dos.writeByte(if (incremental) 1 else 0)
                dos.writeShort(x)
                dos.writeShort(y)
                dos.writeShort(w)
                dos.writeShort(h)
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Sends mouse pointer movement and button clicks.
     * buttonMask: bit 0 = Left, bit 1 = Middle, bit 2 = Right, bit 3 = Wheel Up, bit 4 = Wheel Down
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        try {
            val dos = output ?: return
            synchronized(dos) {
                dos.writeByte(5) // PointerEvent message type
                dos.writeByte(buttonMask)
                dos.writeShort(x.coerceIn(0, width - 1))
                dos.writeShort(y.coerceIn(0, height - 1))
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Sends keyboard press or release event.
     */
    fun sendKeyEvent(keysym: Int, isDown: Boolean) {
        try {
            val dos = output ?: return
            synchronized(dos) {
                dos.writeByte(4) // KeyEvent message type
                dos.writeByte(if (isDown) 1 else 0)
                dos.writeShort(0) // padding
                dos.writeInt(keysym)
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Sends a full key stroke (key down followed by key up).
     */
    fun sendKeyStroke(keysym: Int) {
        sendKeyEvent(keysym, true)
        sendKeyEvent(keysym, false)
    }

    /**
     * Sends typed text string to desktop.
     */
    fun sendText(text: String) {
        for (char in text) {
            val keysym = KeySymbols.charToKeysym(char)
            sendKeyStroke(keysym)
        }
    }

    /**
     * Sends modifier shortcut (e.g. Ctrl+C, Ctrl+V).
     */
    fun sendShortcut(modifierKeysym: Int, keyChar: Char) {
        val charKeysym = KeySymbols.charToKeysym(keyChar)
        sendKeyEvent(modifierKeysym, true)
        sendKeyStroke(charKeysym)
        sendKeyEvent(modifierKeysym, false)
    }

    /**
     * Transfers text from Android clipboard to Linux desktop.
     */
    fun sendClipboardText(text: String) {
        try {
            val dos = output ?: return
            val bytes = text.toByteArray(StandardCharsets.ISO_8859_1)
            synchronized(dos) {
                dos.writeByte(6) // ClientCutText message type
                dos.writeByte(0) // padding 1
                dos.writeByte(0) // padding 2
                dos.writeByte(0) // padding 3
                dos.writeInt(bytes.size)
                dos.write(bytes)
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    fun disconnect() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}
