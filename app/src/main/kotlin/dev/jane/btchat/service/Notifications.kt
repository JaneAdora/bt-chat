package dev.jane.btchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.jane.btchat.R
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.ui.MainActivity

object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_MESSAGES = "messages"
    const val ID_SERVICE = 1
    const val ID_MESSAGE = 2
    const val KEY_REPLY = "reply"
    const val EXTRA_PEER = "peer"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whether the other phone is in range"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages from the other phone"
            }
        )
    }

    fun service(context: Context, title: String, stayConnected: Boolean): Notification {
        val toggle = PendingIntent.getService(
            context, 2,
            Intent(context, ChatService::class.java).setAction(ChatService.ACTION_TOGGLE_STAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (stayConnected) "Stay connected is on" else "Stay connected is off")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (stayConnected) "Turn off" else "Turn on", toggle)
            .build()
    }

    fun message(context: Context, peerNick: String, peer: String, messages: List<MessageEntity>): Notification {
        val me = Person.Builder().setName("Me").build()
        val them = Person.Builder().setName(peerNick).setKey(peer).build()
        val style = NotificationCompat.MessagingStyle(me)
        for (m in messages) {
            val text = if (m.kind == Kind.PHOTO) "Photo" else (m.text ?: "")
            style.addMessage(NotificationCompat.MessagingStyle.Message(text, m.createdAt, them))
        }
        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        val replyPending = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_PEER, peer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(0, "Reply", replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(reply)
            .build()
    }

    fun cancelMessage(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_MESSAGE)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
