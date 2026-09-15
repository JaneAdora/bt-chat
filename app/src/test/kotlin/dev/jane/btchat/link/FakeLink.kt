package dev.jane.btchat.link

import dev.jane.btchat.protocol.Frame
import dev.jane.btchat.protocol.FrameCodec
import dev.jane.btchat.protocol.FrameReader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class FakeLink : Link {
    val stateFlow = MutableStateFlow<LinkState>(LinkState.Off)
    override val state: StateFlow<LinkState> = stateFlow.asStateFlow()

    private val inboundChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val inbound: Flow<ByteArray> = inboundChannel.receiveAsFlow()

    val sent = CopyOnWriteArrayList<ByteArray>()
    private val sentReader = FrameReader()
    private val sentFrameList = mutableListOf<Frame>()

    @Volatile var failSends = false
    @Volatile var dropped = 0
    @Volatile var startedWith: String? = null
    private var connectSession = 0

    override suspend fun send(bytes: ByteArray) {
        if (failSends || state.value !is LinkState.Connected) throw IOException("not connected")
        sent += bytes
        synchronized(sentFrameList) {
            sentFrameList += sentReader.feed(bytes)
        }
    }

    override fun start(peerAddress: String) {
        startedWith = peerAddress
        stateFlow.value = LinkState.Searching
    }

    override fun stop() {
        stateFlow.value = LinkState.Off
    }

    override fun dropConnection() {
        dropped++
        stateFlow.value = LinkState.Searching
    }

    fun connect(peer: String) {
        connectSession++
        stateFlow.value = LinkState.Connected(peer, connectSession)
    }

    fun disconnect() {
        stateFlow.value = LinkState.Searching
    }

    /** Simulate bytes arriving from the peer. */
    fun receive(frame: Frame) {
        inboundChannel.trySend(FrameCodec.encode(frame))
    }

    fun receiveRaw(bytes: ByteArray) {
        inboundChannel.trySend(bytes)
    }

    fun sentFrames(): List<Frame> = synchronized(sentFrameList) { sentFrameList.toList() }
}
