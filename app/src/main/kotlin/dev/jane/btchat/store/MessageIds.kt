package dev.jane.btchat.store

import java.security.SecureRandom

/** Sender-generated 64-bit message ids. Zero is reserved for control frames. */
object MessageIds {
    private val random = SecureRandom()

    fun next(): Long {
        var id: Long
        do { id = random.nextLong() } while (id == 0L)
        return id
    }
}
