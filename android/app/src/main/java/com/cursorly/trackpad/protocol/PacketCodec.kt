package com.cursorly.trackpad.protocol

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PacketCodec {
    const val PACKET_SIZE = 18

    const val EVENT_DOWN = 0
    const val EVENT_MOVE = 1
    const val EVENT_UP = 2

    const val RFCOMM_FRAME_BINARY_TOUCH = 0x01
    const val RFCOMM_FRAME_JSON_CONTROL = 0x02

    /**
     * Encodes a touch sample matching Python struct.pack("!BBffd", ...):
     * 1B unsigned eventType, 1B unsigned pointerId, 4B float x, 4B float y, 8B double timestampSec.
     */
    fun encodeTouchPacket(
        eventType: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        timestampSec: Double,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put((eventType and 0xFF).toByte())
        buffer.put((pointerId and 0xFF).toByte())
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putDouble(timestampSec)
        return buffer.array()
    }

    /**
     * Wraps a binary or UTF-8 JSON payload for Bluetooth RFCOMM stream transport:
     * [1B frameType][2B uint16 big-endian length][payload]
     */
    fun encodeRfcommFrame(frameType: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 65535) { "Payload exceeds 65535 bytes: ${payload.size}" }
        val buffer = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put((frameType and 0xFF).toByte())
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    /** Reads a single length-prefixed RFCOMM frame from a blocking InputStream. */
    fun readRfcommFrame(input: InputStream): Pair<Int, ByteArray>? {
        val header = ByteArray(3)
        if (!readFully(input, header, 3)) return null
        val frameType = header[0].toInt() and 0xFF
        val length = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
        val payload = ByteArray(length)
        if (!readFully(input, payload, length)) return null
        return frameType to payload
    }

    private fun readFully(input: InputStream, dest: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = input.read(dest, offset, length - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw EOFException("Unexpected EOF after $offset of $length bytes")
            }
            offset += read
        }
        return true
    }

    fun jsonAction(action: String): String = """{"action":"${escapeJson(action)}"}"""

    fun jsonPing(timestampMs: Long): String = """{"action":"ping","t":$timestampMs}"""

    fun jsonClientHello(deviceName: String, transport: String): String =
        """{"action":"client_hello","device_name":"${escapeJson(deviceName)}","transport":"${escapeJson(transport)}"}"""

    fun jsonSetSensitivity(value: Float): String =
        """{"action":"set_sensitivity","value":${"%.2f".format(java.util.Locale.US, value)}}"""

    fun jsonSetOrientation(orientation: String): String =
        """{"action":"set_orientation","orientation":"${escapeJson(orientation)}"}"""

    fun jsonSetNaturalScroll(enabled: Boolean): String =
        """{"action":"set_natural_scroll","enabled":$enabled}"""

    fun jsonSetTapToClick(enabled: Boolean): String =
        """{"action":"set_tap_to_click","enabled":$enabled}"""

    fun jsonKeyText(text: String): String =
        """{"action":"key_text","text":"${escapeJson(text)}"}"""

    fun jsonKeyPress(key: String): String =
        """{"action":"key_press","key":"${escapeJson(key)}"}"""

    fun jsonZoom(delta: Int): String =
        """{"action":"zoom","delta":$delta}"""

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
