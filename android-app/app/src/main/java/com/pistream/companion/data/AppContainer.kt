package com.pistream.companion.data

import android.content.Context

class AppContainer(context: Context) {
    private val piApiClient = PiApiClient()
    private val savedPiStore = SavedPiStore(context.applicationContext)
    private val secureTokenStore = SecureTokenStore(context.applicationContext)
    private val clientIdentityStore = ClientIdentityStore(context.applicationContext)

    val discoverer = PiNetworkDiscoverer(context.applicationContext)

    val bluetoothScanner = PhoneBluetoothScanner(context.applicationContext)

    val repository = PiRepository(
        apiClient = piApiClient,
        savedPiStore = savedPiStore,
        tokenStore = secureTokenStore
    )

    val clientInstanceId: String = clientIdentityStore.clientInstanceId
}
