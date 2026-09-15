package dev.jane.btchat.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HelloBody(val proto: Int, val nick: String, val color: String, val app: String)

@Serializable
data class TextBody(val ts: Long, val text: String)

@Serializable
data class PhotoMeta(val ts: Long, val w: Int, val h: Int, val mime: String = "image/jpeg")

class PhotoBody(val meta: PhotoMeta, val jpeg: ByteArray)

object Bodies {
    const val PROTO = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeHello(body: HelloBody): ByteArray = json.encodeToString(body).encodeToByteArray()
    fun decodeHello(bytes: ByteArray): HelloBody = decode { json.decodeFromString<HelloBody>(bytes.decodeToString()) }

    fun encodeText(body: TextBody): ByteArray = json.encodeToString(body).encodeToByteArray()
    fun decodeText(bytes: ByteArray): TextBody = decode { json.decodeFromString<TextBody>(bytes.decodeToString()) }

    fun encodePhoto(body: PhotoBody): ByteArray {
        val meta = json.encodeToString(body.meta).encodeToByteArray()
        require(meta.size <= 0xFFFF) { "photo meta too large" }
        val out = ByteArray(2 + meta.size + body.jpeg.size)
        out[0] = (meta.size shr 8).toByte()
        out[1] = meta.size.toByte()
        System.arraycopy(meta, 0, out, 2, meta.size)
        System.arraycopy(body.jpeg, 0, out, 2 + meta.size, body.jpeg.size)
        return out
    }

    fun decodePhoto(bytes: ByteArray): PhotoBody {
        if (bytes.size < 2) throw ProtocolException("photo body too short")
        val metaLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (bytes.size < 2 + metaLen) throw ProtocolException("photo meta truncated")
        val meta = decode { json.decodeFromString<PhotoMeta>(bytes.copyOfRange(2, 2 + metaLen).decodeToString()) }
        return PhotoBody(meta, bytes.copyOfRange(2 + metaLen, bytes.size))
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw ProtocolException("bad body: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ProtocolException("bad body: ${e.message}")
    }
}

object Frames {
    fun hello(body: HelloBody) = Frame(FrameType.HELLO, 0L, Bodies.encodeHello(body))
    fun text(id: Long, ts: Long, text: String) = Frame(FrameType.TEXT, id, Bodies.encodeText(TextBody(ts, text)))
    fun photo(id: Long, meta: PhotoMeta, jpeg: ByteArray) = Frame(FrameType.PHOTO, id, Bodies.encodePhoto(PhotoBody(meta, jpeg)))
    fun ack(id: Long) = Frame(FrameType.ACK, id, ByteArray(0))
    fun read(id: Long) = Frame(FrameType.READ, id, ByteArray(0))
    fun ping() = Frame(FrameType.PING, 0L, ByteArray(0))
    fun pong() = Frame(FrameType.PONG, 0L, ByteArray(0))
}
