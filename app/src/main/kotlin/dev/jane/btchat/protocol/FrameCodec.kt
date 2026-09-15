package dev.jane.btchat.protocol

import java.nio.ByteBuffer

object FrameCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val MAX_BODY = 2 * 1024 * 1024

    class Header(val type: FrameType?, val bodyLength: Int, val id: Long)

    fun encode(frame: Frame): ByteArray {
        require(frame.body.size <= MAX_BODY) { "body too large: ${frame.body.size}" }
        val buf = ByteBuffer.allocate(HEADER_SIZE + frame.body.size)
        buf.put(VERSION.toByte())
        buf.put(frame.type.code.toByte())
        buf.putShort(0)
        buf.putInt(frame.body.size)
        buf.putLong(frame.id)
        buf.put(frame.body)
        return buf.array()
    }

    fun parseHeader(bytes: ByteArray, offset: Int = 0): Header {
        require(bytes.size - offset >= HEADER_SIZE) { "need $HEADER_SIZE bytes" }
        val buf = ByteBuffer.wrap(bytes, offset, HEADER_SIZE)
        val version = buf.get().toInt() and 0xFF
        if (version != VERSION) throw ProtocolException("unsupported version $version")
        val typeCode = buf.get().toInt() and 0xFF
        val reserved = buf.getShort().toInt()
        if (reserved != 0) throw ProtocolException("reserved bytes must be zero")
        val length = buf.getInt()
        if (length < 0 || length > MAX_BODY) throw ProtocolException("bad body length $length")
        val id = buf.getLong()
        return Header(FrameType.fromCode(typeCode), length, id)
    }
}
