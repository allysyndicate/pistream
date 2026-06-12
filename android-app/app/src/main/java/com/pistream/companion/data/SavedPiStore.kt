package com.pistream.companion.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pistream.companion.domain.TrustedIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.savedPiDataStore by preferencesDataStore(name = "saved_pi")

class SavedPiStore(private val context: Context) {
    private object Keys {
        val Host = stringPreferencesKey("host")
        val ApiName = stringPreferencesKey("api_name")
        val ContractVersion = stringPreferencesKey("contract_version")
        val DeviceId = stringPreferencesKey("device_id")
        val ControllerInstanceId = stringPreferencesKey("controller_instance_id")
    }

    val savedPi: Flow<SavedPi?> = context.savedPiDataStore.data.map { prefs ->
        val host = prefs[Keys.Host] ?: return@map null
        val apiName = prefs[Keys.ApiName] ?: return@map SavedPi(host, null)
        val contractVersion = prefs[Keys.ContractVersion] ?: return@map SavedPi(host, null)
        val deviceId = prefs[Keys.DeviceId] ?: return@map SavedPi(host, null)
        val controllerInstanceId = prefs[Keys.ControllerInstanceId] ?: return@map SavedPi(host, null)
        SavedPi(
            host = host,
            identity = TrustedIdentity(
                apiName = apiName,
                contractVersion = contractVersion,
                deviceId = deviceId,
                controllerInstanceId = controllerInstanceId
            )
        )
    }

    suspend fun save(host: String, identity: TrustedIdentity) {
        context.savedPiDataStore.edit { prefs ->
            prefs[Keys.Host] = normalizePiHost(host)
            prefs[Keys.ApiName] = identity.apiName
            prefs[Keys.ContractVersion] = identity.contractVersion
            prefs[Keys.DeviceId] = identity.deviceId
            prefs[Keys.ControllerInstanceId] = identity.controllerInstanceId
        }
    }
}

data class SavedPi(
    val host: String,
    val identity: TrustedIdentity?
)
