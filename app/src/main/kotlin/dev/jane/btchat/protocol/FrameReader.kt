package dev.jane.btchat.protocol

/**
 * Accumulates bytes from a stream and yields complete frames.
 * Not thread safe; one reader per connection, fed from one coroutine.
 */
class FrameReader {
    private var buf = ByteArray(8192)
    private var size = 0
    private var header: FrameCodec.Header? = null

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<Frame> {
        ensureCapacity(size + length)
        System.arraycopy(bytes, offset, buf, size, length)
        size += length

        val out = ArrayList<Frame>()
        while (true) {
            val h = header ?: run {
                if (size < FrameCodec.HEADER_SIZE) return out
                FrameCodec.parseHeader(buf, 0).also { header = it }
            }
            val total = FrameCodec.HEADER_SIZE + h.bodyLength
            if (size < total) return out
            val body = buf.copyOfRange(FrameCodec.HEADER_SIZE, total)
            if (h.type != null) out.add(Frame(h.type, h.id, body))
            System.arraycopy(buf, total, buf, 0, size - total)
            size -= total
            header = null
        }
    }

    fun reset() {
        size = 0
        header = null
    }

    private fun ensureCapacity(needed: Int) {
        if (buf.size >= needed) return
        var cap = buf.size
        while (cap < needed) cap *= 2
        buf = buf.copyOf(cap)
    }
}
