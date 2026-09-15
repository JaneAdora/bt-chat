package dev.jane.btchat.service

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.jane.btchat.link.FakeLink
import dev.jane.btchat.link.LinkState
import dev.jane.btchat.protocol.Bodies
import dev.jane.btchat.protocol.Frame
import dev.jane.btchat.protocol.FrameType
import dev.jane.btchat.protocol.Frames
import dev.jane.btchat.protocol.HelloBody
import dev.jane.btchat.protocol.PhotoMeta
import dev.jane.btchat.store.AppDatabase
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.Settings
import dev.jane.btchat.store.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChatEngineTest {
    private val peer = "AA:BB:CC:DD:EE:FF"
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var link: FakeLink
    private lateinit var photos: FakePhotoStore
    private lateinit var scope: CoroutineScope
    private lateinit var engine: ChatEngine
    private val inbound = mutableListOf<MessageEntity>()
    private val mismatches = mutableListOf<Int>()
    @Volatile private var now = 1_000_000L

    private val listener = object : ChatEngine.Listener {
        override fun onInbound(message: MessageEntity) { synchronized(inbound) { inbound += message } }
        override fun onProtocolMismatch(peerProto: Int) { mismatches += peerProto }
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemory(context)
        val dir = File(context.cacheDir, "engine-${System.nanoTime()}").apply { mkdirs() }
        settings = Settings(PreferenceDataStoreFactory.create { File(dir, "settings.preferences_pb") })
        settings.setNick("Mom")
        settings.setColor("#1F5F3F")
        link = FakeLink()
        photos = FakePhotoStore(File(dir, "photos"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine = ChatEngine(link, db, settings, photos, listener, scope, clock = { now }, tickMs = 50)
    }

    @After
    fun tearDown() = runBlocking {
        engine.stop()
        // Join, not just cancel: let in-flight collectors actually finish so they don't
        // log "connection is closed" stack traces against an already-closed db.
        scope.coroutineContext[Job]?.cancelAndJoin()
        db.close()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, what: String = "condition", cond: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(20) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    private fun sentOfType(type: FrameType): List<Frame> = link.sentFrames().filter { it.type == type }

    private fun queuedText(id: Long, createdAt: Long, text: String = "m$id") = MessageEntity(
        id = id, peer = peer, direction = Direction.OUT, kind = Kind.TEXT, text = text,
        photoPath = null, thumbPath = null, photoWidth = null, photoHeight = null,
        createdAt = createdAt, status = Status.QUEUED, deliveredAt = null, readAt = null, readPending = false,
    )

    private suspend fun startAndConnect() {
        engine.start(peer)
        link.connect(peer)
        awaitUntil(what = "hello") { sentOfType(FrameType.HELLO).isNotEmpty() }
    }

    @Test
    fun `sends hello with nick on connect`() = runBlocking {
        startAndConnect()
        val hello = Bodies.decodeHello(sentOfType(FrameType.HELLO).first().body)
        assertEquals("Mom", hello.nick)
        assertEquals("#1F5F3F", hello.color)
        assertEquals(peer, link.startedWith)
    }

    @Test
    fun `queued text is sent then delivered on ack`() = runBlocking {
        db.messages().insert(queuedText(1, 10))
        startAndConnect()
        awaitUntil(what = "text sent") { sentOfType(FrameType.TEXT).any { it.id == 1L } }
        awaitUntil(what = "status sent") { db.messages().get(1, peer)!!.status == Status.SENT }
        assertEquals("m1", Bodies.decodeText(sentOfType(FrameType.TEXT).first().body).text)
        link.receive(Frames.ack(1))
        awaitUntil(what = "delivered") { db.messages().get(1, peer)!!.status == Status.DELIVERED }
        assertEquals(now, db.messages().get(1, peer)!!.deliveredAt)
    }

    @Test
    fun `messages go out in created order`() = runBlocking {
        db.messages().insert(queuedText(3, 30))
        db.messages().insert(queuedText(1, 10))
        db.messages().insert(queuedText(2, 20))
        startAndConnect()
        awaitUntil(what = "three sent") { sentOfType(FrameType.TEXT).size == 3 }
        assertEquals(listOf(1L, 2L, 3L), sentOfType(FrameType.TEXT).map { it.id })
    }

    @Test
    fun `messages queued while connected are sent`() = runBlocking {
        startAndConnect()
        db.messages().insert(queuedText(5, 50))
        awaitUntil(what = "late text sent") { sentOfType(FrameType.TEXT).any { it.id == 5L } }
    }

    @Test
    fun `inbound text is stored acked and reported once`() = runBlocking {
        startAndConnect()
        link.receive(Frames.text(77, 123L, "hi there"))
        link.receive(Frames.text(77, 123L, "hi there"))
        awaitUntil(what = "two acks") { sentOfType(FrameType.ACK).count { it.id == 77L } == 2 }
        val stored = db.messages().get(77, peer)
        assertNotNull(stored)
        assertEquals("hi there", stored!!.text)
        assertEquals(Direction.IN, stored.direction)
        assertEquals(Status.RECEIVED, stored.status)
        assertEquals(123L, stored.createdAt)
        assertEquals(1, synchronized(inbound) { inbound.size })
    }

    @Test
    fun `hello from peer updates the peer row`() = runBlocking {
        startAndConnect()
        link.receive(Frames.hello(HelloBody(1, "Ash", "#2E4A8F", "btchat-android/0.1.0")))
        awaitUntil(what = "peer row") { db.peers().get(peer)?.nick == "Ash" }
        assertEquals("#2E4A8F", db.peers().get(peer)!!.color)
        assertEquals(now, db.peers().get(peer)!!.lastSeen)
    }

    @Test
    fun `protocol mismatch drops the link and notifies`() = runBlocking {
        startAndConnect()
        link.receive(Frames.hello(HelloBody(99, "Future", "#000000", "x")))
        awaitUntil(what = "drop") { link.dropped == 1 }
        assertEquals(listOf(99), mismatches)
    }

    @Test
    fun `disconnect requeues sent but undelivered and resends on reconnect`() = runBlocking {
        db.messages().insert(queuedText(1, 10))
        startAndConnect()
        awaitUntil(what = "sent") { db.messages().get(1, peer)!!.status == Status.SENT }
        link.disconnect()
        awaitUntil(what = "requeued") { db.messages().get(1, peer)!!.status == Status.QUEUED }
        link.connect(peer)
        awaitUntil(what = "resent") { sentOfType(FrameType.TEXT).count { it.id == 1L } == 2 }
        assertEquals(2, sentOfType(FrameType.HELLO).size)
    }

    @Test
    fun `read frame marks outbound read`() = runBlocking {
        db.messages().insert(queuedText(1, 10))
        startAndConnect()
        awaitUntil(what = "sent") { db.messages().get(1, peer)!!.status == Status.SENT }
        link.receive(Frames.read(1))
        awaitUntil(what = "read") { db.messages().get(1, peer)!!.status == Status.READ }
        assertNotNull(db.messages().get(1, peer)!!.readAt)
    }

    @Test
    fun `pending read receipts are sent and cleared`() = runBlocking {
        db.messages().insert(queuedText(40, 10).copy(direction = Direction.IN, status = Status.RECEIVED))
        db.messages().markInboundRead(peer, listOf(40L), 99, pending = true)
        startAndConnect()
        awaitUntil(what = "read frame") { sentOfType(FrameType.READ).any { it.id == 40L } }
        awaitUntil(what = "cleared") { !db.messages().get(40, peer)!!.readPending }
    }

    @Test
    fun `read with receipts off never sends a READ frame`() = runBlocking {
        // ChatViewModel.markRead passes pending = settings.sendReadReceipts; with the toggle off it is false.
        db.messages().insert(queuedText(41, 10).copy(direction = Direction.IN, status = Status.RECEIVED))
        db.messages().markInboundRead(peer, listOf(41L), 99, pending = false)
        startAndConnect()
        delay(300)
        assertEquals(Status.READ, db.messages().get(41, peer)!!.status)
        assertTrue(sentOfType(FrameType.READ).none { it.id == 41L })
    }

    @Test
    fun `ping is answered with pong`() = runBlocking {
        startAndConnect()
        link.receive(Frames.ping())
        awaitUntil(what = "pong") { sentOfType(FrameType.PONG).isNotEmpty() }
    }

    @Test
    fun `garbage bytes drop the connection`() = runBlocking {
        startAndConnect()
        link.receiveRaw(ByteArray(16) { 0x42 })
        awaitUntil(what = "drop") { link.dropped == 1 }
    }

    @Test
    fun `keepalive pings after 20s idle and drops after 35s`() = runBlocking {
        startAndConnect()
        now += 21_000
        awaitUntil(what = "ping") { sentOfType(FrameType.PING).isNotEmpty() }
        assertEquals(0, link.dropped)
        now += 15_000
        awaitUntil(what = "drop") { link.dropped == 1 }
    }

    @Test
    fun `inbound traffic keeps the link alive`() = runBlocking {
        startAndConnect()
        now += 30_000
        link.receive(Frames.pong())
        delay(200)
        now += 10_000
        delay(200)
        assertEquals(0, link.dropped)
    }

    @Test
    fun `inbound photo is saved acked and reported`() = runBlocking {
        startAndConnect()
        val jpeg = ByteArray(500) { it.toByte() }
        link.receive(Frames.photo(9, PhotoMeta(ts = 5L, w = 1280, h = 960), jpeg))
        awaitUntil(what = "ack") { sentOfType(FrameType.ACK).any { it.id == 9L } }
        val m = db.messages().get(9, peer)!!
        assertEquals(Kind.PHOTO, m.kind)
        assertTrue(File(m.photoPath!!).readBytes().contentEquals(jpeg))
        assertEquals(5L, m.createdAt)
        assertEquals(1, photos.exported.size)
        assertEquals(1, synchronized(inbound) { inbound.size })
    }

    @Test
    fun `queued photo is sent with its bytes and dimensions`() = runBlocking {
        val jpeg = ByteArray(700) { (it * 3).toByte() }
        val saved = photos.saveOutbound(21, jpeg, 1280, 720)
        db.messages().insert(
            queuedText(21, 10).copy(kind = Kind.PHOTO, text = null, photoPath = saved.photoPath, thumbPath = saved.thumbPath, photoWidth = 1280, photoHeight = 720)
        )
        startAndConnect()
        awaitUntil(what = "photo sent") { sentOfType(FrameType.PHOTO).any { it.id == 21L } }
        val body = Bodies.decodePhoto(sentOfType(FrameType.PHOTO).first().body)
        assertTrue(body.jpeg.contentEquals(jpeg))
        assertEquals(1280, body.meta.w)
        assertEquals(720, body.meta.h)
        awaitUntil(what = "status sent") { db.messages().get(21, peer)!!.status == Status.SENT }
    }

    @Test
    fun `photo row without a file is dropped instead of blocking the queue`() = runBlocking {
        db.messages().insert(queuedText(30, 10).copy(kind = Kind.PHOTO, text = null))
        db.messages().insert(queuedText(31, 20))
        startAndConnect()
        awaitUntil(what = "next text sent") { sentOfType(FrameType.TEXT).any { it.id == 31L } }
        assertNull(db.messages().get(30, peer))
    }

    @Test
    fun `stop turns the link off`() = runBlocking {
        startAndConnect()
        engine.stop()
        assertEquals(LinkState.Off, link.state.value)
    }

    @Test
    fun `stale sent rows from a prior run are requeued on start`() = runBlocking {
        db.messages().insert(queuedText(60, 10).copy(status = Status.SENT))
        engine.start(peer)
        awaitUntil(what = "requeued on start") { db.messages().get(60, peer)!!.status == Status.QUEUED }
    }

    @Test
    fun `send failure drops the connection so it can be retried`() = runBlocking {
        startAndConnect()
        link.failSends = true
        db.messages().insert(queuedText(50, 10))
        awaitUntil(what = "drop") { link.dropped >= 1 }
        assertFalse(sentOfType(FrameType.TEXT).any { it.id == 50L })
    }
}
