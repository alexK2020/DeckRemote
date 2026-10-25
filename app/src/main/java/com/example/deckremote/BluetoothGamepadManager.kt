package com.example.deckremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothGamepadManager(
    private val context: Context,
    private val onStateChanged: (Boolean, String) -> Unit
) {

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var isRegistered = false

    init {
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHidApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    isRegistered = false
                    onStateChanged(false, "Disconnected")
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            Log.d("DeckRemote", "App status changed: registered=$registered, pluggedDevice=$pluggedDevice")
            if (registered && pluggedDevice != null) {
                connectedDevice = pluggedDevice
                onStateChanged(true, "Connected to ${pluggedDevice.name ?: pluggedDevice.address}")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            connectedDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            val isConnected = state == BluetoothProfile.STATE_CONNECTED
            val name = device?.name ?: device?.address ?: "Unknown"
            onStateChanged(isConnected, if (isConnected) "Connected to $name" else "Disconnected")
            Log.d("DeckRemote", "Connection state changed: $state for $name")
        }
    }

    fun registerHidApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w("DeckRemote", "BLUETOOTH_CONNECT permission not granted yet, skipping HID registration")
                return
            }
        }
        val currentHid = hidDevice
        if (currentHid == null) {
            Log.w("DeckRemote", "hidDevice proxy is null, cannot register yet")
            return
        }

        val reportDescriptor = byteArrayOf(
            0x05.toByte(), 0x01.toByte(),        // Usage Page (Generic Desktop Ctrls)
            0x09.toByte(), 0x05.toByte(),        // Usage (Game Pad)
            0xA1.toByte(), 0x01.toByte(),        // Collection (Application)
            0x85.toByte(), 0x01.toByte(),        //   Report ID (1)
            // Hat Switch (D-Pad)
            0x05.toByte(), 0x01.toByte(),        //   Usage Page (Generic Desktop Ctrls)
            0x09.toByte(), 0x39.toByte(),        //   Usage (Hat switch)
            0x15.toByte(), 0x01.toByte(),        //   Logical Minimum (1)
            0x25.toByte(), 0x08.toByte(),        //   Logical Maximum (8)
            0x35.toByte(), 0x00.toByte(),        //   Physical Minimum (0)
            0x46.toByte(), 0x3b.toByte(), 0x01.toByte(), // Physical Maximum (315)
            0x65.toByte(), 0x14.toByte(),        //   Unit
            0x75.toByte(), 0x04.toByte(),        //   Report Size (4)
            0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
            0x81.toByte(), 0x02.toByte(),        //   Input (Data,Var,Abs)
            0x75.toByte(), 0x04.toByte(),        //   Report Size (4)
            0x95.toByte(), 0x01.toByte(),        //   Report Count (1)
            0x81.toByte(), 0x03.toByte(),        //   Input (Const,Var,Abs) (Padding)
            // Buttons (16 buttons)
            0x05.toByte(), 0x09.toByte(),        //   Usage Page (Button)
            0x19.toByte(), 0x01.toByte(),        //   Usage Minimum (Button 1)
            0x29.toByte(), 0x10.toByte(),        //   Usage Maximum (Button 16)
            0x15.toByte(), 0x00.toByte(),        //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),        //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(),        //   Report Size (1)
            0x95.toByte(), 0x10.toByte(),        //   Report Count (16)
            0x81.toByte(), 0x02.toByte(),        //   Input (Data,Var,Abs)
            0xC0.toByte()                        // End Collection
        )

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "DeckRemote Gamepad",
            "Steam Deck Gamepad Remote",
            "DeckRemote",
            0x40.toByte(), // Gamepad subclass
            reportDescriptor
        )

        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, 11250
        )

        try {
            val success = currentHid.registerApp(sdp, null, qos, executor, hidCallback)
            Log.d("DeckRemote", "registerApp called, result: $success")
        } catch (e: Exception) {
            Log.e("DeckRemote", "Failed to register HID app", e)
        }
    }

    fun sendButtonState(hat: Byte, b1: Int, b2: Int) {
        val dev = connectedDevice ?: return
        val report = byteArrayOf(hat, b1.toByte(), b2.toByte())
        hidDevice?.sendReport(dev, 1, report)
    }

    fun getPairedDevices(): Set<BluetoothDevice>? {
        return bluetoothAdapter?.bondedDevices
    }

    fun requestDiscoverable(activity: Activity) {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DeckRemote", "Failed to start discoverable intent", e)
        }
    }

    fun startDiscovery(): Boolean {
        return bluetoothManager?.adapter?.startDiscovery() ?: false
    }

    fun cancelDiscovery() {
        bluetoothManager?.adapter?.cancelDiscovery()
    }

    fun connect(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    fun disconnect(device: BluetoothDevice) {
        hidDevice?.disconnect(device)
    }

    fun unregister() {
        if (isRegistered) {
            hidDevice?.unregisterApp()
        }
    }
}
