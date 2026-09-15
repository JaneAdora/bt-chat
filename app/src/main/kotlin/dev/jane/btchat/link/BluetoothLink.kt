package dev.jane.btchat.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Symmetric RFCOMM link: always listening, dialing whenever not connected.
 * Each new socket starts with an 8-byte node id exchange so both sides can
 * apply [TieBreak] when two sockets appear at once.
 *
 * Requires BLUETOOTH_CONNECT; the service checks it before calling [start].
 */
@SuppressLint("MissingPermission")
class BluetoothLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val nodeId: Long,
    private val scope: CoroutineScope,
) : Link {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("a558fe46-ab4a-43c3-b323-820b0da29a31")
        const val SERVICE_NAME = "BT Chat"
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        val BACKOFF_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)
        private const val TAG = "BluetoothLink"
        private const val READ_BUFFER = 8192
    }

    private class Conn(val socket: BluetoothSocket, val dialed: Boolean, val peerNodeId: Long) {
        val input: InputStream = socket.inputStream
        val output: OutputStream = socket.outputStream
        val address: String = socket.remoteDevice.address
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Off)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    private val lock = Any()
    @Volatile private var peer: String? = null
    private var active: Conn? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var dialJob: Job? = null
    private val writeMutex = Mutex()
    private var adapterReceiver: BroadcastReceiver? = null

    // Bumped every time the radio is torn down. A handshake or connect() that finishes
    // after teardown carries a stale epoch and is discarded instead of being adopted.
    @Volatile private var epoch = 0

    // Incremented on every successful adopt so LinkState.Connected changes value even when
    // the peer address is unchanged (e.g. tie-break keeps the same peer on a new socket).
    private var session = 0

    override fun start(peerAddress: String) {
        if (peer.equals(peerAddress, ignoreCase = true) && _state.value !is LinkState.Off) return
        stop()
        peer = peerAddress.uppercase()
        registerAdapterReceiver()
        if (adapter.isEnabled) startRadio() else _state.value = LinkState.Off
    }

    override fun stop() {
        unregisterAdapterReceiver()
        stopRadio()
        peer = null
        _state.value = LinkState.Off
    }

    override fun dropConnection() {
        val conn = synchronized(lock) { active } ?: return
        onDisconnected(conn)
    }

    override suspend fun send(bytes: ByteArray) {
        val conn = synchronized(lock) { active } ?: throw IOException("not connected")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    conn.output.write(bytes)
                    conn.output.flush()
                } catch (e: IOException) {
                    onDisconnected(conn)
                    throw e
                }
            }
        }
    }

    // Radio lifecycle

    private fun startRadio() {
        _state.value = LinkState.Searching
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
        dialJob = scope.launch(Dispatchers.IO) { dialLoop() }
    }

    private fun stopRadio() {
        acceptJob?.cancel()
        dialJob?.cancel()
        acceptJob = null
        dialJob = null
        serverSocket.closeQuietly()
        serverSocket = null
        val conn = synchronized(lock) {
            epoch++
            active.also { active = null }
        }
        conn?.socket.closeQuietly()
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    // Gate on acceptJob rather than the published state: onDisconnected can
                    // publish Searching after this receiver already published Off, and a
                    // state-based check would then never see Off again and never restart.
                    BluetoothAdapter.STATE_ON -> if (peer != null && acceptJob == null) startRadio()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        stopRadio()
                        _state.value = LinkState.Off
                    }
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        adapterReceiver = receiver
    }

    private fun unregisterAdapterReceiver() {
        adapterReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        adapterReceiver = null
    }

    // Listening

    private suspend fun acceptLoop() {
        while (currentCoroutineContext().isActive) {
            val server = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            } catch (e: IOException) {
                Log.w(TAG, "listen failed: ${e.message}")
                delay(5_000)
                continue
            }
            serverSocket = server
            try {
                while (currentCoroutineContext().isActive) {
                    val socket = server.accept()
                    val address = socket.remoteDevice.address
                    if (!address.equals(peer, ignoreCase = true)) {
                        Log.i(TAG, "rejecting connection from $address")
                        socket.closeQuietly()
                        continue
                    }
                    val acceptedEpoch = epoch
                    scope.launch(Dispatchers.IO) { handshake(socket, dialed = false, connEpoch = acceptedEpoch) }
                }
            } catch (e: IOException) {
                Log.d(TAG, "accept loop ended: ${e.message}")
            } finally {
                server.closeQuietly()
                if (serverSocket === server) serverSocket = null
            }
            delay(1_000)
        }
    }

    // Dialing

    private suspend fun dialLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (synchronized(lock) { active } != null) return
            val target = peer ?: return
            val dialEpoch = epoch
            val device = adapter.getRemoteDevice(target)
            val socket = try {
                device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            } catch (e: IOException) {
                null
            }
            if (socket != null) {
                val connected = try {
                    socket.connect()
                    true
                } catch (e: IOException) {
                    socket.closeQuietly()
                    false
                }
                if (connected && handshake(socket, dialed = true, connEpoch = dialEpoch)) return
            }
            delay(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)])
            attempt++
        }
    }

    // Handshake and adoption

    private fun handshake(socket: BluetoothSocket, dialed: Boolean, connEpoch: Int): Boolean {
        // A peer that connects and then stalls must not hang the accept or dial loop.
        // handshakeDone guards against the watchdog closing a socket that was just adopted:
        // it is set before the watchdog is cancelled, and the watchdog rechecks it right
        // before acting, so a race between "handshake just finished" and "delay just elapsed"
        // cannot close a live, adopted socket.
        val handshakeDone = AtomicBoolean(false)
        val watchdog = scope.launch(Dispatchers.IO) {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (!handshakeDone.get()) socket.closeQuietly()
        }
        try {
            return handshakeBlocking(socket, dialed, connEpoch)
        } finally {
            handshakeDone.set(true)
            watchdog.cancel()
        }
    }

    private fun handshakeBlocking(socket: BluetoothSocket, dialed: Boolean, connEpoch: Int): Boolean = try {
        val out = socket.outputStream
        val inp = socket.inputStream
        out.write(ByteBuffer.allocate(8).putLong(nodeId).array())
        out.flush()
        val buf = ByteArray(8)
        var got = 0
        while (got < 8) {
            val n = inp.read(buf, got, 8 - got)
            if (n < 0) throw IOException("closed during handshake")
            got += n
        }
        adopt(Conn(socket, dialed, ByteBuffer.wrap(buf).long), connEpoch)
    } catch (e: IOException) {
        Log.d(TAG, "handshake failed (dialed=$dialed): ${e.message}")
        socket.closeQuietly()
        false
    }

    /** Returns true if [conn] became the active connection. */
    private fun adopt(conn: Conn, connEpoch: Int): Boolean {
        val toClose: Conn?
        val mySession: Int
        synchronized(lock) {
            if (connEpoch != epoch) {
                // Handshake or connect() finished after stop()/adapter-off tore the radio
                // down; do not resurrect a connection for a generation that no longer exists.
                Log.i(TAG, "discarding stale socket from epoch $connEpoch (current $epoch)")
                conn.socket.closeQuietly()
                return false
            }
            val current = active
            if (current != null && current.dialed != conn.dialed) {
                val keep = TieBreak.decide(nodeId, conn.peerNodeId)
                val keepNew = (keep == TieBreak.Keep.DIALED) == conn.dialed
                if (!keepNew) {
                    Log.i(TAG, "tie-break: keeping existing socket")
                    conn.socket.closeQuietly()
                    return false
                }
            }
            toClose = current
            active = conn
            session++
            mySession = session
        }
        toClose?.socket.closeQuietly()
        dialJob?.cancel()
        dialJob = null
        scope.launch(Dispatchers.IO) { readLoop(conn) }
        _state.value = LinkState.Connected(conn.address, mySession)
        Log.i(TAG, "connected to ${conn.address} (dialed=${conn.dialed})")
        return true
    }

    private suspend fun readLoop(conn: Conn) {
        val buf = ByteArray(READ_BUFFER)
        try {
            while (true) {
                val n = conn.input.read(buf)
                if (n < 0) break
                if (synchronized(lock) { active } !== conn) break
                inboundChannel.send(buf.copyOf(n))
            }
        } catch (e: IOException) {
            Log.d(TAG, "read ended: ${e.message}")
        }
        onDisconnected(conn)
    }

    private fun onDisconnected(conn: Conn) {
        // Decide what to publish while holding the lock, so this races cleanly against
        // stopRadio()/adapter-off instead of publishing Searching over a state that a
        // concurrent teardown already moved to Off (which would wedge the radio: the
        // adapter-off receiver no longer sees Off and never restarts on STATE_ON).
        val publish: LinkState? = synchronized(lock) {
            if (active !== conn) return@synchronized null
            active = null
            // Drop any tail bytes left in the channel from this dead session; feeding
            // them to the next session's fresh FrameReader would corrupt its first
            // frame. Only safe here, inside the lock, where active === conn proves
            // this is the real teardown and not a superseded/tie-break-losing
            // connection whose replacement readLoop may already be live on the
            // same channel.
            while (inboundChannel.tryReceive().isSuccess) { }
            when {
                peer == null -> null
                !adapter.isEnabled -> LinkState.Off
                else -> LinkState.Searching
            }
        }
        conn.socket.closeQuietly()
        val next = publish ?: return
        _state.value = next
        if (next is LinkState.Searching && dialJob?.isActive != true) {
            dialJob = scope.launch(Dispatchers.IO) { dialLoop() }
        }
    }

    private fun BluetoothSocket?.closeQuietly() {
        try { this?.close() } catch (_: IOException) { }
    }

    private fun BluetoothServerSocket?.closeQuietly() {
        try { this?.close() } catch (_: IOException) { }
    }
}
