package dev.jane.btchat.store

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: AppDatabase
    private val peer = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun outbound(id: Long, createdAt: Long, status: Int = Status.QUEUED) = MessageEntity(
        id = id, peer = peer, direction = Direction.OUT, kind = Kind.TEXT, text = "m$id",
        photoPath = null, thumbPath = null, photoWidth = null, photoHeight = null,
        createdAt = createdAt, status = status, deliveredAt = null, readAt = null, readPending = false,
    )

    private fun inbound(id: Long, createdAt: Long) = outbound(id, createdAt, Status.RECEIVED).copy(direction = Direction.IN)

    @Test
    fun `insert returns minus one on duplicate id and peer`() = runBlocking {
        assertTrue(db.messages().insert(inbound(1, 10)) >= 0)
        assertEquals(-1L, db.messages().insert(inbound(1, 10)))
    }

    @Test
    fun `queued messages come back in created order`() = runBlocking {
        db.messages().insert(outbound(2, 20))
        db.messages().insert(outbound(1, 10))
        db.messages().insert(outbound(3, 30, Status.SENT))
        assertEquals(listOf(1L, 2L), db.messages().observeQueued(peer).first().map { it.id })
    }

    @Test
    fun `status only moves forward`() = runBlocking {
        db.messages().insert(outbound(1, 10))
        db.messages().markSent(1, peer)
        db.messages().markDelivered(1, peer, 100)
        db.messages().markSent(1, peer)
        assertEquals(Status.DELIVERED, db.messages().get(1, peer)!!.status)
        db.messages().markReadByPeer(1, peer, 200)
        db.messages().markDelivered(1, peer, 300)
        val m = db.messages().get(1, peer)!!
        assertEquals(Status.READ, m.status)
        assertEquals(100L, m.deliveredAt)
        assertEquals(200L, m.readAt)
    }

    @Test
    fun `requeueSent only touches sent outbound`() = runBlocking {
        db.messages().insert(outbound(1, 10, Status.SENT))
        db.messages().insert(outbound(2, 20, Status.DELIVERED))
        db.messages().insert(inbound(3, 30))
        db.messages().requeueSent(peer)
        assertEquals(Status.QUEUED, db.messages().get(1, peer)!!.status)
        assertEquals(Status.DELIVERED, db.messages().get(2, peer)!!.status)
        assertEquals(Status.RECEIVED, db.messages().get(3, peer)!!.status)
    }

    @Test
    fun `markInboundRead sets readPending only when asked`() = runBlocking {
        db.messages().insert(inbound(1, 10))
        db.messages().insert(inbound(2, 20))
        db.messages().markInboundRead(peer, listOf(1L), 50, pending = true)
        db.messages().markInboundRead(peer, listOf(2L), 60, pending = false)
        assertEquals(listOf(1L), db.messages().observeReadPending(peer).first().map { it.id })
        assertEquals(Status.READ, db.messages().get(2, peer)!!.status)
        db.messages().clearReadPending(1, peer)
        assertTrue(db.messages().observeReadPending(peer).first().isEmpty())
        assertFalse(db.messages().get(1, peer)!!.readPending)
    }

    @Test
    fun `peer upsert replaces and observe emits`() = runBlocking {
        db.peers().upsert(PeerEntity(peer, "Galaxy S23 FE", "#000000", 1))
        db.peers().upsert(PeerEntity(peer, "Ash", "#123456", 2))
        assertEquals("Ash", db.peers().observe(peer).first()!!.nick)
        assertNull(db.peers().get("00:00:00:00:00:00"))
    }

    @Test
    fun `latestInbound returns newest first and clear wipes the peer`() = runBlocking {
        db.messages().insert(inbound(1, 10))
        db.messages().insert(inbound(2, 20))
        db.messages().insert(outbound(3, 30))
        assertEquals(listOf(2L, 1L), db.messages().latestInbound(peer, 5).map { it.id })
        db.messages().clear(peer)
        assertTrue(db.messages().observe(peer).first().isEmpty())
    }
}
