package com.retroremote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retroremote.core.model.RemoteDevice
import com.retroremote.discovery.mdns.AndroidTvDiscovery
import com.retroremote.protocol.AtvIdentity
import com.retroremote.protocol.AtvPairing
import com.retroremote.protocol.AtvRemote

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RetroRemoteApp() }
    }
}

@Composable
private fun RetroRemoteApp() {
    val context = LocalContext.current
    val discovery = remember(context) { AndroidTvDiscovery(context) }
    val devices = remember { mutableStateListOf<RemoteDevice>() }
    val isScanning = remember { mutableStateOf(false) }
    val status = remember { mutableStateOf("Ready to scan your local network") }
    val error = remember { mutableStateOf<String?>(null) }
    val selectedDevice = remember { mutableStateOf<RemoteDevice?>(null) }
    val prefs = remember(context) { context.getSharedPreferences("retro_remote", 0) }
    val identity = remember { AtvIdentity.load(prefs) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val pairing = remember { mutableStateOf<AtvPairing?>(null) }
    val pairingBusy = remember { mutableStateOf(false) }
    val pairingCode = remember { mutableStateOf("") }
    val remote = remember { mutableStateOf<AtvRemote?>(null) }
    val connected = remember { mutableStateOf(false) }

    DisposableEffect(discovery) {
        onDispose {
            discovery.stop()
            pairing.value?.close()
            remote.value?.close()
            isScanning.value = false
        }
    }

    fun selectDevice(device: RemoteDevice) {
        selectedDevice.value = device
        pairingCode.value = ""
        error.value = null
        val saved = prefs.getString("pin_" + device.host, null)
        if (saved != null) {
            pairingBusy.value = true
            val client = AtvRemote(device.host, identity, saved) { message ->
                mainHandler.post { status.value = message; connected.value = message == "Connected to Android TV"; pairingBusy.value = false }
            }
            remote.value = client
            Thread({ client.run() }, "RetroRemote-control").start()
        } else {
            pairingBusy.value = true
            Thread({
                try {
                    val session = AtvPairing(device.host, identity)
                    session.begin()
                    mainHandler.post {
                        pairing.value = session
                        pairingBusy.value = false
                        status.value = "Enter the pairing code shown on your TV"
                    }
                } catch (t: Throwable) {
                    mainHandler.post { pairingBusy.value = false; error.value = t.message ?: "Could not start pairing" }
                }
            }, "RetroRemote-pairing").start()
        }
    }

    fun startDiscovery() {
        devices.clear()
        error.value = null
        status.value = "Searching for Android TV devices…"
        isScanning.value = true
        discovery.start(
            onDevice = { device ->
                if (devices.none { it.id == device.id }) {
                    devices.add(device)
                    status.value = if (devices.size == 1) "Found 1 Android TV device" else "Found ${devices.size} Android TV devices"
                }
            },
            onError = { throwable ->
                error.value = throwable.message ?: "Unknown discovery error"
                status.value = "Discovery encountered an error"
            }
        )
    }

    fun stopDiscovery() {
        discovery.stop()
        isScanning.value = false
        status.value = if (devices.isEmpty()) "Scan stopped — no devices found" else "Scan stopped — ${devices.size} device(s) found"
    }

    selectedDevice.value?.let { device ->
        val savedPin = prefs.getString("pin_" + device.host, null)
        AlertDialog(
            onDismissRequest = { pairing.value?.close(); pairing.value = null; selectedDevice.value = null },
            title = { Text(if (pairingBusy.value) "Connecting…" else "Pair " + device.name) },
            text = {
                Column {
                    Text(device.host + ":6467 pairing / :6466 remote")
                    if (pairingBusy.value) {
                        androidx.compose.material3.CircularProgressIndicator()
                    } else if (savedPin == null) {
                        Text("Enter the six-character code displayed on your Mi Box.")
                        OutlinedTextField(value = pairingCode.value, onValueChange = {
                            pairingCode.value = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6)
                        }, label = { Text("TV pairing code") }, singleLine = true)
                    } else {
                        Text("Saved pairing found. Connecting to the remote service…")
                    }
                    error.value?.let { Text(it, color = Color(0xFFFF7777)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !pairingBusy.value && pairingCode.value.length == 6 && pairing.value != null,
                    onClick = {
                        val session = pairing.value ?: return@TextButton
                        pairingBusy.value = true
                        val code = pairingCode.value
                        Thread({
                            try {
                                session.submit(code)
                                val pin = session.pin
                                prefs.edit().putString("pin_" + device.host, pin).apply()
                                session.close()
                                mainHandler.post {
                                    pairing.value = null
                                    pairingBusy.value = false
                                    status.value = "Pairing successful — connecting…"
                                    val client = AtvRemote(device.host, identity, pin) { message ->
                                        mainHandler.post {
                                            status.value = message
                                            connected.value = message == "Connected to Android TV"
                                        }
                                    }
                                    remote.value = client
                                    Thread({ client.run() }, "RetroRemote-control").start()
                                    selectedDevice.value = null
                                }
                            } catch (t: Throwable) {
                                mainHandler.post { pairingBusy.value = false; error.value = t.message ?: "Pairing failed" }
                            }
                        }, "RetroRemote-pair-submit").start()
                    }) { Text("Pair & connect") }
                TextButton(onClick = { pairing.value?.close(); pairing.value = null; selectedDevice.value = null }) { Text("Cancel") }
            }
        )
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B0E)) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0E)).padding(24.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("RetroRemote", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Universal control for your TV devices",
                    color = Color(0xFFB8B8C0),
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { if (isScanning.value) stopDiscovery() else startDiscovery() }
                    ) {
                        Text(if (isScanning.value) "Stop scanning" else if (devices.isEmpty()) "Discover Android TV devices" else "Scan again")
                    }
                }

                Text(
                    status.value,
                    color = if (error.value == null) Color(0xFFB8B8C0) else Color(0xFFFF7777),
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )

                error.value?.let {
                    Text("Discovery error: $it", color = Color(0xFFFF7777), modifier = Modifier.padding(bottom = 8.dp))
                }

                if (devices.isEmpty()) {
                    Text(
                        "Make sure your phone and Android TV are on the same Wi-Fi network. Keep the TV awake while scanning.",
                        color = Color(0xFF8E8E98),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Text(
                        "AVAILABLE DEVICES",
                        color = Color(0xFFE11D48),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            DeviceCard(device, onClick = { selectDevice(device) })
                        }
                    }
                }

                if (connected.value) {
                    Text("REMOTE CONTROL", color = Color(0xFFE11D48), modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { remote.value?.press(19) }) { Text("▲") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { remote.value?.press(21) }) { Text("◀") }
                            Button(onClick = { remote.value?.press(23) }) { Text("OK") }
                            Button(onClick = { remote.value?.press(22) }) { Text("▶") }
                        }
                        Button(onClick = { remote.value?.press(20) }) { Text("▼") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { remote.value?.press(4) }) { Text("BACK") }
                            Button(onClick = { remote.value?.press(3) }) { Text("HOME") }
                            Button(onClick = { remote.value?.press(82) }) { Text("MENU") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { remote.value?.press(25) }) { Text("VOL −") }
                            Button(onClick = { remote.value?.press(164) }) { Text("MUTE") }
                            Button(onClick = { remote.value?.press(24) }) { Text("VOL +") }
                        }
                        TextButton(onClick = { remote.value?.close(); connected.value = false; status.value = "Disconnected" }) { Text("Disconnect") }
                    }
                } else {
                    Text(
                        "Pair once using the code shown on your TV. The trusted certificate is saved on this phone.",
                        color = Color(0xFF777780),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: RemoteDevice, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF19191F))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(device.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text("${device.host}:${device.port}", color = Color(0xFFB8B8C0), style = MaterialTheme.typography.bodyMedium)
            Text(device.serviceType, color = Color(0xFF85858F), style = MaterialTheme.typography.bodySmall)
        }
    }
}
