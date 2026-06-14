package com.pistream.companion

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pistream.companion.ui.MainViewModel
import com.pistream.companion.ui.MainViewModelFactory
import com.pistream.companion.ui.PiCompanionScreen
import com.pistream.companion.ui.theme.PiStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PiStreamApplication).container

        // RuntimePermissions and Bluetooth-enable launchers are activity-scoped,
        // so they live here and dispatch results back into the ViewModel. The
        // assign-speaker flow needs BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+
        // and ACCESS_FINE_LOCATION on 26-30.
        var viewModelRef: MainViewModel? = null

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results: Map<String, Boolean> ->
            val granted = results.isNotEmpty() && results.values.all { it }
            val canAskAgain = results.keys.any { perm ->
                shouldShowRequestPermissionRationale(perm)
            } || granted
            viewModelRef?.onAssignPermissionResult(allGranted = granted, canAskAgain = canAskAgain)
        }

        val enableBluetoothLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result: ActivityResult ->
            viewModelRef?.onBluetoothEnableResult(result.resultCode == Activity.RESULT_OK)
        }

        setContent {
            PiStreamTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModelFactory(
                        repository = container.repository,
                        discoverer = container.discoverer,
                        bluetoothScanner = container.bluetoothScanner,
                        clientInstanceId = container.clientInstanceId
                    )
                )
                viewModelRef = viewModel
                PiCompanionScreen(
                    viewModel = viewModel,
                    onRequestBluetoothPermissions = { perms ->
                        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                    },
                    onRequestEnableBluetooth = {
                        enableBluetoothLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        )
                    },
                    onOpenAppSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                )
            }
        }
    }
}
