package com.pistream.companion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pistream.companion.domain.ComponentRow
import com.pistream.companion.domain.DashboardModel
import com.pistream.companion.domain.DiscoveredPi
import com.pistream.companion.domain.ReasonCodes
import com.pistream.companion.domain.SpeakerLiveState
import com.pistream.companion.domain.SpeakerSystemRow
import com.pistream.companion.domain.SpotifySummary
import com.pistream.companion.domain.SystemReadiness
import com.pistream.companion.domain.TrustedIdentity
import com.pistream.companion.domain.liveState
import com.pistream.companion.domain.plainEnglishStatus
import com.pistream.companion.domain.systemReadiness

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiCompanionScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speakers") },
                actions = {
                    if (state.stage == HomeStage.Connected || state.stage == HomeStage.ConnectionFailed) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    enabled = state.stage == HomeStage.Connected && !state.isBusy,
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.refreshStatus()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Forget this Pi") },
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.forgetPi()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        HomeBody(
            padding = padding,
            state = state,
            context = context,
            onConnect = viewModel::startConnectFlow,
            onCancelConnect = viewModel::cancelConnectFlow,
            onChooseDiscovered = viewModel::chooseDiscoveredPi,
            onUpdateManualHost = viewModel::updateManualHostInput,
            onSubmitManualHost = viewModel::submitManualHost,
            onUpdateManualToken = viewModel::updateManualTokenInput,
            onSubmitManualToken = viewModel::submitManualToken,
            onRetry = viewModel::retrySavedConnection,
            onForget = viewModel::forgetPi,
            onSetZoneOn = viewModel::setZoneOn,
            onSetAllZonesOn = viewModel::setAllZones,
            onReconnectSpeaker = viewModel::reconnectSpeaker
        )
    }
}

@Composable
private fun HomeBody(
    padding: PaddingValues,
    state: HomeUiState,
    context: Context,
    onConnect: () -> Unit,
    onCancelConnect: () -> Unit,
    onChooseDiscovered: (DiscoveredPi) -> Unit,
    onUpdateManualHost: (String) -> Unit,
    onSubmitManualHost: () -> Unit,
    onUpdateManualToken: (String) -> Unit,
    onSubmitManualToken: () -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    onSetZoneOn: (String, Boolean) -> Unit,
    onSetAllZonesOn: (Boolean) -> Unit,
    onReconnectSpeaker: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (state.stage) {
            HomeStage.Connecting -> ConnectingPanel()
            HomeStage.Empty -> EmptyPanel(onConnect = onConnect)
            HomeStage.ConnectionFailed -> ConnectionFailedPanel(
                host = state.savedHost,
                message = state.statusMessage,
                onRetry = onRetry,
                onForget = onForget
            )
            HomeStage.Discovering -> DiscoveryPanel(
                discoveredPis = state.discoveredPis,
                manualHostInput = state.manualHostInput,
                manualTokenInput = state.manualTokenInput,
                pairing = state.pairing,
                statusMessage = state.statusMessage,
                onChooseDiscovered = onChooseDiscovered,
                onUpdateManualHost = onUpdateManualHost,
                onSubmitManualHost = onSubmitManualHost,
                onUpdateManualToken = onUpdateManualToken,
                onSubmitManualToken = onSubmitManualToken,
                onCancel = onCancelConnect
            )
            HomeStage.Connected -> ConnectedPanel(
                dashboard = state.dashboard,
                isBusy = state.isBusy,
                statusMessage = state.statusMessage,
                onSetZoneOn = onSetZoneOn,
                onSetAllZonesOn = onSetAllZonesOn,
                onReconnectSpeaker = onReconnectSpeaker,
                onOpenSpotify = { openSpotify(context) }
            )
        }
    }
}

@Composable
private fun ConnectingPanel() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text("Connecting to your Pi...", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EmptyPanel(onConnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("No speakers yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pair a Raspberry Pi running PiHouse Audio to start streaming. " +
                    "The Pi handles the Bluetooth pairing — this app is the remote.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text("Connect a speaker")
            }
        }
    }
}

@Composable
private fun ConnectionFailedPanel(
    host: String?,
    message: String?,
    onRetry: () -> Unit,
    onForget: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Can't reach your Pi", style = MaterialTheme.typography.titleMedium)
            Text(
                message ?: "The saved Pi at ${host ?: "(unknown)"} did not respond.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Try again") }
                OutlinedButton(onClick = onForget) { Text("Forget Pi") }
            }
        }
    }
}

@Composable
private fun DiscoveryPanel(
    discoveredPis: List<DiscoveredPi>,
    manualHostInput: String,
    manualTokenInput: String,
    pairing: PairingUiState?,
    statusMessage: String?,
    onChooseDiscovered: (DiscoveredPi) -> Unit,
    onUpdateManualHost: (String) -> Unit,
    onSubmitManualHost: () -> Unit,
    onUpdateManualToken: (String) -> Unit,
    onSubmitManualToken: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Find your Pi", style = MaterialTheme.typography.titleLarge)
            if (pairing == null) {
                Text(
                    "Looking for PiHouse Audio on the local network. Make sure the Pi is powered on and on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (discoveredPis.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Searching...")
                    }
                } else {
                    discoveredPis.forEach { pi ->
                        DiscoveredPiRow(pi = pi, onClick = { onChooseDiscovered(pi) })
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Don't see your Pi?",
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    value = manualHostInput,
                    onValueChange = onUpdateManualHost,
                    label = { Text("Pi IP address or hostname") },
                    placeholder = { Text("e.g. 192.168.1.42") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onSubmitManualHost,
                    enabled = manualHostInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add by address")
                }
            } else {
                PairingContent(
                    pairing = pairing,
                    manualTokenInput = manualTokenInput,
                    onUpdateManualToken = onUpdateManualToken,
                    onSubmitManualToken = onSubmitManualToken
                )
            }

            statusMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun DiscoveredPiRow(pi: DiscoveredPi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(pi.label, style = MaterialTheme.typography.titleMedium)
            Text(pi.authority, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!pi.pairingOpen) {
                Text(
                    "Pairing closed — you may need to open the pairing window on the Pi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PairingContent(
    pairing: PairingUiState,
    manualTokenInput: String,
    onUpdateManualToken: (String) -> Unit,
    onSubmitManualToken: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pairing with ${pairing.host}", style = MaterialTheme.typography.titleMedium)

        if (pairing.busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Asking the Pi for a pairing token...")
            }
        } else if (pairing.requiresManualToken) {
            pairing.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = manualTokenInput,
                onValueChange = onUpdateManualToken,
                label = { Text("Pairing code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onSubmitManualToken,
                enabled = manualTokenInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pair")
            }
        } else {
            pairing.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ConnectedPanel(
    dashboard: DashboardModel?,
    isBusy: Boolean,
    statusMessage: String?,
    onSetZoneOn: (String, Boolean) -> Unit,
    onSetAllZonesOn: (Boolean) -> Unit,
    onReconnectSpeaker: (String) -> Unit,
    onOpenSpotify: () -> Unit
) {
    if (dashboard == null) {
        ConnectingPanel()
        return
    }

    if (dashboard.isDemoMode) {
        DemoModeBanner()
    }

    if (dashboard.healthReasons.isNotEmpty()) {
        HealthIssuesBanner(reasonCodes = dashboard.healthReasons)
    }

    SpeakersList(
        dashboard = dashboard,
        isBusy = isBusy,
        onSetZoneOn = onSetZoneOn,
        onReconnectSpeaker = onReconnectSpeaker
    )

    WholeHouseRow(
        dashboard = dashboard,
        isBusy = isBusy,
        onSetAllZonesOn = onSetAllZonesOn,
        onOpenSpotify = onOpenSpotify
    )

    UnavailableControlsHint(dashboard = dashboard)

    statusMessage?.let { MessageCard(it) }
}

@Composable
private fun SpeakersList(
    dashboard: DashboardModel,
    isBusy: Boolean,
    onSetZoneOn: (String, Boolean) -> Unit,
    onReconnectSpeaker: (String) -> Unit
) {
    val canSetSystems = dashboard.canRunOperation("set_speaker_systems")
    val canReconnect = dashboard.canRunOperation("reconnect")

    dashboard.speakerSystems.forEach { system ->
        val speaker = dashboard.speakers.firstOrNull { matchesSystem(it.id, system.id) }
        SpeakerCard(
            system = system,
            speaker = speaker,
            isDemoMode = dashboard.isDemoMode,
            isBusy = isBusy,
            canToggle = canSetSystems,
            canReconnect = canReconnect,
            onSetOn = { on -> onSetZoneOn(system.id, on) },
            onReconnect = speaker?.let { { onReconnectSpeaker(it.id) } }
        )
    }

    if (dashboard.speakerSystems.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("No speakers reported", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The Pi did not return any speaker systems in this snapshot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpeakerCard(
    system: SpeakerSystemRow,
    speaker: ComponentRow?,
    isDemoMode: Boolean,
    isBusy: Boolean,
    canToggle: Boolean,
    canReconnect: Boolean,
    onSetOn: (Boolean) -> Unit,
    onReconnect: (() -> Unit)?
) {
    val readiness = system.systemReadiness()
    val speakerLive = speaker?.liveState() ?: SpeakerLiveState.UNKNOWN
    val statusLabel = system.plainEnglishStatus()
    val suffix = if (isDemoMode) " (demo)" else ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusDot(readiness = readiness, isDemoMode = isDemoMode)
                Column(modifier = Modifier.weight(1f)) {
                    Text(system.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$statusLabel$suffix",
                        style = MaterialTheme.typography.bodyMedium,
                        color = readinessColor(readiness, isDemoMode)
                    )
                }
                Switch(
                    checked = system.enabled,
                    enabled = !isBusy && canToggle,
                    onCheckedChange = onSetOn
                )
            }

            speaker?.let { row ->
                val tag = when (speakerLive) {
                    SpeakerLiveState.CONNECTED -> if (isDemoMode) "Bluetooth: simulated link" else "Bluetooth: linked"
                    SpeakerLiveState.DISCONNECTED -> "Bluetooth: not linked"
                    SpeakerLiveState.UNASSIGNED -> "Bluetooth: no speaker assigned"
                    SpeakerLiveState.UNKNOWN -> "Bluetooth: status unknown"
                }
                Text(
                    tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReasonList(row.reasonCodes)
                if (onReconnect != null && speakerLive != SpeakerLiveState.UNASSIGNED) {
                    OutlinedButton(
                        onClick = onReconnect,
                        enabled = !isBusy && canReconnect,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reconnect on the Pi")
                    }
                }
            }

            if (speaker == null) {
                Text(
                    "No Bluetooth speaker assigned to this zone yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ReasonList(system.reasonCodes)
        }
    }
}

@Composable
private fun WholeHouseRow(
    dashboard: DashboardModel,
    isBusy: Boolean,
    onSetAllZonesOn: (Boolean) -> Unit,
    onOpenSpotify: () -> Unit
) {
    val canSetSystems = dashboard.canRunOperation("set_speaker_systems")
    val allOn = dashboard.speakerSystems.isNotEmpty() &&
        dashboard.speakerSystems.all { it.enabled }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Whole house", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allOn) "All zones on" else "Pick which zones to play",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = allOn,
                    enabled = !isBusy && canSetSystems && dashboard.speakerSystems.isNotEmpty(),
                    onCheckedChange = onSetAllZonesOn
                )
            }
            Button(onClick = onOpenSpotify, modifier = Modifier.fillMaxWidth()) {
                Text("Play on Spotify")
            }
            Text(
                "Spotify Connect targets the enabled zones above. The Pi owns the Bluetooth link.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun UnavailableControlsHint(dashboard: DashboardModel) {
    val reasons = buildList {
        if (!dashboard.canRunOperations) {
            add("The Pi did not return a boot id and observation time, so controls are paused until status refreshes.")
        }
        if (dashboard.canRunOperations && dashboard.supportedOperations.isEmpty()) {
            add("The Pi did not advertise any supported operations on this snapshot.")
        }
        if (dashboard.canRunOperations && !dashboard.canRunOperation("set_speaker_systems")) {
            add("This Pi does not advertise the set_speaker_systems operation, so zone toggles are disabled.")
        }
        if (dashboard.canRunOperations && !dashboard.canRunOperation("reconnect")) {
            add("This Pi does not advertise the reconnect operation, so per-speaker reconnect is disabled.")
        }
    }
    if (reasons.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Why some controls are off", style = MaterialTheme.typography.titleSmall)
            reasons.forEach { reason -> Text("• $reason", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun StatusDot(readiness: SystemReadiness, isDemoMode: Boolean) {
    val color = readinessColor(readiness, isDemoMode)
    Surface(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape),
        color = color
    ) { Spacer(Modifier.size(12.dp)) }
}

private fun matchesSystem(speakerId: String, systemId: String): Boolean {
    if (speakerId == systemId) return true
    if (speakerId.startsWith("$systemId-")) return true
    if (speakerId.endsWith("-$systemId")) return true
    return false
}

@Composable
private fun readinessColor(readiness: SystemReadiness, isDemoMode: Boolean): Color {
    if (isDemoMode) return MaterialTheme.colorScheme.onSurfaceVariant
    return when (readiness) {
        SystemReadiness.READY -> MaterialTheme.colorScheme.primary
        SystemReadiness.NOT_READY -> MaterialTheme.colorScheme.error
        SystemReadiness.UNASSIGNED -> MaterialTheme.colorScheme.onSurfaceVariant
        SystemReadiness.UNREPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
        SystemReadiness.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun DemoModeBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "The Pi is reporting stub data. No real hardware is connected, so any 'on' state below is simulated.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun HealthIssuesBanner(reasonCodes: List<String>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Pi reports ${reasonCodes.size} issue(s)",
                style = MaterialTheme.typography.titleMedium
            )
            ReasonCodes.toPlainEnglishList(reasonCodes).forEach { sentence ->
                Text("• $sentence", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ReasonList(reasonCodes: List<String>) {
    if (reasonCodes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReasonCodes.toPlainEnglishList(reasonCodes).forEach { sentence ->
            Text(
                "• $sentence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Last update", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun openSpotify(context: Context) {
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

@Preview(showBackground = true)
@Composable
private fun PreviewConnectedHealthy() {
    PreviewWrapper {
        ConnectedPanel(
            dashboard = sampleDashboard(),
            isBusy = false,
            statusMessage = null,
            onSetZoneOn = { _, _ -> },
            onSetAllZonesOn = {},
            onReconnectSpeaker = {},
            onOpenSpotify = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewConnectedDemo() {
    PreviewWrapper {
        ConnectedPanel(
            dashboard = sampleDashboard().copy(audioOutputAdapterMode = "stub"),
            isBusy = false,
            statusMessage = null,
            onSetZoneOn = { _, _ -> },
            onSetAllZonesOn = {},
            onReconnectSpeaker = {},
            onOpenSpotify = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewConnectedDegraded() {
    PreviewWrapper {
        ConnectedPanel(
            dashboard = sampleDashboard().copy(
                healthReasons = listOf("outdoor_speaker_disconnected", "watchdog_inactive"),
                speakers = listOf(
                    ComponentRow("indoor", "Indoor speaker", "healthy", emptyList(), "Connected"),
                    ComponentRow("outdoor", "Outdoor speaker", "degraded", listOf("speaker_disconnected"), "Disconnected")
                ),
                speakerSystems = listOf(
                    SpeakerSystemRow("indoor", "Indoor", "alsa-indoor", true, true, "ready", emptyList(), true),
                    SpeakerSystemRow(
                        "outdoor",
                        "Outdoor",
                        "alsa-outdoor",
                        true,
                        false,
                        "not_ready",
                        listOf("speaker_disconnected"),
                        true
                    )
                )
            ),
            isBusy = false,
            statusMessage = null,
            onSetZoneOn = { _, _ -> },
            onSetAllZonesOn = {},
            onReconnectSpeaker = {},
            onOpenSpotify = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewEmpty() {
    PreviewWrapper {
        EmptyPanel(onConnect = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewConnectionFailed() {
    PreviewWrapper {
        ConnectionFailedPanel(
            host = "audiopi.local",
            message = "Could not reach audiopi.local on this network.",
            onRetry = {},
            onForget = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDiscoverySearching() {
    PreviewWrapper {
        DiscoveryPanel(
            discoveredPis = emptyList(),
            manualHostInput = "",
            manualTokenInput = "",
            pairing = null,
            statusMessage = null,
            onChooseDiscovered = {},
            onUpdateManualHost = {},
            onSubmitManualHost = {},
            onUpdateManualToken = {},
            onSubmitManualToken = {},
            onCancel = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDiscoveryFound() {
    PreviewWrapper {
        DiscoveryPanel(
            discoveredPis = listOf(
                DiscoveredPi(
                    serviceName = "PiHouse Audio (kitchen-pi)",
                    host = "192.168.1.42",
                    port = 8765,
                    deviceId = "kitchen-pi",
                    contractVersion = "2026-06-phase3",
                    pairingOpen = true
                )
            ),
            manualHostInput = "",
            manualTokenInput = "",
            pairing = null,
            statusMessage = null,
            onChooseDiscovered = {},
            onUpdateManualHost = {},
            onSubmitManualHost = {},
            onUpdateManualToken = {},
            onSubmitManualToken = {},
            onCancel = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPairingFallback() {
    PreviewWrapper {
        DiscoveryPanel(
            discoveredPis = emptyList(),
            manualHostInput = "192.168.1.42",
            manualTokenInput = "",
            pairing = PairingUiState(
                host = "192.168.1.42",
                busy = false,
                requiresManualToken = true,
                note = "This Pi does not advertise automatic pairing. Paste the pairing code from the Pi setup screen."
            ),
            statusMessage = null,
            onChooseDiscovered = {},
            onUpdateManualHost = {},
            onSubmitManualHost = {},
            onUpdateManualToken = {},
            onSubmitManualToken = {},
            onCancel = {}
        )
    }
}

@Composable
private fun PreviewWrapper(content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) { content() }
    }
}

private fun sampleDashboard(): DashboardModel {
    return DashboardModel(
        host = "audiopi.local",
        identity = TrustedIdentity(
            apiName = "pihouse-audio-api",
            contractVersion = "2026-06-phase3",
            deviceId = "preview-pi",
            controllerInstanceId = "preview-controller"
        ),
        healthState = "healthy",
        phase = "phase3",
        healthReasons = emptyList(),
        observedBootId = "preview-boot",
        observedAt = "2026-06-12T18:00:00Z",
        speakers = listOf(
            ComponentRow("indoor", "Indoor speaker", "healthy", emptyList(), "Connected"),
            ComponentRow("outdoor", "Outdoor speaker", "healthy", emptyList(), "Connected")
        ),
        speakerSystems = listOf(
            SpeakerSystemRow("indoor", "Indoor", "alsa-indoor", true, true, "ready", emptyList(), true),
            SpeakerSystemRow("outdoor", "Outdoor", "alsa-outdoor", false, false, "ready", emptyList(), true)
        ),
        sinks = emptyList(),
        spotifyEndpoints = emptyList(),
        routes = emptyList(),
        selectedRouteId = null,
        activeRouteId = null,
        enabledSystemIds = listOf("indoor"),
        activeSystemIds = listOf("indoor"),
        audioOutputLastChangedAt = null,
        audioOutputAdapterMode = "real",
        hasSpeakerSystemStatus = true,
        hasAudioOutputStatus = true,
        hasSpeakerConnectionStatus = true,
        spotify = SpotifySummary(
            accountState = "ready",
            activeDevice = "pi",
            playbackState = "playing",
            recommendedAction = "none",
            routeReadiness = emptyList()
        ),
        watchdog = null,
        operations = emptyList(),
        supportedOperations = listOf("set_speaker_systems", "reconnect")
    )
}
