package dev.jane.btchat.link

import org.junit.Assert.assertEquals
import org.junit.Test

class TieBreakTest {
    @Test
    fun `lower node id keeps the socket it dialed`() {
        assertEquals(TieBreak.Keep.DIALED, TieBreak.decide(myNodeId = 1L, peerNodeId = 2L))
    }

    @Test
    fun `higher node id keeps the socket it accepted`() {
        assertEquals(TieBreak.Keep.ACCEPTED, TieBreak.decide(myNodeId = 2L, peerNodeId = 1L))
    }

    @Test
    fun `both sides agree on the same socket`() {
        val a = 0x1234L
        val b = -0x99L
        val keepA = TieBreak.decide(a, b)
        val keepB = TieBreak.decide(b, a)
        // The socket A dialed is the socket B accepted, so the choices must be opposite.
        assertEquals(keepA == TieBreak.Keep.DIALED, keepB == TieBreak.Keep.ACCEPTED)
    }

    @Test
    fun `equal ids fall back to accepted`() {
        assertEquals(TieBreak.Keep.ACCEPTED, TieBreak.decide(7L, 7L))
    }
}
