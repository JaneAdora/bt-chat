package dev.jane.btchat.service

import android.app.ForegroundServiceStartNotAllowedException
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jane.btchat.App
import kotlinx.coroutines.CoroutineExceptionHandler
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
        val handler = CoroutineExceptionHandler { _, e -> Log.w(TAG, "boot/bluetooth-on handling failed", e) }
        CoroutineScope(Dispatchers.IO + handler).launch {
            try {
                val settings = App.get(context).settings
                if (settings.stayConnected.first() && settings.activePeer.first() != null) {
                    try {
                        ChatService.start(context)
                    } catch (e: ForegroundServiceStartNotAllowedException) {
                        // ACTION_STATE_CHANGED is not on the background-FGS-start exemption
                        // list, so a backgrounded app can be refused here. There is nothing
                        // more to do; the user opening the app will start the service instead.
                        Log.w(TAG, "foreground service start not allowed", e)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
