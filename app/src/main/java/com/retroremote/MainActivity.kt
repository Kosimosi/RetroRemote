package com.retroremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RetroRemoteApp() }
    }
}

@Composable
private fun RetroRemoteApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0B0E)) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0E)).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("RetroRemote", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Universal control for your TV devices",
                    color = Color(0xFFB8B8C0),
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
                )
                Button(onClick = { }) { Text("Discover Android TV devices") }
                Text(
                    "Discovery UI and secure pairing are the next implementation steps.",
                    color = Color(0xFF8E8E98),
                    modifier = Modifier.padding(top = 18.dp)
                )
            }
        }
    }
}
