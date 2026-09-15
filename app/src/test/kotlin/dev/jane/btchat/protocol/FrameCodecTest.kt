package dev.jane.btchat.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCodecTest {
    private fun ping() = Frame(FrameType.PING, 0L, ByteArray(0))

    @Test
    fun `header is 16 bytes big endian followed by body`() {
        val bytes = FrameCodec.encode(Frame(FrameType.TEXT, 0x0102030405060708L, byteArrayOf(9, 9, 9)))
        assertEquals(19, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(2, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0, 3), bytes.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), bytes.copyOfRange(8, 16))
        assertArrayEquals(byteArrayOf(9, 9, 9), bytes.copyOfRange(16, 19))
    }

    @Test
    fun `parseHeader round trips every type`() {
        for (type in FrameType.entries) {
            val header = FrameCodec.parseHeader(FrameCodec.encode(Frame(type, 42L, ByteArray(5))))
            assertEquals(type, header.type)
            assertEquals(5, header.bodyLength)
            assertEquals(42L, header.id)
        }
    }

    @Test
    fun `parseHeader honors offset`() {
        val bytes = byteArrayOf(0x55, 0x55) + FrameCodec.encode(Frame(FrameType.ACK, 7L, ByteArray(0)))
        val header = FrameCodec.parseHeader(bytes, offset = 2)
        assertEquals(FrameType.ACK, header.type)
        assertEquals(7L, header.id)
    }

    @Test
    fun `unknown type parses with null type`() {
        val bytes = FrameCodec.encode(ping())
        bytes[1] = 0x7F
        assertNull(FrameCodec.parseHeader(bytes).type)
    }

    @Test(expected = ProtocolException::class)
    fun `wrong version is rejected`() {
        val bytes = FrameCodec.encode(ping())
        bytes[0] = 2
        FrameCodec.parseHeader(bytes)
    }

    @Test(expected = ProtocolException::class)
    fun `nonzero reserved bytes are rejected`() {
        val bytes = FrameCodec.encode(ping())
        bytes[3] = 1
        FrameCodec.parseHeader(bytes)
    }

    @Test(expected = ProtocolException::class)
    fun `oversized body length is rejected`() {
        val bytes = FrameCodec.encode(ping())
        bytes[4] = 0x7F
        FrameCodec.parseHeader(bytes)
    }

    @Test(expected = ProtocolException::class)
    fun `negative body length is rejected`() {
        val bytes = FrameCodec.encode(ping())
        bytes[4] = 0xFF.toByte()
        FrameCodec.parseHeader(bytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode refuses a body over the max`() {
        FrameCodec.encode(Frame(FrameType.PHOTO, 1L, ByteArray(FrameCodec.MAX_BODY + 1)))
    }

    @Test
    fun `max size body encodes and parses`() {
        val bytes = FrameCodec.encode(Frame(FrameType.PHOTO, 1L, ByteArray(FrameCodec.MAX_BODY)))
        assertEquals(FrameCodec.MAX_BODY, FrameCodec.parseHeader(bytes).bodyLength)
    }

    @Test
    fun `frames with equal content are equal`() {
        assertEquals(Frame(FrameType.TEXT, 1L, byteArrayOf(1)), Frame(FrameType.TEXT, 1L, byteArrayOf(1)))
    }
}
