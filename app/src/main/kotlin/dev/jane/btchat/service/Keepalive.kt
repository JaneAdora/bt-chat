package dev.jane.btchat.service

/**
 * Decides when to ping and when to give up on a silent link.
 * Pure state machine driven by a caller-supplied clock, so it is testable without delays.
 */
class Keepalive(
    private val pingAfterMs: Long = 20_000,
    private val dropAfterMs: Long = 35_000,
) {
    enum class Action { NONE, PING, DROP }

    private var lastInboundAt = 0L
    private var lastPingAt = Long.MIN_VALUE / 2

    fun reset(now: Long) {
        lastInboundAt = now
        lastPingAt = Long.MIN_VALUE / 2
    }

    fun inbound(now: Long) {
        lastInboundAt = now
    }

    fun tick(now: Long): Action {
        val idle = now - lastInboundAt
        if (idle >= dropAfterMs) return Action.DROP
        if (idle >= pingAfterMs && now - lastPingAt >= pingAfterMs) {
            lastPingAt = now
            return Action.PING
        }
        return Action.NONE
    }

    companion object {
        const val TICK_MS = 5_000L
    }
}
