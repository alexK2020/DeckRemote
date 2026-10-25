package com.example.deckremote

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var bluetoothManager: BluetoothGamepadManager? = null
    private var connectionStatus by mutableStateOf("Disconnected")
    private var isConnected by mutableStateOf(false)
    private var pairedDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var discoveredDevices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var selectedDevice by mutableStateOf<BluetoothDevice?>(null)
    private var showTutorial by mutableStateOf(false)
    private var showPermissionDisclaimer by mutableStateOf(false)

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_FOUND == intent?.action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let { dev ->
                    if (!discoveredDevices.contains(dev) && !pairedDevices.contains(dev)) {
                        discoveredDevices = discoveredDevices + dev
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPairedDevicesAndAutoConnect()
        bluetoothManager?.registerHidApp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure system status bar and navigation bar are pitch black
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        val prefs = getSharedPreferences("deck_remote_prefs", MODE_PRIVATE)
        val hasSeenTutorial = prefs.getBoolean("has_seen_tutorial", false)
        showTutorial = !hasSeenTutorial

        val hasAllPermissions = checkHasPermissions()
        showPermissionDisclaimer = !hasAllPermissions

        bluetoothManager = BluetoothGamepadManager(this) { connected, _ ->
            isConnected = connected
            connectionStatus = if (connected) "Connected" else "Disconnected"
            if (connected) {
                GamepadForegroundService.start(this, "Connected to ${selectedDevice?.name ?: "Steam Deck"}")
            } else {
                GamepadForegroundService.stop(this)
            }
        }

        if (hasAllPermissions) {
            refreshPairedDevicesAndAutoConnect()
            bluetoothManager?.registerHidApp()
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    primary = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showPermissionDisclaimer) {
                        PermissionDisclaimerScreen(onAccept = {
                            showPermissionDisclaimer = false
                            requestPermissions()
                        })
                    } else if (showTutorial) {
                        TutorialScreen(onStart = {
                            prefs.edit().putBoolean("has_seen_tutorial", true).apply()
                            showTutorial = false
                        })
                    } else {
                        ControllerScreen(
                            connectionStatus = connectionStatus,
                            isConnected = isConnected,
                            pairedDevices = pairedDevices,
                            discoveredDevices = discoveredDevices,
                            selectedDevice = selectedDevice,
                            onSelectDevice = { dev ->
                                selectedDevice = dev
                                prefs.edit().putString("last_device_mac", dev.address).apply()
                            },
                            onConnect = { dev ->
                                dev?.let {
                                    selectedDevice = it
                                    prefs.edit().putString("last_device_mac", it.address).apply()
                                    bluetoothManager?.registerHidApp()
                                    bluetoothManager?.connect(it)
                                }
                            },
                            onDisconnect = { dev ->
                                dev?.let { bluetoothManager?.disconnect(it) }
                                GamepadForegroundService.stop(this)
                            },
                            onRefreshDevices = { refreshPairedDevicesAndAutoConnect() },
                            onMakeDiscoverable = { bluetoothManager?.requestDiscoverable(this) },
                            onScanDevices = {
                                discoveredDevices = emptyList()
                                bluetoothManager?.startDiscovery()
                            },
                            onButtonEvent = { hat, b1, b2 ->
                                bluetoothManager?.sendButtonState(hat, b1, b2)
                            },
                            onShowTutorialAgain = { showTutorial = true }
                        )
                    }
                }
            }
        }
    }

    private fun checkHasPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshPairedDevicesAndAutoConnect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            val list = bluetoothManager?.getPairedDevices()?.toList() ?: emptyList()
            pairedDevices = list

            val prefs = getSharedPreferences("deck_remote_prefs", MODE_PRIVATE)
            val lastMac = prefs.getString("last_device_mac", null)

            if (selectedDevice == null && list.isNotEmpty()) {
                val matched = list.firstOrNull { it.address == lastMac } ?: list.first()
                selectedDevice = matched
                if (!isConnected) {
                    bluetoothManager?.connect(matched)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
        bluetoothManager?.unregister()
    }
}

@Composable
fun PermissionDisclaimerScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Permission Disclosure",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "DeckRemote requires specific permissions to operate as a wireless Bluetooth controller for your Steam Deck. Below is a detailed breakdown of why each permission is required:",
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Start
            )
            Spacer(modifier = Modifier.height(20.dp))

            PermissionExplanationItem(
                title = "Bluetooth Permissions",
                subtitle = "BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE",
                description = "Required to register the Bluetooth HID Gamepad profile, discover nearby devices, and send controller input commands to your Steam Deck."
            )

            PermissionExplanationItem(
                title = "Location Permissions",
                subtitle = "ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION",
                description = "Required by the Android system for Bluetooth device discovery. DeckRemote does NOT track, collect, store, or share your physical location data."
            )

            PermissionExplanationItem(
                title = "Notification Permission",
                subtitle = "POST_NOTIFICATIONS",
                description = "Required on Android 13+ to show an active status notification so your gamepad remains connected when you switch apps or lock your screen."
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onAccept,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066CC))
        ) {
            Text("Okay", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PermissionExplanationItem(title: String, subtitle: String, description: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(text = subtitle, color = Color.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description, color = Color.LightGray, fontSize = 13.sp)
        }
    }
}

@Composable
fun TutorialScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Welcome to DeckRemote",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))

            TutorialStep(
                step = "1",
                title = "Bluetooth & Visibility",
                description = "Turn on Bluetooth on both your Steam Deck and your phone."
            )
            TutorialStep(
                step = "2",
                title = "Steam Deck: 'Show all devices'",
                description = "Important: In the Steam Deck Bluetooth menu, enable 'Show all devices' so your Steam Deck can discover your phone."
            )
            TutorialStep(
                step = "3",
                title = "Make Phone Visible",
                description = "Tap the menu icon (☰) in the app and tap 'Make Phone Visible' to allow your Steam Deck to discover and pair."
            )
            TutorialStep(
                step = "4",
                title = "Pair & Connect",
                description = "Pair the devices once in Bluetooth settings, then tap Connect in the app to use it as a gamepad!"
            )
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066CC))
        ) {
            Text("Got It & Get Started", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TutorialStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Blue),
            contentAlignment = Alignment.Center
        ) {
            Text(text = step, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, color = Color.LightGray, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(
    connectionStatus: String,
    isConnected: Boolean,
    pairedDevices: List<BluetoothDevice>,
    discoveredDevices: List<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    onSelectDevice: (BluetoothDevice) -> Unit,
    onConnect: (BluetoothDevice?) -> Unit,
    onDisconnect: (BluetoothDevice?) -> Unit,
    onRefreshDevices: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onScanDevices: () -> Unit,
    onButtonEvent: (Byte, Int, Int) -> Unit,
    onShowTutorialAgain: () -> Unit
) {
    var showDeviceDialog by remember { mutableStateOf(false) }

    var currentHat by remember { mutableStateOf<Byte>(0) }
    var buttonStateBits by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var qamJob by remember { mutableStateOf<Job?>(null) }

    fun updateButtonState(hat: Byte, newBits: Int) {
        currentHat = hat
        buttonStateBits = newBits
        val b1 = newBits and 0xFF
        val b2 = (newBits shr 8) and 0xFF
        onButtonEvent(hat, b1, b2)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedDevice?.name ?: "No Device Selected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = connectionStatus,
                        color = if (isConnected) Color.Green else Color.Red,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = { showDeviceDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Devices Menu",
                        tint = Color.White
                    )
                }
            }
        }

        // Weighted Spacer to push all interactive controller buttons down towards the thumb area
        Spacer(modifier = Modifier.weight(1f))

        // Center D-PAD & A Button UI
        Box(
            modifier = Modifier
                .size(290.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Larger D-Pad Up Arrow Button
            DPadArrowButton(
                arrowText = "▲",
                modifier = Modifier.align(Alignment.TopCenter)
            ) { pressed ->
                updateButtonState(if (pressed) 1 else 0, buttonStateBits)
            }

            // Larger D-Pad Down Arrow Button
            DPadArrowButton(
                arrowText = "▼",
                modifier = Modifier.align(Alignment.BottomCenter)
            ) { pressed ->
                updateButtonState(if (pressed) 5 else 0, buttonStateBits)
            }

            // Larger D-Pad Left Arrow Button
            DPadArrowButton(
                arrowText = "◀",
                modifier = Modifier.align(Alignment.CenterStart)
            ) { pressed ->
                updateButtonState(if (pressed) 7 else 0, buttonStateBits)
            }

            // Larger D-Pad Right Arrow Button
            DPadArrowButton(
                arrowText = "▶",
                modifier = Modifier.align(Alignment.CenterEnd)
            ) { pressed ->
                updateButtonState(if (pressed) 3 else 0, buttonStateBits)
            }

            // Center 'A' Button (Graphic Image)
            ImageActionButton(
                drawableRes = R.drawable.ic_btn_a,
                contentDescription = "A Button",
                size = 90.dp
            ) { pressed ->
                val newBits = if (pressed) buttonStateBits or (1 shl 0) else buttonStateBits and (1 shl 0).inv()
                updateButtonState(currentHat, newBits)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Middle Row: [ ... (Dot.png) ] [ B (B.png) ] [ STEAM (Home.png) ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Three Dots Button (...) -> Quick Access Menu (Atomic 150ms Steam + A chord sequence)
            ImageActionButton(
                drawableRes = R.drawable.ic_btn_dot,
                contentDescription = "Quick Access Menu",
                size = 76.dp
            ) { pressed ->
                if (pressed) {
                    qamJob?.cancel()
                    qamJob = scope.launch {
                        val guideBits = buttonStateBits or (1 shl 12)
                        updateButtonState(currentHat, guideBits)
                        delay(150)
                        val guideAndABits = guideBits or (1 shl 0)
                        updateButtonState(currentHat, guideAndABits)
                        delay(100)
                        updateButtonState(currentHat, guideBits)
                        delay(50)
                        val allClearBits = buttonStateBits and (1 shl 12).inv() and (1 shl 0).inv()
                        updateButtonState(currentHat, allClearBits)
                    }
                }
            }

            // Button B (B.png) -> Usage 2 (bit index 1 = BTN_EAST / B)
            ImageActionButton(
                drawableRes = R.drawable.ic_btn_b,
                contentDescription = "B Button",
                size = 76.dp
            ) { pressed ->
                val newBits = if (pressed) buttonStateBits or (1 shl 1) else buttonStateBits and (1 shl 1).inv()
                updateButtonState(currentHat, newBits)
            }

            // Steam Logo Button (Home.png) -> Usage 13 (bit index 12 = BTN_MODE / Steam / Home / Xbox)
            ImageActionButton(
                drawableRes = R.drawable.ic_btn_home,
                contentDescription = "Steam Button",
                size = 76.dp
            ) { pressed ->
                val newBits = if (pressed) buttonStateBits or (1 shl 12) else buttonStateBits and (1 shl 12).inv()
                updateButtonState(currentHat, newBits)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Row: [ Select (Select.png) ] [ Start (Start.png) ]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Select Button (Select.png) -> Usage 11 (bit index 10 = BTN_SELECT)
                ImageActionButton(
                    drawableRes = R.drawable.ic_btn_select,
                    contentDescription = "Select Button",
                    size = 68.dp
                ) { pressed ->
                    val newBits = if (pressed) buttonStateBits or (1 shl 10) else buttonStateBits and (1 shl 10).inv()
                    updateButtonState(currentHat, newBits)
                }

                // Start Button (Start.png) -> Usage 12 (bit index 11 = BTN_START)
                ImageActionButton(
                    drawableRes = R.drawable.ic_btn_start,
                    contentDescription = "Start Button",
                    size = 68.dp
                ) { pressed ->
                    val newBits = if (pressed) buttonStateBits or (1 shl 11) else buttonStateBits and (1 shl 11).inv()
                    updateButtonState(currentHat, newBits)
                }
            }
        }
    }

    // Device Selection Dialog
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("Bluetooth Devices & Connection") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    Button(
                        onClick = { onMakeDiscoverable() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0066CC))
                    ) {
                        Text("Make Phone Visible (Discoverable)")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { onScanDevices() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan for Steam Deck")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = {
                            showDeviceDialog = false
                            onShowTutorialAgain()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Show Setup Tutorial", color = Color.Cyan)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Paired Devices (Tap to Connect/Disconnect):", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (pairedDevices.isEmpty()) {
                            item { Text("No paired devices found", color = Color.Gray, fontSize = 12.sp) }
                        } else {
                            val sortedDevices = pairedDevices.sortedByDescending { it == selectedDevice && isConnected }
                            items(sortedDevices) { device ->
                                val isThisConnected = (device == selectedDevice && isConnected)
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isThisConnected) Color(0xFF003300) else Color(0xFF222222)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name ?: device.address,
                                                color = if (isThisConnected) Color.Green else Color.White,
                                                fontWeight = if (isThisConnected) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (isThisConnected) {
                                                Text("Connected", color = Color.Green, fontSize = 10.sp)
                                            }
                                        }
                                        if (isThisConnected) {
                                            Button(
                                                onClick = {
                                                    onDisconnect(device)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("Disconnect", fontSize = 10.sp)
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    onSelectDevice(device)
                                                    onConnect(device)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Text("Connect", fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Discovered Devices:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        }

                        if (discoveredDevices.isEmpty()) {
                            item { Text("No new devices found during scan", color = Color.Gray, fontSize = 12.sp) }
                        } else {
                            items(discoveredDevices) { device ->
                                TextButton(
                                    onClick = {
                                        onSelectDevice(device)
                                        onConnect(device)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(device.name ?: device.address, color = Color.Yellow)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun DPadArrowButton(
    arrowText: String,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = if (isPressed) 0.88f else 1.0f
                scaleY = if (isPressed) 0.88f else 1.0f
                alpha = if (isPressed) 0.7f else 1.0f
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressChanged(true)
                        try { awaitRelease() } finally {
                            isPressed = false
                            onPressChanged(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = arrowText,
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ImageActionButton(
    @DrawableRes drawableRes: Int,
    contentDescription: String,
    size: Dp = 80.dp,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = if (isPressed) 0.92f else 1.0f
                scaleY = if (isPressed) 0.92f else 1.0f
                alpha = if (isPressed) 0.75f else 1.0f
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPressChanged(true)
                        try { awaitRelease() } finally {
                            isPressed = false
                            onPressChanged(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}
