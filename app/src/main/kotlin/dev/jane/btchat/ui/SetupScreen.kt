package dev.jane.btchat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jane.btchat.App
import dev.jane.btchat.link.BondedDevice
import dev.jane.btchat.link.BondedDevices
import dev.jane.btchat.service.ChatService
import dev.jane.btchat.store.PeerEntity
import dev.jane.btchat.store.Settings
import kotlinx.coroutines.launch

private fun hasNotificationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun isBatteryExempt(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@Composable
fun SetupScreen(app: App, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var bluetoothGranted by remember { mutableStateOf(ChatService.hasBluetoothPermission(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var devices by remember { mutableStateOf<List<BondedDevice>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bluetoothGranted = ChatService.hasBluetoothPermission(context)
        notificationsGranted = hasNotificationPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bluetoothGranted = ChatService.hasBluetoothPermission(context)
                notificationsGranted = hasNotificationPermission(context)
                batteryExempt = isBatteryExempt(context)
                if (bluetoothGranted) devices = BondedDevices.list(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(bluetoothGranted) { if (bluetoothGranted) devices = BondedDevices.list(context) }

    val savedNick by app.settings.myNick.collectAsStateWithLifecycle(null)
    val savedColor by app.settings.myColor.collectAsStateWithLifecycle(Settings.DEFAULT_COLOR)
    val savedPeer by app.settings.activePeer.collectAsStateWithLifecycle(null)

    var nick by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf(Settings.DEFAULT_COLOR) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(savedNick, savedColor, savedPeer) {
        if (!seeded && savedNick != null) {
            nick = savedNick ?: ""
            color = savedColor
            chosen = savedPeer
            seeded = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up BT Chat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Messages travel over Bluetooth only. Pair the two phones once in system settings, then pick the other phone here.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SetupCard(title = "Permissions") {
            Text(
                if (bluetoothGranted) "Nearby devices: allowed" else "Nearby devices lets the app talk to the paired phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (notificationsGranted) "Notifications: allowed" else "Notifications show new messages when the app is closed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!bluetoothGranted || !notificationsGranted) {
                Button(onClick = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                }) { Text("Allow") }
            }
        }

        SetupCard(title = "Background") {
            Text(
                if (batteryExempt) "Battery optimization is off for BT Chat, so it can stay connected."
                else "Samsung's battery manager stops background apps. Turn optimization off so messages arrive while the screen is off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!batteryExempt) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Turn off optimization") }
            }
        }

        SetupCard(title = "You") {
            OutlinedTextField(
                value = nick,
                onValueChange = { nick = it.take(24) },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Your color", style = MaterialTheme.typography.labelLarge)
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
        }

        SetupCard(title = "Other phone") {
            if (!bluetoothGranted) {
                Text("Allow Nearby devices first.", style = MaterialTheme.typography.bodyMedium)
            } else if (devices.isEmpty()) {
                Text("No paired phones yet. Pair them in Bluetooth settings, then come back.", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (device in devices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = device.address },
                    ) {
                        RadioButton(selected = chosen == device.address, onClick = { chosen = device.address })
                        Column {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            Text(device.address, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { context.startActivity(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Open Bluetooth settings")
            }
        }

        val ready = bluetoothGranted && nick.isNotBlank() && chosen != null
        Button(
            enabled = ready,
            onClick = {
                val peer = chosen ?: return@Button
                val deviceName = devices.firstOrNull { it.address == peer }?.name ?: peer
                scope.launch {
                    app.settings.setNick(nick.trim())
                    app.settings.setColor(color)
                    app.settings.setActivePeer(peer)
                    if (app.db.peers().get(peer) == null) {
                        app.db.peers().upsert(PeerEntity(peer, deviceName, "#3F3F3F", 0L))
                    }
                    ChatService.start(context)
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupCard(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
