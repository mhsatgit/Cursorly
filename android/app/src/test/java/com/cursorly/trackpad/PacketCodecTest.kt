package com.cursorly.trackpad.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketCodecTest {

    @Test
    fun encodeTouchPacketMatchesBigEndianLayout() {
        val pkt = PacketCodec.encodeTouchPacket(
            eventType = PacketCodec.EVENT_MOVE,
            pointerId = 3,
            x = 123.5f,
            y = 456.25f,
            timestampSec = 10.5,
        )
        assertEquals(PacketCodec.PACKET_SIZE, pkt.size)

        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, buf.get().toInt() and 0xFF)
        assertEquals(3, buf.get().toInt() and 0xFF)
        assertEquals(123.5f, buf.float, 1e-6f)
        assertEquals(456.25f, buf.float, 1e-6f)
        assertEquals(10.5, buf.double, 1e-9)
    }

    @Test
    fun rfcommFrameRoundTrip() {
        val payload = """{"action":"left_click"}""".toByteArray(Charsets.UTF_8)
        val encoded = PacketCodec.encodeRfcommFrame(PacketCodec.RFCOMM_FRAME_JSON_CONTROL, payload)
        val decoded = PacketCodec.readRfcommFrame(ByteArrayInputStream(encoded))!!
        assertEquals(PacketCodec.RFCOMM_FRAME_JSON_CONTROL, decoded.first)
        assertEquals("""{"action":"left_click"}""", String(decoded.second, Charsets.UTF_8))
    }

    @Test
    fun jsonHelpersEscapeControlCharacters() {
        val json = PacketCodec.jsonKeyText("hi \"there\"\n")
        assertEquals("""{"action":"key_text","text":"hi \"there\"\n"}""", json)
        assertEquals("""{"action":"set_tap_to_click","enabled":false}""", PacketCodec.jsonSetTapToClick(false))
    }
}
