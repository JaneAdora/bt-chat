package dev.jane.btchat.link

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Off : LinkState
    data object Searching : LinkState
    data class Connected(val peerAddress: String) : LinkState
}

/**
 * A byte pipe to one peer. Knows nothing about frames or messages.
 * Implementations own reconnection; consumers watch [state].
 */
interface Link {
    val state: StateFlow<LinkState>

    /** Raw chunks as they arrive. Chunk boundaries carry no meaning. */
    val inbound: Flow<ByteArray>

    /** Writes all bytes or throws java.io.IOException. Safe to call from any coroutine. */
    suspend fun send(bytes: ByteArray)

    /** Begin listening for and dialing [peerAddress]. Idempotent. */
    fun start(peerAddress: String)

    /** Close everything and go to Off. */
    fun stop()

    /** Close the active connection and go back to Searching. Used by keepalive and on protocol errors. */
    fun dropConnection()
}
