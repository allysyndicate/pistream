package com.pistream.companion.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.pistream.companion.domain.DiscoveredPi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

private const val SERVICE_TYPE = "_pihouse-audio._tcp."

class PiNetworkDiscoverer(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discoveredPis(): Flow<List<DiscoveredPi>> = callbackFlow {
        val resolved = ConcurrentHashMap<String, DiscoveredPi>()

        fun publish() {
            trySend(resolved.values.sortedBy { it.label }.toList())
        }

        fun resolve(serviceInfo: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    { it.run() },
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                        override fun onServiceUpdated(updated: NsdServiceInfo) {
                            val pi = updated.toDiscoveredPi() ?: return
                            resolved[updated.serviceName] = pi
                            publish()
                        }
                        override fun onServiceLost() {
                            resolved.remove(serviceInfo.serviceName)
                            publish()
                        }
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val pi = resolvedInfo.toDiscoveredPi() ?: return
                        resolved[resolvedInfo.serviceName] = pi
                        publish()
                    }
                })
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("Start discovery failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                resolved.remove(service.serviceName)
                publish()
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        publish()

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }
}

private fun NsdServiceInfo.toDiscoveredPi(): DiscoveredPi? {
    val hostAddress = host?.hostAddress ?: return null
    val txt = attributes.orEmpty().mapValues { it.value?.toString(Charsets.UTF_8).orEmpty() }
    return DiscoveredPi(
        serviceName = serviceName,
        host = hostAddress,
        port = port.takeIf { it > 0 } ?: 8765,
        deviceId = txt["deviceId"],
        contractVersion = txt["contractVersion"],
        pairingOpen = txt["pairing"] == "open"
    )
}
