package dev.jane.btchat.service

import dev.jane.btchat.link.LinkState
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide link state so the UI can show it without binding to the service. */
object LinkStateHolder {
    val state = MutableStateFlow<LinkState>(LinkState.Off)
    val serviceRunning = MutableStateFlow(false)
}

/** What the UI is showing, so the service can suppress notifications for a visible chat. */
object ChatVisibility {
    val visiblePeer = MutableStateFlow<String?>(null)
    val appInForeground = MutableStateFlow(false)
}
