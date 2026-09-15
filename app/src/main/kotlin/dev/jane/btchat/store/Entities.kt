package dev.jane.btchat.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Direction {
    const val OUT = 0
    const val IN = 1
}

object Kind {
    const val TEXT = 0
    const val PHOTO = 1
}

/** Outbound: QUEUED -> SENT -> DELIVERED -> READ. Inbound: RECEIVED -> READ. */
object Status {
    const val QUEUED = 0
    const val SENT = 1
    const val RECEIVED = 1
    const val DELIVERED = 2
    const val READ = 3
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val address: String,
    val nick: String,
    val color: String,
    val lastSeen: Long,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["id", "peer"],
    indices = [Index("peer", "createdAt")],
)
data class MessageEntity(
    val id: Long,
    val peer: String,
    val direction: Int,
    val kind: Int,
    val text: String?,
    val photoPath: String?,
    val thumbPath: String?,
    val photoWidth: Int?,
    val photoHeight: Int?,
    val createdAt: Long,
    val status: Int,
    val deliveredAt: Long?,
    val readAt: Long?,
    val readPending: Boolean,
)
