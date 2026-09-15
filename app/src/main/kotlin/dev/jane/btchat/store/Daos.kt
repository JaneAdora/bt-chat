package dev.jane.btchat.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers WHERE address = :address")
    fun observe(address: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE address = :address")
    suspend fun get(address: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY createdAt ASC, id ASC")
    fun observe(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peer = :peer AND direction = 0 AND status = 0 ORDER BY createdAt ASC, id ASC")
    fun observeQueued(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peer = :peer AND readPending = 1 ORDER BY createdAt ASC, id ASC")
    fun observeReadPending(peer: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id AND peer = :peer")
    suspend fun get(id: Long, peer: String): MessageEntity?

    /** Returns -1 when a row with the same id and peer already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET status = 1 WHERE id = :id AND peer = :peer AND direction = 0 AND status = 0")
    suspend fun markSent(id: Long, peer: String)

    @Query("UPDATE messages SET status = 2, deliveredAt = :now WHERE id = :id AND peer = :peer AND direction = 0 AND status < 2")
    suspend fun markDelivered(id: Long, peer: String, now: Long)

    @Query("UPDATE messages SET status = 3, readAt = :now, deliveredAt = COALESCE(deliveredAt, :now) WHERE id = :id AND peer = :peer AND direction = 0 AND status < 3")
    suspend fun markReadByPeer(id: Long, peer: String, now: Long)

    @Query("UPDATE messages SET status = 3, readAt = :now, readPending = :pending WHERE peer = :peer AND id IN (:ids) AND direction = 1 AND status < 3")
    suspend fun markInboundRead(peer: String, ids: List<Long>, now: Long, pending: Boolean)

    @Query("UPDATE messages SET readPending = 0 WHERE id = :id AND peer = :peer")
    suspend fun clearReadPending(id: Long, peer: String)

    @Query("UPDATE messages SET status = 0 WHERE peer = :peer AND direction = 0 AND status = 1")
    suspend fun requeueSent(peer: String)

    @Query("SELECT * FROM messages WHERE peer = :peer AND direction = 1 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun latestInbound(peer: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE peer = :peer")
    suspend fun clear(peer: String)
}
