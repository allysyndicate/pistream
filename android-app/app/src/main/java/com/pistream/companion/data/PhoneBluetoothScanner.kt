package com.pistream.companion.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pistream.companion.domain.BluetoothDeviceModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Phone-side Bluetooth enumeration for the assign-speaker flow.
 *
 * Two sources are merged:
 *  - Bonded devices ([BluetoothAdapter.getBondedDevices]) seeded immediately.
 *  - Live classic-BT discovery ([BluetoothAdapter.startDiscovery]) for nearby
 *    speakers the user has not paired to the phone.
 *
 * Both are filtered to audio-class devices so the picker doesn't surface
 * laptops, phones, watches, or BLE beacons. The picked MAC is then handed to
 * the Pi to do the real BlueZ pair on its own radio.
 */
class PhoneBluetoothScanner(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    val isBluetoothSupported: Boolean
        get() = adapter != null

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    /**
     * Runtime permissions required to enumerate speakers on this API level.
     * On API 31+ we need BLUETOOTH_SCAN + BLUETOOTH_CONNECT; on 26-30 we need
     * ACCESS_FINE_LOCATION (the classic-BT discovery tax). Older legacy
     * BLUETOOTH/BLUETOOTH_ADMIN are install-time and not runtime-checked.
     */
    fun requiredRuntimePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingPermissions(): List<String> =
        requiredRuntimePermissions().filterNot {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun hasAllPermissions(): Boolean = missingPermissions().isEmpty()

    /**
     * Bonded (already-paired-to-this-phone) audio devices. May be empty if the
     * user has never paired a speaker to the phone — which is the common case
     * in our flow, since pairing happens on the Pi.
     */
    @SuppressLint("MissingPermission")
    fun bondedAudioSpeakers(): List<BluetoothDeviceModel> {
        val a = adapter ?: return emptyList()
        if (!hasAllPermissions()) return emptyList()
        return try {
            a.bondedDevices.orEmpty()
                .mapNotNull { it.toAudioSpeakerModel(pairedToPhone = true) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /**
     * Cold flow that registers a classic-BT discovery receiver, kicks off
     * [BluetoothAdapter.startDiscovery], and emits the cumulative
     * audio-class device list as results arrive. Cancellation or
     * [timeoutMillis] elapsing tears the receiver down and cancels discovery.
     */
    fun discoverNearbyAudioSpeakers(
        timeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MS
    ): Flow<List<BluetoothDeviceModel>> = callbackFlow {
        val a = adapter ?: run {
            close(IllegalStateException("Bluetooth is not supported on this device."))
            return@callbackFlow
        }
        if (!a.isEnabled) {
            close(IllegalStateException("Bluetooth is turned off."))
            return@callbackFlow
        }
        if (!hasAllPermissions()) {
            close(SecurityException("Bluetooth permissions required."))
            return@callbackFlow
        }

        val seen = LinkedHashMap<String, BluetoothDeviceModel>()
        bondedAudioSpeakers().forEach { seen[it.address] = it }
        trySend(seen.values.toList())

        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = extraDevice(intent)
                        val model = device?.toAudioSpeakerModel(
                            pairedToPhone = device.bondState == BluetoothDevice.BOND_BONDED
                        ) ?: return
                        val existing = seen[model.address]
                        val merged = existing?.copy(
                            name = model.name ?: existing.name,
                            paired = existing.paired || model.paired
                        ) ?: model
                        if (existing != merged) {
                            seen[model.address] = merged
                            trySend(seen.values.toList())
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        val started = try {
            a.startDiscovery()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            close(IllegalStateException("Bluetooth scan could not be started."))
            return@callbackFlow
        }

        val timeoutJob = launch {
            delay(timeoutMillis)
            close()
        }
        awaitClose {
            timeoutJob.cancel()
            runCatching { a.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toAudioSpeakerModel(
        pairedToPhone: Boolean
    ): BluetoothDeviceModel? {
        val addr = address ?: return null
        val major = runCatching { bluetoothClass?.majorDeviceClass }.getOrNull()
        // AUDIO_VIDEO covers speakers/headsets/car audio. UNCATEGORIZED gets
        // through because some BR/EDR speakers in pairing mode report it
        // until full SDP is read. Everything else (phones, computers, watches,
        // peripherals, imaging, toys) is filtered out.
        val accepted = major == null ||
            major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED
        if (!accepted) return null
        val displayName = runCatching { name }.getOrNull()
        return BluetoothDeviceModel(
            address = addr,
            name = displayName,
            paired = pairedToPhone,
            trusted = false,
            connected = false
        )
    }

    @Suppress("DEPRECATION")
    private fun extraDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MS: Long = 12_000L
    }
}
