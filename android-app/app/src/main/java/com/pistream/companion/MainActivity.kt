package com.pistream.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pistream.companion.ui.MainViewModel
import com.pistream.companion.ui.MainViewModelFactory
import com.pistream.companion.ui.PiCompanionScreen
import com.pistream.companion.ui.theme.PiStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PiStreamApplication).container
        setContent {
            PiStreamTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModelFactory(container.repository)
                )
                PiCompanionScreen(viewModel = viewModel)
            }
        }
    }
}
