package dev.jane.btchat.protocol

enum class FrameType(val code: Int) {
    HELLO(0x01), TEXT(0x02), PHOTO(0x03), ACK(0x04), READ(0x05), PING(0x06), PONG(0x07);

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

/** One unit on the wire. Content equality so tests can compare frames. */
class Frame(val type: FrameType, val id: Long, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && other.type == type && other.id == id && other.body.contentEquals(body)

    override fun hashCode(): Int = 31 * (31 * type.hashCode() + id.hashCode()) + body.contentHashCode()

    override fun toString(): String = "Frame($type, id=$id, ${body.size} bytes)"
}

/** Thrown when the peer sends bytes we cannot trust. The caller closes the socket. */
class ProtocolException(message: String) : Exception(message)
