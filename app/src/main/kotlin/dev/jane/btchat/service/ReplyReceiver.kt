package dev.jane.btchat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.jane.btchat.App
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.MessageIds
import dev.jane.btchat.store.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Handles inline replies from the message notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(Notifications.EXTRA_PEER) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim().orEmpty()
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = App.get(context)
                val now = System.currentTimeMillis()
                if (text.isNotEmpty()) {
                    app.db.messages().insert(
                        MessageEntity(
                            id = MessageIds.next(), peer = peer, direction = Direction.OUT, kind = Kind.TEXT, text = text,
                            photoPath = null, thumbPath = null, photoWidth = null, photoHeight = null,
                            createdAt = now, status = Status.QUEUED, deliveredAt = null, readAt = null, readPending = false,
                        )
                    )
                }
                val unread = app.db.messages().latestInbound(peer, 50).filter { it.status == Status.RECEIVED }.map { it.id }
                if (unread.isNotEmpty()) {
                    app.db.messages().markInboundRead(peer, unread, now, app.settings.sendReadReceipts.first())
                }
                Notifications.cancelMessage(context)
                ChatService.start(context)
            } finally {
                result.finish()
            }
        }
    }
}
