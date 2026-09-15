package dev.jane.btchat.service

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.jane.btchat.App
import dev.jane.btchat.link.BluetoothLink
import dev.jane.btchat.link.LinkState
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the BluetoothLink and ChatEngine for the active peer.
 * Started by the activity, the boot receiver, and the notification toggle.
 */
class ChatService : Service() {
    companion object {
        const val ACTION_TOGGLE_STAY = "dev.jane.btchat.action.TOGGLE_STAY"

        fun start(context: Context) {
            if (!hasBluetoothPermission(context)) return
            ContextCompat.startForegroundService(context, Intent(context, ChatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatService::class.java))
        }

        fun hasBluetoothPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: ChatEngine? = null
    private val mismatch = MutableStateFlow(false)

    private val listener = object : ChatEngine.Listener {
        override fun onInbound(message: MessageEntity) {
            if (ChatVisibility.visiblePeer.value == message.peer && ChatVisibility.appInForeground.value) return
            scope.launch { postMessageNotification(message.peer) }
        }

        override fun onProtocolMismatch(peerProto: Int) {
            mismatch.value = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.ID_SERVICE,
            Notifications.service(this, "Starting", true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        LinkStateHolder.serviceRunning.value = true
        if (!hasBluetoothPermission(this)) {
            stopSelf()
            return
        }
        scope.launch { boot() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun boot() {
        val app = App.get(this)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            stopSelf()
            return
        }
        val link = BluetoothLink(this, adapter, app.settings.nodeId(), scope)
        val e = ChatEngine(link, app.db, app.settings, app.photoStore, listener, scope)
        engine = e

        scope.launch { link.state.collect { LinkStateHolder.state.value = it } }

        val peerNick = app.settings.activePeer.flatMapLatest { peer ->
            if (peer == null) flowOf(null) else app.db.peers().observe(peer)
        }.map { it?.nick ?: "the other phone" }

        scope.launch {
            combine(link.state, peerNick, app.settings.stayConnected, mismatch) { state, nick, stay, bad ->
                val title = when {
                    bad -> "Version mismatch, update BT Chat on both phones"
                    state is LinkState.Connected -> "Connected to $nick"
                    state is LinkState.Searching -> "Looking for $nick..."
                    else -> "Bluetooth is off"
                }
                Notifications.service(this@ChatService, title, stay)
            }.collect { notification ->
                NotificationManagerCompat.from(this@ChatService).notify(Notifications.ID_SERVICE, notification)
            }
        }

        app.settings.activePeer.distinctUntilChanged().collect { peer ->
            mismatch.value = false
            if (peer == null) e.stop() else e.start(peer)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_STAY) {
            scope.launch {
                val settings = App.get(this@ChatService).settings
                val on = !settings.stayConnected.first()
                settings.setStayConnected(on)
                if (!on && !ChatVisibility.appInForeground.value) stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun postMessageNotification(peer: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val app = App.get(this)
        val nick = app.db.peers().get(peer)?.nick ?: "the other phone"
        val unread = app.db.messages().latestInbound(peer, 5).filter { it.status == Status.RECEIVED }.reversed()
        if (unread.isEmpty()) return
        try {
            NotificationManagerCompat.from(this).notify(Notifications.ID_MESSAGE, Notifications.message(this, nick, peer, unread))
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        scope.cancel()
        LinkStateHolder.state.value = LinkState.Off
        LinkStateHolder.serviceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
