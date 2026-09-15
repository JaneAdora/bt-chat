package dev.jane.btchat.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.jane.btchat.App
import dev.jane.btchat.link.LinkState
import dev.jane.btchat.service.ChatService
import dev.jane.btchat.service.LinkStateHolder
import dev.jane.btchat.service.PhotoStore
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.MessageIds
import dev.jane.btchat.store.PeerEntity
import dev.jane.btchat.store.Settings
import dev.jane.btchat.store.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class ChatViewModel(private val app: App, val peer: String) : ViewModel() {
    sealed interface Event {
        data class Toast(val text: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events

    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val messages: StateFlow<List<MessageEntity>> = app.db.messages().observe(peer).hot(emptyList())
    val peerInfo: StateFlow<PeerEntity?> = app.db.peers().observe(peer).hot(null)
    val linkState: StateFlow<LinkState> = LinkStateHolder.state
    val serviceRunning: StateFlow<Boolean> = LinkStateHolder.serviceRunning
    val myColor: StateFlow<String> = app.settings.myColor.hot(Settings.DEFAULT_COLOR)
    val stayConnected: StateFlow<Boolean> = app.settings.stayConnected.hot(true)

    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            app.db.messages().insert(outbound(MessageIds.next(), Kind.TEXT, text = trimmed))
        }
    }

    fun sendPhoto(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = MessageIds.next()
            val prepared = try {
                app.photoProcessor.prepare(uri)
            } catch (e: IOException) {
                _events.tryEmit(Event.Toast("Couldn't read that photo"))
                return@launch
            } catch (e: SecurityException) {
                _events.tryEmit(Event.Toast("No access to that photo"))
                return@launch
            }
            val saved = try {
                app.photoStore.saveOutbound(id, peer, prepared.jpeg, prepared.width, prepared.height)
            } catch (e: IOException) {
                _events.tryEmit(Event.Toast("Couldn't save the photo, is storage full?"))
                return@launch
            }
            app.db.messages().insert(outbound(id, Kind.PHOTO, saved = saved))
        }
    }

    /** Called by the screen when inbound messages are actually on screen. */
    fun markRead(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val pending = app.settings.sendReadReceipts.first()
            app.db.messages().markInboundRead(peer, ids, System.currentTimeMillis(), pending)
        }
    }

    fun setStayConnected(on: Boolean) {
        viewModelScope.launch {
            app.settings.setStayConnected(on)
            if (on) ChatService.start(app)
        }
    }

    private fun outbound(id: Long, kind: Int, text: String? = null, saved: PhotoStore.Saved? = null) = MessageEntity(
        id = id, peer = peer, direction = Direction.OUT, kind = kind, text = text,
        photoPath = saved?.photoPath, thumbPath = saved?.thumbPath, photoWidth = saved?.width, photoHeight = saved?.height,
        createdAt = System.currentTimeMillis(), status = Status.QUEUED, deliveredAt = null, readAt = null, readPending = false,
    )

    companion object {
        fun factory(app: App, peer: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(app, peer) }
        }
    }
}
