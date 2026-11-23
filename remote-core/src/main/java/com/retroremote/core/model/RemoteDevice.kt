package com.retroremote.core.model

enum class RemotePlatform { ANDROID_TV, LG_WEBOS, ROKU, UNKNOWN }

data class RemoteDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val platform: RemotePlatform,
    val serviceType: String
)
