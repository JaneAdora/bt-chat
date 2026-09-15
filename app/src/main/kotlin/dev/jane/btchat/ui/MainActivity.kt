package dev.jane.btchat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.jane.btchat.App
import dev.jane.btchat.service.ChatService
import dev.jane.btchat.service.ChatVisibility
import dev.jane.btchat.store.ThemeMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = App.get(this)
        setContent {
            val theme by app.settings.theme.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            BtChatTheme(theme) { Root(app) }
        }
    }

    override fun onStart() {
        super.onStart()
        ChatVisibility.appInForeground.value = true
        lifecycleScope.launch {
            if (App.get(this@MainActivity).settings.activePeer.first() != null) ChatService.start(this@MainActivity)
        }
    }

    override fun onStop() {
        ChatVisibility.appInForeground.value = false
        lifecycleScope.launch {
            if (!App.get(this@MainActivity).settings.stayConnected.first()) ChatService.stop(this@MainActivity)
        }
        super.onStop()
    }
}

private data class SetupInfo(val nick: String?, val peer: String?)

@Composable
private fun Root(app: App) {
    val context = LocalContext.current
    val info by remember {
        combine(app.settings.myNick, app.settings.activePeer) { nick, peer -> SetupInfo(nick, peer) }
    }.collectAsStateWithLifecycle(initialValue = null)
    var forceSetup by rememberSaveable { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val hasPermission = remember(permissionTick) { ChatService.hasBluetoothPermission(context) }

    val current = info ?: return
    val peer = current.peer
    if (forceSetup || current.nick == null || peer == null || !hasPermission) {
        SetupScreen(app, onDone = { forceSetup = false; permissionTick++ })
    } else {
        ChatScreen(app, peer, onChangePeer = { forceSetup = true })
    }
}
