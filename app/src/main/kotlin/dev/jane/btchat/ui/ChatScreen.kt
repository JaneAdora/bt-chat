package dev.jane.btchat.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.jane.btchat.App

@Composable
fun ChatScreen(app: App, peer: String, onChangePeer: () -> Unit) {
    Text("Chat with $peer")
}
