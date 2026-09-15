package dev.jane.btchat.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodiesTest {
    @Test
    fun `hello round trips`() {
        val hello = HelloBody(proto = 1, nick = "Mom", color = "#1F5F3F", app = "btchat-android/0.1.0")
        assertEquals(hello, Bodies.decodeHello(Bodies.encodeHello(hello)))
    }

    @Test
    fun `hello ignores unknown keys from a newer peer`() {
        val bytes = """{"proto":1,"nick":"Ash","color":"#000000","app":"x","extra":true}""".encodeToByteArray()
        assertEquals("Ash", Bodies.decodeHello(bytes).nick)
    }

    @Test
    fun `text round trips with emoji`() {
        val body = TextBody(ts = 1_700_000_000_000L, text = "where are you 🦖")
        assertEquals(body, Bodies.decodeText(Bodies.encodeText(body)))
    }

    @Test
    fun `photo round trips meta and bytes`() {
        val jpeg = ByteArray(3000) { it.toByte() }
        val meta = PhotoMeta(ts = 5L, w = 1280, h = 960)
        val decoded = Bodies.decodePhoto(Bodies.encodePhoto(PhotoBody(meta, jpeg)))
        assertEquals(meta, decoded.meta)
        assertTrue(decoded.jpeg.contentEquals(jpeg))
    }

    @Test
    fun `photo meta length prefix is two bytes big endian`() {
        val bytes = Bodies.encodePhoto(PhotoBody(PhotoMeta(1L, 2, 3), byteArrayOf(0x77)))
        val metaLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        assertEquals(bytes.size - 2 - 1, metaLen)
        assertEquals(0x77, bytes.last().toInt())
    }

    @Test(expected = ProtocolException::class)
    fun `bad text json throws ProtocolException`() {
        Bodies.decodeText("not json".encodeToByteArray())
    }

    @Test(expected = ProtocolException::class)
    fun `bad hello json throws ProtocolException`() {
        Bodies.decodeHello("{".encodeToByteArray())
    }

    @Test(expected = ProtocolException::class)
    fun `truncated photo meta throws ProtocolException`() {
        Bodies.decodePhoto(byteArrayOf(0x01, 0x00, 0x7B))
    }

    @Test
    fun `frame builders set type id and body`() {
        assertEquals(FrameType.HELLO, Frames.hello(HelloBody(1, "a", "#fff", "x")).type)
        val text = Frames.text(9L, 1L, "hi")
        assertEquals(FrameType.TEXT, text.type)
        assertEquals(9L, text.id)
        assertEquals(TextBody(1L, "hi"), Bodies.decodeText(text.body))
        assertEquals(Frame(FrameType.ACK, 9L, ByteArray(0)), Frames.ack(9L))
        assertEquals(Frame(FrameType.READ, 9L, ByteArray(0)), Frames.read(9L))
        assertEquals(Frame(FrameType.PING, 0L, ByteArray(0)), Frames.ping())
        assertEquals(Frame(FrameType.PONG, 0L, ByteArray(0)), Frames.pong())
        val photo = Frames.photo(3L, PhotoMeta(1L, 4, 5), byteArrayOf(1, 2))
        assertEquals(FrameType.PHOTO, photo.type)
        assertEquals(3L, photo.id)
    }
}
