package com.retroremote.core.transport

import com.retroremote.core.model.RemoteDevice

interface RemoteTransport {
    suspend fun connect(device: RemoteDevice)
    suspend fun disconnect()
    suspend fun sendKey(keyCode: Int, pressed: Boolean)
    val isConnected: Boolean
}
