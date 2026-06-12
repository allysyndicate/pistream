package com.pistream.companion.data

import android.content.Context

class AppContainer(context: Context) {
    private val piApiClient = PiApiClient()
    private val savedPiStore = SavedPiStore(context.applicationContext)
    private val secureTokenStore = SecureTokenStore(context.applicationContext)

    val repository = PiRepository(
        apiClient = piApiClient,
        savedPiStore = savedPiStore,
        tokenStore = secureTokenStore
    )
}
