package com.music.bypass.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BudsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast action: $action")

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val addr = dev?.address?.uppercase() ?: ""
                val name = dev?.name?.lowercase() ?: ""
                if (addr == "B0:38:E2:DF:F4:8C" || name.contains("t500") || (name.contains("realme") && name.contains("buds"))) {
                    Log.i(TAG, "realme Buds connected. Starting BudsService...")
                    BudsService.start(context)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val addr = dev?.address?.uppercase() ?: ""
                val name = dev?.name?.lowercase() ?: ""
                if (addr == "B0:38:E2:DF:F4:8C" || name.contains("t500") || (name.contains("realme") && name.contains("buds"))) {
                    Log.i(TAG, "realme Buds disconnected.")
                    BudsService.instance?.disconnectSocket()
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                BudsService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "BudsReceiver"
    }
}

