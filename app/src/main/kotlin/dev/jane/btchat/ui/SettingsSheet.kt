package dev.jane.btchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jane.btchat.App
import dev.jane.btchat.service.ChatService
import dev.jane.btchat.store.Settings
import dev.jane.btchat.store.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(app: App, peer: String, onDismiss: () -> Unit, onChangePeer: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by app.settings.theme.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val stayConnected by app.settings.stayConnected.collectAsStateWithLifecycle(true)
    val readReceipts by app.settings.sendReadReceipts.collectAsStateWithLifecycle(true)
    val savedNick by app.settings.myNick.collectAsStateWithLifecycle(null)
    val savedColor by app.settings.myColor.collectAsStateWithLifecycle(Settings.DEFAULT_COLOR)

    var nick by rememberSaveable(savedNick) { mutableStateOf(savedNick ?: "") }
    var color by rememberSaveable(savedColor) { mutableStateOf(savedColor) }
    var confirmClear by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Theme", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { scope.launch { app.settings.setTheme(mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            SettingRow("Stay connected", "Keep the link alive when the app is closed") {
                Switch(
                    checked = stayConnected,
                    onCheckedChange = { on ->
                        scope.launch {
                            app.settings.setStayConnected(on)
                            if (on) ChatService.start(context)
                        }
                    },
                )
            }

            SettingRow("Send read receipts", "Let the other phone see when you read a message") {
                Switch(
                    checked = readReceipts,
                    onCheckedChange = { on -> scope.launch { app.settings.setSendReadReceipts(on) } },
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it.take(24) },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (hex in AccentPalette) {
                    val selected = hex == color
                    Spacer(
                        modifier = Modifier
                            .size(32.dp)
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .background(parseHex(hex), CircleShape)
                            .clickable { color = hex },
                    )
                }
            }
            Button(
                enabled = nick.isNotBlank() && (nick.trim() != savedNick || color != savedColor),
                onClick = {
                    scope.launch {
                        app.settings.setNick(nick.trim())
                        app.settings.setColor(color)
                    }
                },
            ) { Text("Save name and color") }
            Text(
                "The other phone sees the new name the next time you connect.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            OutlinedButton(onClick = onChangePeer, modifier = Modifier.fillMaxWidth()) { Text("Change the other phone") }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear history") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Deletes every message with this phone. Photos already saved to the gallery stay.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.db.messages().clear(peer)
                        app.photoStore.deleteForPeer(peer)
                        confirmClear = false
                        onDismiss()
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}
