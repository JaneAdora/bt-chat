package dev.jane.btchat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameReaderTest {
    private val frames = listOf(
        Frame(FrameType.HELLO, 0L, "hello".encodeToByteArray()),
        Frame(FrameType.TEXT, 11L, "first".encodeToByteArray()),
        Frame(FrameType.ACK, 11L, ByteArray(0)),
        Frame(FrameType.PHOTO, 12L, ByteArray(70_000) { it.toByte() }),
        Frame(FrameType.PING, 0L, ByteArray(0)),
    )
    private val wire: ByteArray = frames.fold(ByteArray(0)) { acc, f -> acc + FrameCodec.encode(f) }

    @Test
    fun `all frames in one feed`() {
        assertEquals(frames, FrameReader().feed(wire))
    }

    @Test
    fun `one byte at a time`() {
        val reader = FrameReader()
        val out = mutableListOf<Frame>()
        for (b in wire) out += reader.feed(byteArrayOf(b))
        assertEquals(frames, out)
    }

    @Test
    fun `random chunk sizes`() {
        val rng = Random(1234)
        repeat(20) {
            val reader = FrameReader()
            val out = mutableListOf<Frame>()
            var pos = 0
            while (pos < wire.size) {
                val n = minOf(rng.nextInt(1, 5000), wire.size - pos)
                out += reader.feed(wire, pos, n)
                pos += n
            }
            assertEquals(frames, out)
        }
    }

    @Test
    fun `unknown type is skipped by length`() {
        val bad = FrameCodec.encode(Frame(FrameType.PONG, 0L, ByteArray(10)))
        bad[1] = 0x7F
        val good = FrameCodec.encode(Frame(FrameType.PING, 0L, ByteArray(0)))
        val out = FrameReader().feed(bad + good)
        assertEquals(listOf(Frame(FrameType.PING, 0L, ByteArray(0))), out)
    }

    @Test(expected = ProtocolException::class)
    fun `garbage prefix throws`() {
        FrameReader().feed(byteArrayOf(0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `reset discards partial data`() {
        val reader = FrameReader()
        reader.feed(wire, 0, 20)
        reader.reset()
        assertEquals(frames, reader.feed(wire))
    }

    @Test
    fun `max size body reassembles from chunks`() {
        val big = Frame(FrameType.PHOTO, 5L, ByteArray(FrameCodec.MAX_BODY) { (it * 7).toByte() })
        val bytes = FrameCodec.encode(big)
        val reader = FrameReader()
        val out = mutableListOf<Frame>()
        var pos = 0
        while (pos < bytes.size) {
            val n = minOf(8192, bytes.size - pos)
            out += reader.feed(bytes, pos, n)
            pos += n
        }
        assertEquals(1, out.size)
        assertTrue(out[0].body.contentEquals(big.body))
    }
}
