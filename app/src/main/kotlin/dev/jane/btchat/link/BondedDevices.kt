package dev.jane.btchat.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context

data class BondedDevice(val address: String, val name: String)

/** Lists phones already paired in system settings. Requires BLUETOOTH_CONNECT; returns empty without it. */
object BondedDevices {
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<BondedDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty()
                .map { BondedDevice(it.address.uppercase(), it.name ?: it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
