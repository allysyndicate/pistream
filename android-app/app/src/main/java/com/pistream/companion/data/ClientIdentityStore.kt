package com.pistream.companion.data

import android.content.Context
import java.util.UUID

class ClientIdentityStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pi_client_identity", Context.MODE_PRIVATE)

    val clientInstanceId: String
        get() {
            val existing = prefs.getString(KEY_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_ID, generated).apply()
            return generated
        }

    companion object {
        private const val KEY_ID = "client_instance_id"
    }
}
