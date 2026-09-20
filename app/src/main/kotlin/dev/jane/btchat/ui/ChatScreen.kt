package dev.jane.btchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.jane.btchat.App
import dev.jane.btchat.link.BondedDevices
import dev.jane.btchat.link.LinkState
import dev.jane.btchat.service.ChatVisibility
import dev.jane.btchat.service.Notifications
import dev.jane.btchat.store.Direction
import dev.jane.btchat.store.Kind
import dev.jane.btchat.store.MessageEntity
import dev.jane.btchat.store.Status
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun isBatteryExempt(context: android.content.Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(app: App, peer: String, onChangePeer: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$peer", factory = ChatViewModel.factory(app, peer))
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peerInfo by vm.peerInfo.collectAsStateWithLifecycle()
    val linkState by vm.linkState.collectAsStateWithLifecycle()
    val serviceRunning by vm.serviceRunning.collectAsStateWithLifecycle()
    val myColor by vm.myColor.collectAsStateWithLifecycle()
    val stayConnected by vm.stayConnected.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var viewerPath by rememberSaveable { mutableStateOf<String?>(null) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    val peerNick = peerInfo?.nick ?: "the other phone"
    val peerColor = parseHex(peerInfo?.color ?: "#3F3F3F")

    DisposableEffect(peer) {
        ChatVisibility.visiblePeer.value = peer
        Notifications.cancelMessage(context)
        onDispose { if (ChatVisibility.visiblePeer.value == peer) ChatVisibility.visiblePeer.value = null }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryExempt = isBatteryExempt(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Spec section 7: if the saved phone was unpaired in system settings, fall back to setup.
    LaunchedEffect(peer) {
        if (BondedDevices.list(context).none { it.address.equals(peer, ignoreCase = true) }) {
            Toast.makeText(context, "That phone is no longer paired in Bluetooth settings. Pick it again.", Toast.LENGTH_LONG).show()
            onChangePeer()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is ChatViewModel.Event.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val listState = rememberLazyListState()
    val newestFirst = remember(messages) { messages.asReversed() }

    LaunchedEffect(listState, messages) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } to (listState.firstVisibleItemIndex == 0) }
            .distinctUntilChanged()
            .collect { (keys, atBottom) ->
                if (!atBottom) return@collect
                val visible = keys.toSet()
                vm.markRead(
                    messages.filter { it.direction == Direction.IN && it.status == Status.RECEIVED && visible.contains(it.id) }.map { it.id }
                )
            }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex == 0) listState.animateScrollToItem(0)
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendPhoto(uri)
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.sendPhoto(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(peerNick) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                onSend = vm::sendText,
                onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCamera = {
                    val uri = CameraFiles.newUri(context)
                    cameraUri = uri
                    camera.launch(uri)
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            StatusStrip(
                state = linkState,
                serviceRunning = serviceRunning,
                nick = peerNick,
                stayConnected = stayConnected,
                onStayConnected = vm::setStayConnected,
                onOpenBluetooth = { context.startActivity(Intent(SystemSettings.ACTION_BLUETOOTH_SETTINGS)) },
                batteryExempt = batteryExempt,
            )
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(newestFirst, key = { _, m -> m.id }) { index, message ->
                    val older = newestFirst.getOrNull(index + 1)
                    Column {
                        if (older == null || !sameDay(older.createdAt, message.createdAt)) DayHeader(message.createdAt)
                        MessageBubble(
                            message = message,
                            myColor = parseHex(myColor),
                            peerColor = peerColor,
                            onPhotoClick = { viewerPath = message.photoPath },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            app = app,
            peer = peer,
            onDismiss = { showSettings = false },
            onChangePeer = { showSettings = false; onChangePeer() },
        )
    }
    viewerPath?.let { path -> PhotoViewer(app = app, path = path, onDismiss = { viewerPath = null }) }
}

@Composable
private fun StatusStrip(
    state: LinkState,
    serviceRunning: Boolean,
    nick: String,
    stayConnected: Boolean,
    onStayConnected: (Boolean) -> Unit,
    onOpenBluetooth: () -> Unit,
    batteryExempt: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val (label, dot) = when {
        state is LinkState.Connected -> "Connected to $nick" to colors.primary
        state is LinkState.Searching -> "Looking for $nick..." to colors.onSurfaceVariant
        !serviceRunning && !stayConnected -> "Stay connected is off" to colors.outline
        !serviceRunning -> "Starting..." to colors.onSurfaceVariant
        else -> "Bluetooth is off" to colors.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dot, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (serviceRunning && state is LinkState.Off) {
                TextButton(onClick = onOpenBluetooth) { Text("Turn on") }
            }
            if (!stayConnected && state !is LinkState.Connected) {
                Switch(checked = false, onCheckedChange = { onStayConnected(true) })
            }
        }
        if (!batteryExempt) {
            Text(
                "Allow unrestricted battery in Settings to stay connected.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit, onPickPhoto: () -> Unit, onCamera: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onPickPhoto) { Text("Photo") }
            TextButton(onClick = onCamera) { Text("Camera") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onSend(text)
                    text = ""
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: MessageEntity, myColor: Color, peerColor: Color, onPhotoClick: () -> Unit) {
    val context = LocalContext.current
    val mine = message.direction == Direction.OUT
    val accent = if (mine) myColor else peerColor
    val dark = LocalIsDark.current
    val fill = accent.copy(alpha = if (dark) 0.28f else 0.14f)
    val edge = accent.copy(alpha = 0.55f)
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(fill)
                .border(1.dp, edge, shape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val text = message.text ?: return@combinedClickable
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cat Chat message", text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                )
                .padding(10.dp),
        ) {
            if (message.kind == Kind.PHOTO && message.thumbPath != null) {
                val w = message.photoWidth ?: 4
                val h = message.photoHeight ?: 3
                val height = (220f * h / w).coerceIn(80f, 320f)
                AsyncImage(
                    model = File(message.thumbPath),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(220.dp)
                        .height(height.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPhotoClick),
                )
            } else {
                Text(linkify(message.text ?: "", MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeOf(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (mine) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusMark(message.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.status == Status.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(epochMs: Long) {
    Text(
        dayLabel(epochMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun statusMark(status: Int): String = when (status) {
    Status.QUEUED -> "waiting"
    Status.SENT -> "sent"
    Status.DELIVERED -> "delivered"
    Status.READ -> "read"
    else -> ""
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

private fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

private fun sameDay(a: Long, b: Long): Boolean = localDate(a) == localDate(b)

private fun timeOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormat)

private fun dayLabel(epochMs: Long): String {
    val date = localDate(epochMs)
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dayFormat)
    }
}
