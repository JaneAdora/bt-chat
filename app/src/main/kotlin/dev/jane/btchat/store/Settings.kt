package dev.jane.btchat.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.jane.btchat.protocol.Bodies
import dev.jane.btchat.protocol.HelloBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(private val store: DataStore<Preferences>) {
    private object Keys {
        val NICK = stringPreferencesKey("my_nick")
        val COLOR = stringPreferencesKey("my_color")
        val THEME = stringPreferencesKey("theme")
        val STAY = booleanPreferencesKey("stay_connected")
        val READ_RECEIPTS = booleanPreferencesKey("send_read_receipts")
        val ACTIVE_PEER = stringPreferencesKey("active_peer")
        val NODE_ID = longPreferencesKey("node_id")
    }

    val myNick: Flow<String?> = store.data.map { it[Keys.NICK] }
    val myColor: Flow<String> = store.data.map { it[Keys.COLOR] ?: DEFAULT_COLOR }
    val theme: Flow<ThemeMode> = store.data.map { p -> p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
    val stayConnected: Flow<Boolean> = store.data.map { it[Keys.STAY] ?: true }
    val sendReadReceipts: Flow<Boolean> = store.data.map { it[Keys.READ_RECEIPTS] ?: true }
    val activePeer: Flow<String?> = store.data.map { it[Keys.ACTIVE_PEER] }

    suspend fun setNick(nick: String) = store.edit { it[Keys.NICK] = nick }
    suspend fun setColor(color: String) = store.edit { it[Keys.COLOR] = color }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }
    suspend fun setStayConnected(on: Boolean) = store.edit { it[Keys.STAY] = on }
    suspend fun setSendReadReceipts(on: Boolean) = store.edit { it[Keys.READ_RECEIPTS] = on }
    suspend fun setActivePeer(address: String?) = store.edit { p ->
        if (address == null) p.remove(Keys.ACTIVE_PEER) else p[Keys.ACTIVE_PEER] = address
    }

    /** A random identity for this install, used by the link tie-break. Never zero. */
    suspend fun nodeId(): Long {
        store.data.first()[Keys.NODE_ID]?.let { return it }
        var fresh: Long
        do { fresh = SecureRandom().nextLong() } while (fresh == 0L)
        store.edit { p -> if (p[Keys.NODE_ID] == null) p[Keys.NODE_ID] = fresh }
        return store.data.first()[Keys.NODE_ID]!!
    }

    suspend fun helloBody(): HelloBody {
        val p = store.data.first()
        return HelloBody(
            proto = Bodies.PROTO,
            nick = p[Keys.NICK] ?: "Me",
            color = p[Keys.COLOR] ?: DEFAULT_COLOR,
            app = APP_TAG,
        )
    }

    companion object {
        const val DEFAULT_COLOR = "#1F5F3F"
        const val APP_TAG = "btchat-android/0.1.0"
        fun forContext(context: Context): Settings = Settings(context.applicationContext.settingsDataStore)
    }
}
