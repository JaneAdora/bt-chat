package dev.jane.btchat.store

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private fun newSettings(name: String): Settings {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "ds-$name-${System.nanoTime()}")
        dir.mkdirs()
        val store = PreferenceDataStoreFactory.create { File(dir, "settings.preferences_pb") }
        return Settings(store)
    }

    @Test
    fun `defaults`() = runBlocking {
        val s = newSettings("defaults")
        assertNull(s.myNick.first())
        assertEquals(Settings.DEFAULT_COLOR, s.myColor.first())
        assertEquals(ThemeMode.SYSTEM, s.theme.first())
        assertEquals(true, s.stayConnected.first())
        assertEquals(true, s.sendReadReceipts.first())
        assertNull(s.activePeer.first())
    }

    @Test
    fun `setters persist`() = runBlocking {
        val s = newSettings("setters")
        s.setNick("Mom")
        s.setColor("#8A3B12")
        s.setTheme(ThemeMode.DARK)
        s.setStayConnected(false)
        s.setSendReadReceipts(false)
        s.setActivePeer("AA:BB:CC:DD:EE:FF")
        assertEquals("Mom", s.myNick.first())
        assertEquals("#8A3B12", s.myColor.first())
        assertEquals(ThemeMode.DARK, s.theme.first())
        assertEquals(false, s.stayConnected.first())
        assertEquals(false, s.sendReadReceipts.first())
        assertEquals("AA:BB:CC:DD:EE:FF", s.activePeer.first())
    }

    @Test
    fun `nodeId is generated once and is never zero`() = runBlocking {
        val s = newSettings("node")
        val first = s.nodeId()
        assertNotEquals(0L, first)
        assertEquals(first, s.nodeId())
        assertNotEquals(first, newSettings("node2").nodeId())
    }

    @Test
    fun `helloBody reflects nick and color`() = runBlocking {
        val s = newSettings("hello")
        s.setNick("Ash")
        s.setColor("#2E4A8F")
        val hello = s.helloBody()
        assertEquals(1, hello.proto)
        assertEquals("Ash", hello.nick)
        assertEquals("#2E4A8F", hello.color)
        assertEquals("btchat-android/0.1.0", hello.app)
    }
}
