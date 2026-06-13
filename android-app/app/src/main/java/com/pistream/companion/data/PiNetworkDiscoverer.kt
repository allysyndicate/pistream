package com.pistream.companion.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.pistream.companion.domain.DiscoveredPi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val SERVICE_TYPE = "_pihouse-audio._tcp."
// Smooth over mDNS TXT-record restarts: a Pi that re-publishes its service often
// fires `onServiceLost` followed by `onServiceFound` within a few hundred ms. If we
// publish the empty list during that gap, the Add-Pi screen blinks the row off and
// back on. Hold removals for ~1s and cancel if the same service reappears.
private const val LOST_DEBOUNCE_MILLIS = 1_000L

class PiNetworkDiscoverer(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discoveredPis(): Flow<List<DiscoveredPi>> = callbackFlow {
        val resolved = ConcurrentHashMap<String, DiscoveredPi>()
        val pendingRemoval = ConcurrentHashMap<String, Job>()
        val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun publish() {
            trySend(resolved.values.sortedBy { it.label }.toList())
        }

        fun cancelPendingRemoval(name: String) {
            pendingRemoval.remove(name)?.cancel()
        }

        fun scheduleRemoval(name: String) {
            cancelPendingRemoval(name)
            pendingRemoval[name] = debounceScope.launch {
                delay(LOST_DEBOUNCE_MILLIS)
                if (pendingRemoval.remove(name) != null) {
                    resolved.remove(name)
                    publish()
                }
            }
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
                            cancelPendingRemoval(updated.serviceName)
                            resolved[updated.serviceName] = pi
                            publish()
                        }
                        override fun onServiceLost() {
                            scheduleRemoval(serviceInfo.serviceName)
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
                        cancelPendingRemoval(resolvedInfo.serviceName)
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
                cancelPendingRemoval(service.serviceName)
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                scheduleRemoval(service.serviceName)
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        publish()

        awaitClose {
            debounceScope.cancel()
            pendingRemoval.clear()
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
