package com.pistream.companion.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pistream.companion.domain.ComponentRow
import com.pistream.companion.domain.DashboardModel

@Composable
fun PiCompanionScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("PiStream Companion", style = MaterialTheme.typography.headlineMedium)
            Text("Connection: ${state.connectionLabel}", style = MaterialTheme.typography.labelLarge)

            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::updateHost,
                label = { Text("Pi hostname or IP") },
                placeholder = { Text("audiopi.local") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = viewModel::updateToken,
                label = { Text("Bearer token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = viewModel::connect,
                enabled = !state.isLoading && state.token.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isLoading) "Working..." else "Ping Identity, Health, And Status")
            }

            state.message?.let { MessageCard(text = it) }
            state.operationMessage?.let { MessageCard(text = it) }

            state.dashboard?.let { dashboard ->
                DashboardSection(
                    dashboard = dashboard,
                    onReconnect = viewModel::reconnect,
                    onRunWatchdog = viewModel::runWatchdog,
                    onRestartService = viewModel::restartService,
                    onOpenSpotify = {
                        openSpotify(context)
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardSection(
    dashboard: DashboardModel,
    onReconnect: () -> Unit,
    onRunWatchdog: () -> Unit,
    onRestartService: () -> Unit,
    onOpenSpotify: () -> Unit
) {
    SectionCard(title = "Pi Identity") {
        Text("Host: ${dashboard.host}")
        Text("Device: ${dashboard.identity.deviceId}")
        Text("Controller: ${dashboard.identity.controllerInstanceId}")
        Text("Contract: ${dashboard.identity.contractVersion}")
    }

    SectionCard(title = "Health") {
        Text("State: ${dashboard.healthState}")
        Text("Phase: ${dashboard.phase ?: "unknown"}")
        Text("Observed boot: ${dashboard.observedBootId ?: "missing"}")
        Text("Observed at: ${dashboard.observedAt ?: "missing"}")
    }

    ComponentSection("Speakers", dashboard.speakers)
    ComponentSection("Sinks", dashboard.sinks)
    ComponentSection("Spotify Endpoints", dashboard.spotifyEndpoints)

    SectionCard(title = "Spotify Handoff") {
        Text("Account: ${dashboard.spotify.accountState}")
        Text("Active device: ${dashboard.spotify.activeDevice}")
        Text("Playback: ${dashboard.spotify.playbackState}")
        Text("Recommended: ${dashboard.spotify.recommendedAction}")
        OutlinedButton(onClick = onOpenSpotify, modifier = Modifier.fillMaxWidth()) {
            Text("Open Spotify")
        }
    }

    dashboard.watchdog?.let { ComponentSection("Watchdog", listOf(it)) }

    SectionCard(title = "Operations") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReconnect, enabled = dashboard.canRunOperations) {
                Text("Reconnect")
            }
            TextButton(onClick = onRunWatchdog, enabled = dashboard.canRunOperations) {
                Text("Run Watchdog")
            }
        }
        TextButton(onClick = onRestartService, enabled = dashboard.canRunOperations) {
            Text("Restart bt_watchdog")
        }
        dashboard.operations.forEach {
            Text("${it.type}: ${it.status} (${it.operationId})")
        }
    }
}

@Composable
private fun ComponentSection(title: String, rows: List<ComponentRow>) {
    SectionCard(title = title) {
        if (rows.isEmpty()) {
            Text("No Pi-reported rows.")
        } else {
            rows.forEach { row ->
                Text("${row.label}: ${row.state}")
                if (row.reasonCodes.isNotEmpty()) {
                    Text(row.reasonCodes.joinToString(prefix = "Reasons: "))
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    SectionCard(title = "Status") {
        Text(text)
    }
}

private fun openSpotify(context: android.content.Context) {
    val spotifyIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
    try {
        if (spotifyIntent != null) {
            context.startActivity(spotifyIntent)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/")))
        }
    } catch (exception: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/")))
    }
}
