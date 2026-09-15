package dev.jane.btchat.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.jane.btchat.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the service after boot, and when Bluetooth turns on while the service is not running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bluetoothOn = intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && !bluetoothOn) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = App.get(context).settings
                if (settings.stayConnected.first() && settings.activePeer.first() != null) {
                    ChatService.start(context)
                }
            } finally {
                result.finish()
            }
        }
    }
}
