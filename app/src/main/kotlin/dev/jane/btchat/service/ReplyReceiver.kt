package dev.jane.btchat.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dev.jane.btchat.App
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.MessageIds
import dev.jane.btchat.store.Status
import kotlinx.coroutines.CoroutineExceptionHandler
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
        val handler = CoroutineExceptionHandler { _, e -> Log.w(TAG, "reply handling failed", e) }
        CoroutineScope(Dispatchers.IO + handler).launch {
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
                try {
                    ChatService.start(context)
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "foreground service start not allowed", e)
                }
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReplyReceiver"
    }
}
