package dev.jane.btchat.service

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepaliveTest {
    private val k = Keepalive(pingAfterMs = 20_000, dropAfterMs = 35_000)

    @Test
    fun `quiet link under 20s does nothing`() {
        k.reset(0)
        assertEquals(Keepalive.Action.NONE, k.tick(19_999))
    }

    @Test
    fun `pings once at 20s idle and does not repeat immediately`() {
        k.reset(0)
        assertEquals(Keepalive.Action.PING, k.tick(20_000))
        assertEquals(Keepalive.Action.NONE, k.tick(25_000))
        assertEquals(Keepalive.Action.NONE, k.tick(34_999))
    }

    @Test
    fun `drops at 35s idle`() {
        k.reset(0)
        k.tick(20_000)
        assertEquals(Keepalive.Action.DROP, k.tick(35_000))
    }

    @Test
    fun `any inbound resets the idle clock`() {
        k.reset(0)
        k.tick(20_000)
        k.inbound(26_000)
        assertEquals(Keepalive.Action.NONE, k.tick(40_000))
        assertEquals(Keepalive.Action.PING, k.tick(46_000))
        assertEquals(Keepalive.Action.DROP, k.tick(61_000))
    }

    @Test
    fun `reset clears a pending ping`() {
        k.reset(0)
        k.tick(20_000)
        k.reset(100_000)
        assertEquals(Keepalive.Action.NONE, k.tick(110_000))
        assertEquals(Keepalive.Action.PING, k.tick(120_000))
    }
}
