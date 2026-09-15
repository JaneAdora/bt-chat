package dev.jane.btchat.service

import android.util.Log
import dev.jane.btchat.link.Link
import dev.jane.btchat.link.LinkState
import dev.jane.btchat.protocol.Bodies
import dev.jane.btchat.protocol.Frame
import dev.jane.btchat.protocol.FrameCodec
import dev.jane.btchat.protocol.FrameReader
import dev.jane.btchat.protocol.FrameType
import dev.jane.btchat.protocol.Frames
import dev.jane.btchat.protocol.PhotoMeta
import dev.jane.btchat.protocol.ProtocolException
import dev.jane.btchat.store.AppDatabase
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.PeerEntity
import dev.jane.btchat.store.Settings
import dev.jane.btchat.store.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The only class that knows both frames and the database.
 * One engine per active peer. Owns the session lifecycle on top of a [Link].
 */
class ChatEngine(
    private val link: Link,
    private val db: AppDatabase,
    private val settings: Settings,
    private val photoStore: PhotoStore,
    private val listener: Listener,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepalive: Keepalive = Keepalive(),
    private val tickMs: Long = Keepalive.TICK_MS,
) {
    interface Listener {
        /** A new inbound message was stored. Called once per message, never for duplicates. */
        fun onInbound(message: MessageEntity)

        /** The peer speaks a different protocol version. The link has been dropped. */
        fun onProtocolMismatch(peerProto: Int)
    }

    @Volatile private var peer: String? = null
    private var mainJob: Job? = null
    private var sessionJob: Job? = null
    @Volatile private var reader = FrameReader()
    private val stateMutex = Mutex()

    fun start(peerAddress: String) {
        stop()
        peer = peerAddress
        link.start(peerAddress)
        mainJob = scope.launch {
            // Rows left SENT by a stop() or a process kill must go back to QUEUED
            // before the first connection of this session, or they are lost.
            db.messages().requeueSent(peerAddress)
            launch { readLoop() }
            link.state.collect { st ->
                try {
                    stateMutex.withLock { onStateChanged(st) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "onStateChanged failed for $peerAddress", e)
                }
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        mainJob?.cancel()
        mainJob = null
        if (peer != null) link.stop()
        peer = null
    }

    private suspend fun onStateChanged(state: LinkState) {
        val p = peer ?: return
        // Join, not just cancel: an in-flight markSent from the old session's drainQueue
        // must finish (or be cancelled cleanly) before we requeue, or the requeue can be
        // clobbered by a write that lands after it.
        sessionJob?.cancelAndJoin()
        sessionJob = null
        if (state is LinkState.Connected) {
            // Fresh reader per session: the old one is never touched again, so there is
            // no shared mutable buffer for readLoop and this state collector to race on.
            reader = FrameReader()
            keepalive.reset(clock())
            sessionJob = scope.launch {
                try {
                    sendFrame(Frames.hello(settings.helloBody()))
                } catch (e: IOException) {
                    link.dropConnection()
                    return@launch
                }
                launch { drainQueue(p) }
                launch { sendPendingReads(p) }
                launch { keepaliveLoop() }
            }
        } else {
            db.messages().requeueSent(p)
        }
    }

    private suspend fun sendFrame(frame: Frame) = link.send(FrameCodec.encode(frame))

    private suspend fun readLoop() {
        link.inbound.collect { chunk ->
            val frames = try {
                reader.feed(chunk)
            } catch (e: ProtocolException) {
                reader.reset()
                link.dropConnection()
                return@collect
            }
            keepalive.inbound(clock())
            for (frame in frames) {
                try {
                    handle(frame)
                } catch (e: ProtocolException) {
                    reader.reset()
                    link.dropConnection()
                    return@collect
                } catch (e: IOException) {
                    link.dropConnection()
                    return@collect
                }
            }
        }
    }

    private suspend fun handle(frame: Frame) {
        val p = peer ?: return
        when (frame.type) {
            FrameType.HELLO -> {
                val hello = Bodies.decodeHello(frame.body)
                if (hello.proto != Bodies.PROTO) {
                    listener.onProtocolMismatch(hello.proto)
                    link.dropConnection()
                    return
                }
                db.peers().upsert(PeerEntity(p, hello.nick, hello.color, clock()))
            }
            FrameType.TEXT -> {
                val body = Bodies.decodeText(frame.body)
                val message = inboundRow(frame.id, p, Kind.TEXT, body.ts, text = body.text)
                val fresh = db.messages().insert(message) != -1L
                if (fresh) listener.onInbound(message)
                sendFrame(Frames.ack(frame.id))
            }
            FrameType.PHOTO -> {
                val body = Bodies.decodePhoto(frame.body)
                if (db.messages().get(frame.id, p) == null) {
                    val saved = photoStore.saveInbound(frame.id, p, body.jpeg)
                    val message = inboundRow(frame.id, p, Kind.PHOTO, body.meta.ts, saved = saved)
                    if (db.messages().insert(message) != -1L) listener.onInbound(message)
                }
                sendFrame(Frames.ack(frame.id))
            }
            FrameType.ACK -> db.messages().markDelivered(frame.id, p, clock())
            FrameType.READ -> db.messages().markReadByPeer(frame.id, p, clock())
            FrameType.PING -> sendFrame(Frames.pong())
            FrameType.PONG -> Unit
        }
    }

    private fun inboundRow(id: Long, p: String, kind: Int, ts: Long, text: String? = null, saved: PhotoStore.Saved? = null) =
        MessageEntity(
            id = id, peer = p, direction = Direction.IN, kind = kind, text = text,
            photoPath = saved?.photoPath, thumbPath = saved?.thumbPath,
            photoWidth = saved?.width, photoHeight = saved?.height,
            createdAt = ts, status = Status.RECEIVED, deliveredAt = null, readAt = null, readPending = false,
        )

    private suspend fun drainQueue(p: String) {
        db.messages().observeQueued(p).map { it.firstOrNull() }.distinctUntilChanged().collect { m ->
            if (m == null) return@collect
            val frame = when (m.kind) {
                Kind.PHOTO -> {
                    val path = m.photoPath
                    if (path == null) {
                        // A photo row with no file cannot be sent and must not block the queue.
                        db.messages().delete(m.id, p)
                        return@collect
                    }
                    val jpeg = try {
                        photoStore.readOutbound(path)
                    } catch (e: IOException) {
                        db.messages().delete(m.id, p)
                        return@collect
                    }
                    Frames.photo(m.id, PhotoMeta(m.createdAt, m.photoWidth ?: 0, m.photoHeight ?: 0), jpeg)
                }
                else -> Frames.text(m.id, m.createdAt, m.text ?: "")
            }
            try {
                sendFrame(frame)
            } catch (e: IOException) {
                link.dropConnection()
                return@collect
            }
            db.messages().markSent(m.id, p)
        }
    }

    private suspend fun sendPendingReads(p: String) {
        db.messages().observeReadPending(p).collect { pending ->
            for (m in pending) {
                try {
                    sendFrame(Frames.read(m.id))
                } catch (e: IOException) {
                    link.dropConnection()
                    return@collect
                }
                db.messages().clearReadPending(m.id, p)
            }
        }
    }

    private suspend fun keepaliveLoop() {
        while (true) {
            delay(tickMs)
            when (keepalive.tick(clock())) {
                Keepalive.Action.NONE -> Unit
                Keepalive.Action.PING -> try {
                    sendFrame(Frames.ping())
                } catch (e: IOException) {
                    link.dropConnection()
                    return
                }
                Keepalive.Action.DROP -> {
                    link.dropConnection()
                    return
                }
            }
        }
    }

    private companion object {
        const val TAG = "ChatEngine"
    }
}
