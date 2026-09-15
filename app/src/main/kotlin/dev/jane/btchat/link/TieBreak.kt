package dev.jane.btchat.link

/**
 * When both phones dial each other at the same moment there are two sockets.
 * The lower node id keeps the socket it dialed; the higher keeps the one it accepted.
 * Both sides apply this, so exactly one socket survives.
 */
object TieBreak {
    enum class Keep { DIALED, ACCEPTED }

    fun decide(myNodeId: Long, peerNodeId: Long): Keep =
        if (myNodeId < peerNodeId) Keep.DIALED else Keep.ACCEPTED
}
