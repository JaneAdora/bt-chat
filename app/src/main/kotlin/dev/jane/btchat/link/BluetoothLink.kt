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
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var dialJob: Job? = null
    private val writeMutex = Mutex()
    private var adapterReceiver: BroadcastReceiver? = null

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
        val conn = synchronized(lock) { active.also { active = null } }
        conn?.socket.closeQuietly()
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> if (peer != null && _state.value is LinkState.Off) startRadio()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        stopRadio()
                        _state.value = LinkState.Off
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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
                    scope.launch(Dispatchers.IO) { handshake(socket, dialed = false) }
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
                if (connected && handshake(socket, dialed = true)) return
            }
            delay(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)])
            attempt++
        }
    }

    // Handshake and adoption

    private fun handshake(socket: BluetoothSocket, dialed: Boolean): Boolean {
        // A peer that connects and then stalls must not hang the accept or dial loop.
        val watchdog = scope.launch { delay(HANDSHAKE_TIMEOUT_MS); socket.closeQuietly() }
        try { return handshakeBlocking(socket, dialed) } finally { watchdog.cancel() }
    }

    private fun handshakeBlocking(socket: BluetoothSocket, dialed: Boolean): Boolean = try {
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
        adopt(Conn(socket, dialed, ByteBuffer.wrap(buf).long))
    } catch (e: IOException) {
        Log.d(TAG, "handshake failed (dialed=$dialed): ${e.message}")
        socket.closeQuietly()
        false
    }

    /** Returns true if [conn] became the active connection. */
    private fun adopt(conn: Conn): Boolean {
        val toClose: Conn?
        synchronized(lock) {
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
        }
        toClose?.socket.closeQuietly()
        dialJob?.cancel()
        dialJob = null
        scope.launch(Dispatchers.IO) { readLoop(conn) }
        _state.value = LinkState.Connected(conn.address)
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
        val wasActive = synchronized(lock) {
            if (active === conn) {
                active = null
                true
            } else {
                false
            }
        }
        conn.socket.closeQuietly()
        if (!wasActive) return
        if (peer == null || !adapter.isEnabled) return
        _state.value = LinkState.Searching
        if (dialJob?.isActive != true) dialJob = scope.launch(Dispatchers.IO) { dialLoop() }
    }

    private fun BluetoothSocket?.closeQuietly() {
        try { this?.close() } catch (_: IOException) { }
    }

    private fun BluetoothServerSocket?.closeQuietly() {
        try { this?.close() } catch (_: IOException) { }
    }
}
