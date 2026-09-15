package dev.jane.btchat.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageIdsTest {
    @Test
    fun `ids are never zero and do not repeat in a small sample`() {
        val ids = (1..10_000).map { MessageIds.next() }
        assertEquals(10_000, ids.toSet().size)
        assertFalse(ids.any { it == 0L })
    }
}
