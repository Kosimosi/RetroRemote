package com.retroremote.discovery.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.retroremote.core.model.RemoteDevice
import com.retroremote.core.model.RemotePlatform

/**
 * Discovers Android TV Remote v2 and legacy v1 endpoints on the local network.
 * This class only discovers endpoints; it does not pair or send remote commands.
 */
class AndroidTvDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start(onDevice: (RemoteDevice) -> Unit, onError: (Throwable) -> Unit = {}) {
        SERVICE_TYPES.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("androidtvremote")) return
                    try {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                onError(IllegalStateException("NSD resolve failed: $errorCode"))
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val resolvedType = info.serviceType.orEmpty()
                                onDevice(
                                    RemoteDevice(
                                        id = "${host}:${info.port}:${info.serviceName}",
                                        name = info.serviceName ?: "Android TV",
                                        host = host,
                                        port = info.port,
                                        platform = RemotePlatform.ANDROID_TV,
                                        serviceType = resolvedType
                                    )
                                )
                            }
                        })
                    } catch (t: Throwable) { onError(t) }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    onError(IllegalStateException("NSD discovery start failed ($serviceType): $errorCode"))
                    stopListener(this)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    onError(IllegalStateException("NSD discovery stop failed ($serviceType): $errorCode"))
                    stopListener(this)
                }
            }
            listeners += listener
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (t: Throwable) { onError(t) }
        }
    }

    fun stop() {
        listeners.toList().forEach(::stopListener)
        listeners.clear()
    }

    private fun stopListener(listener: NsdManager.DiscoveryListener) {
        try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { }
        listeners.remove(listener)
    }

    companion object {
        private val SERVICE_TYPES = listOf("_androidtvremote2._tcp.", "_androidtvremote._tcp.")
    }
}
