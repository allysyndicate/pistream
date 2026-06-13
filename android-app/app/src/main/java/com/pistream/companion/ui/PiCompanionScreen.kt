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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pistream.companion.domain.BluetoothDeviceModel
import com.pistream.companion.domain.ComponentRow
import com.pistream.companion.domain.DashboardModel
import com.pistream.companion.domain.ReasonCodes
import com.pistream.companion.domain.SpeakerLiveState
import com.pistream.companion.domain.SpeakerSystemRow
import com.pistream.companion.domain.SpotifySummary
import com.pistream.companion.domain.SystemReadiness
import com.pistream.companion.domain.TrustedIdentity
import com.pistream.companion.domain.liveState
import com.pistream.companion.domain.plainEnglishStatus
import com.pistream.companion.domain.systemReadiness

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
                    isLoading = state.isLoading,
                    isScanning = state.isScanning,
                    bluetoothDevices = state.bluetoothDevices,
                    zoneNameInput = state.zoneNameInput,
                    onZoneNameInputChange = viewModel::updateZoneNameInput,
                    onScanBluetooth = viewModel::scanBluetoothDevices,
                    onPairSpeaker = viewModel::pairSpeaker,
                    onAssignSpeaker = viewModel::assignSpeaker,
                    onRefreshStatus = viewModel::refreshStatus,
                    onReconnect = viewModel::reconnect,
                    onRunWatchdog = viewModel::runWatchdog,
                    onRestartService = viewModel::restartService,
                    onSetSpeakerSystemEnabled = viewModel::setSpeakerSystemEnabled,
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
    isLoading: Boolean,
    onRefreshStatus: () -> Unit,
    onReconnect: (String) -> Unit,
    onRunWatchdog: () -> Unit,
    onRestartService: () -> Unit,
    onSetSpeakerSystemEnabled: (String, Boolean) -> Unit,
    onOpenSpotify: () -> Unit,
    isScanning: Boolean = false,
    bluetoothDevices: List<BluetoothDeviceModel> = emptyList(),
    zoneNameInput: String = "",
    onZoneNameInputChange: (String) -> Unit = {},
    onScanBluetooth: () -> Unit = {},
    onPairSpeaker: (String) -> Unit = {},
    onAssignSpeaker: (String, String) -> Unit = { _, _ -> }
) {
    if (dashboard.isDemoMode) {
        DemoModeBanner()
    }

    if (dashboard.healthReasons.isNotEmpty()) {
        HealthIssuesBanner(reasonCodes = dashboard.healthReasons)
    }

    SectionCard(title = "Pi Identity") {
        Text("Host: ${dashboard.host}")
        Text("Device: ${dashboard.identity.deviceId}")
        Text("Controller: ${dashboard.identity.controllerInstanceId}")
        Text("Contract: ${dashboard.identity.contractVersion}")
        Text(
            "Adapter: ${
                when (dashboard.audioOutputAdapterMode) {
                    "real" -> "real hardware"
                    "stub" -> "demo (no hardware)"
                    null -> "not reported"
                    else -> dashboard.audioOutputAdapterMode
                }
            }"
        )
    }

    SectionCard(title = "Pi / Service Health") {
        Text("State: ${dashboard.healthState}")
        Text("Phase: ${dashboard.phase ?: "unknown"}")
        Text("Observed boot: ${dashboard.observedBootId ?: "missing"}")
        Text("Observed at: ${dashboard.observedAt ?: "missing"}")
        Text("Speaker systems: ${if (dashboard.hasSpeakerSystemStatus) "reported" else "not reported"}")
        Text("Audio output: ${if (dashboard.hasAudioOutputStatus) "reported" else "not reported"}")
        Text("Speaker connection: ${if (dashboard.hasSpeakerConnectionStatus) "reported" else "not reported"}")
    }

    SpeakerControlSection(
        dashboard = dashboard,
        isLoading = isLoading,
        onReconnect = onReconnect
    )
    BluetoothSetupSection(
        dashboard = dashboard,
        isLoading = isLoading,
        isScanning = isScanning,
        devices = bluetoothDevices,
        zoneNameInput = zoneNameInput,
        onZoneNameInputChange = onZoneNameInputChange,
        onScanBluetooth = onScanBluetooth,
        onPairSpeaker = onPairSpeaker,
        onAssignSpeaker = onAssignSpeaker
    )
    SpeakerSystemsSection(
        dashboard = dashboard,
        isLoading = isLoading,
        onSetSpeakerSystemEnabled = onSetSpeakerSystemEnabled
    )
    ComponentSection("Sinks", dashboard.sinks)
    ComponentSection("Spotify Endpoints", dashboard.spotifyEndpoints)
    ComponentSection("Routes", dashboard.routes)
    ComponentSection("Route Readiness", dashboard.spotify.routeReadiness)

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
        TextButton(
            onClick = onRefreshStatus,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh Status")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onRunWatchdog,
                enabled = !isLoading && dashboard.canRunOperation("run_watchdog")
            ) {
                Text("Run Watchdog")
            }
        }
        TextButton(
            onClick = onRestartService,
            enabled = !isLoading && dashboard.canRunOperation("restart_service")
        ) {
            Text("Restart bt_watchdog")
        }
        if (!dashboard.canRunOperations) {
            Text("Controls unavailable until status includes boot id and observation time.")
        }
        if (dashboard.supportedOperations.isEmpty()) {
            Text("No supported operations reported by the Pi.")
        } else {
            Text("Supported: ${dashboard.supportedOperations.joinToString()}")
        }
        dashboard.operations.forEach {
            Text("${it.type}: ${it.status} (${it.operationId})")
        }
    }
}

@Composable
private fun SpeakerSystemsSection(
    dashboard: DashboardModel,
    isLoading: Boolean,
    onSetSpeakerSystemEnabled: (String, Boolean) -> Unit
) {
    val canSetSystems = dashboard.canRunOperation("set_speaker_systems")
    val canUseLegacyRoutes = dashboard.canRunOperation("select_route")

    SectionCard(title = "Speaker Systems") {
        if (!dashboard.hasSpeakerSystemStatus) {
            Text("Pi did not report any speaker-system status in this snapshot.")
        }
        if (!dashboard.hasAudioOutputStatus) {
            Text("Pi did not report which speaker systems are enabled or active.")
        }
        Text(
            "Enabled: " + dashboard.enabledSystemIds.ifEmpty { listOf("all off") }
                .joinToString { it.replaceFirstChar { c -> c.uppercase() } }
        )
        Text(
            "Active: " + dashboard.activeSystemIds.ifEmpty { listOf("none") }
                .joinToString { it.replaceFirstChar { c -> c.uppercase() } }
        )
        dashboard.audioOutputLastChangedAt?.let { Text("Last changed: $it") }

        dashboard.speakerSystems.forEach { system ->
            val nextEnabledIds = if (system.enabled) {
                dashboard.enabledSystemIds.filterNot { it == system.id }
            } else {
                (dashboard.enabledSystemIds + system.id).distinct()
            }
            val toggleSupported = canSetSystems || (canUseLegacyRoutes && nextEnabledIds.isLegacyRouteSet())
            val readiness = system.systemReadiness()
            val statusLabel = system.plainEnglishStatus()
            val statusSuffix = if (dashboard.isDemoMode) " (demo)" else ""
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(system.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "$statusLabel$statusSuffix",
                        color = when (readiness) {
                            SystemReadiness.READY ->
                                if (dashboard.isDemoMode) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                            SystemReadiness.NOT_READY -> MaterialTheme.colorScheme.error
                            SystemReadiness.UNASSIGNED,
                            SystemReadiness.UNREPORTED,
                            SystemReadiness.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (system.enabled) "Enabled" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReasonList(system.reasonCodes)
                }
                Switch(
                    checked = system.enabled,
                    onCheckedChange = { enabled -> onSetSpeakerSystemEnabled(system.id, enabled) },
                    enabled = !isLoading && dashboard.canRunOperations && toggleSupported
                )
            }
        }

        if (!canSetSystems && canUseLegacyRoutes) {
            Text("Using legacy route mapping. All-off needs the set_speaker_systems operation.")
        } else if (!canSetSystems) {
            Text("Speaker system toggles are unavailable on this Pi status snapshot.")
        }
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
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Demo mode", style = MaterialTheme.typography.titleMedium)
            Text(
                "The Pi is reporting stub data. No real hardware is connected, " +
                    "so any \"connected\" or \"playing\" state on this screen is simulated.",
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
            modifier = Modifier.padding(12.dp),
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
private fun BluetoothSetupSection(
    dashboard: DashboardModel,
    isLoading: Boolean,
    isScanning: Boolean,
    devices: List<BluetoothDeviceModel>,
    zoneNameInput: String,
    onZoneNameInputChange: (String) -> Unit,
    onScanBluetooth: () -> Unit,
    onPairSpeaker: (String) -> Unit,
    onAssignSpeaker: (String, String) -> Unit
) {
    val canPair = dashboard.canRunOperation("pair_speaker")
    val canAssign = dashboard.canRunOperation("assign_speaker")

    SectionCard(title = "Bluetooth Speakers") {
        Text("Scan from the Pi, pair a speaker, then assign it to a zone. Put new speakers in pairing mode before scanning.")
        Button(
            onClick = onScanBluetooth,
            enabled = !isLoading && !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Scanning (about 8s)..." else "Scan For Speakers")
        }

        if (!canPair && !canAssign) {
            Text("This Pi does not report pair/assign support. Update the Pi API to use Bluetooth setup.")
        }

        if (devices.isEmpty()) {
            Text("No scan results yet.")
        }
        devices.forEach { device ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(device.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOf(
                        device.address,
                        if (device.paired) "paired" else "not paired",
                        if (device.connected) "connected" else "not connected"
                    ).joinToString(" | ")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!device.paired) {
                        TextButton(
                            onClick = { onPairSpeaker(device.address) },
                            enabled = !isLoading && !isScanning && canPair
                        ) {
                            Text("Pair")
                        }
                    }
                    dashboard.speakerSystems.forEach { system ->
                        TextButton(
                            onClick = { onAssignSpeaker(system.id, device.address) },
                            enabled = !isLoading && !isScanning && canAssign && device.paired
                        ) {
                            Text("Set As ${system.label}")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = zoneNameInput,
            onValueChange = onZoneNameInputChange,
            label = { Text("Custom zone name (optional, used on assign)") },
            placeholder = { Text("Indoor Downstairs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpeakerControlSection(
    dashboard: DashboardModel,
    isLoading: Boolean,
    onReconnect: (String) -> Unit
) {
    SectionCard(title = "Speakers") {
        if (!dashboard.hasSpeakerConnectionStatus) {
            Text("Pi did not report speaker connection state.")
            return@SectionCard
        }
        if (dashboard.speakers.isEmpty()) {
            Text("No speakers reported.")
            return@SectionCard
        }
        dashboard.speakers.forEach { speaker ->
            val liveState = speaker.liveState()
            val statusLabel = speaker.plainEnglishStatus()
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(speaker.label, style = MaterialTheme.typography.titleSmall)
                val suffix = if (dashboard.isDemoMode) " (demo)" else ""
                Text(
                    "Status: $statusLabel$suffix",
                    color = when (liveState) {
                        SpeakerLiveState.CONNECTED ->
                            if (dashboard.isDemoMode) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        SpeakerLiveState.DISCONNECTED -> MaterialTheme.colorScheme.error
                        SpeakerLiveState.UNASSIGNED -> MaterialTheme.colorScheme.onSurfaceVariant
                        SpeakerLiveState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                ReasonList(speaker.reasonCodes)
                OutlinedButton(
                    onClick = { onReconnect(speaker.id) },
                    enabled = !isLoading
                        && dashboard.canRunOperation("reconnect")
                        && liveState != SpeakerLiveState.UNASSIGNED,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reconnect ${speaker.label}")
                }
            }
        }
    }
}

@Composable
private fun ComponentSection(title: String, rows: List<ComponentRow>) {
    SectionCard(title = title) {
        if (rows.isEmpty()) {
            Text("Pi did not report any rows.")
        } else {
            rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Status: ${row.state}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (row.state) {
                            "healthy", "ready", "active" -> MaterialTheme.colorScheme.primary
                            "degraded", "failed", "not_ready" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    row.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    ReasonList(row.reasonCodes)
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

private fun List<String>.isLegacyRouteSet(): Boolean {
    return toSet() in setOf(
        setOf("indoor"),
        setOf("outdoor"),
        setOf("indoor", "outdoor")
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedFreshNoAssignmentPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard().copy(
            speakers = emptyList(),
            speakerSystems = listOf(
                SpeakerSystemRow(
                    "indoor",
                    "Indoor",
                    null,
                    false,
                    false,
                    "unreported",
                    listOf("speaker_system_status_missing"),
                    false
                ),
                SpeakerSystemRow(
                    "outdoor",
                    "Outdoor",
                    null,
                    false,
                    false,
                    "unreported",
                    listOf("speaker_system_status_missing"),
                    false
                )
            ),
            sinks = emptyList(),
            spotifyEndpoints = emptyList(),
            routes = emptyList(),
            selectedRouteId = null,
            activeRouteId = null,
            enabledSystemIds = emptyList(),
            activeSystemIds = emptyList(),
            audioOutputLastChangedAt = null,
            audioOutputAdapterMode = null,
            hasSpeakerSystemStatus = false,
            hasAudioOutputStatus = false,
            hasSpeakerConnectionStatus = false
        ),
        isLoading = false,
        message = "Fresh no-assignment status: Pi reachable, speaker assignment unreported."
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedHealthyPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard(),
        isLoading = false,
        message = null
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedDisconnectedPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard().copy(
            healthState = "degraded",
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
            ),
            activeSystemIds = listOf("indoor")
        ),
        isLoading = false,
        message = "Outdoor system is enabled but not active."
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedDemoModePreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard().copy(
            audioOutputAdapterMode = "stub"
        ),
        isLoading = false,
        message = null
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedLoadingPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard(),
        isLoading = true,
        message = "Speaker systems running: sim-operation"
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedFailedPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard(),
        isLoading = false,
        message = "stale_observation: Refresh status before retrying."
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedSuccessPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard().copy(
            speakerSystems = listOf(
                SpeakerSystemRow("indoor", "Indoor", "alsa-indoor", true, true, "ready", emptyList(), true),
                SpeakerSystemRow("outdoor", "Outdoor", "alsa-outdoor", true, true, "ready", emptyList(), true)
            ),
            enabledSystemIds = listOf("indoor", "outdoor"),
            activeSystemIds = listOf("indoor", "outdoor")
        ),
        isLoading = false,
        message = "Speaker systems completed: sim-operation\nStatus refreshed."
    )
}

@Preview(showBackground = true)
@Composable
private fun SimulatedAllOffPreview() {
    SimulatedControllerPreviewContent(
        dashboard = simulatedDashboard().copy(
            speakerSystems = listOf(
                SpeakerSystemRow("indoor", "Indoor", "alsa-indoor", false, false, "ready", emptyList(), true),
                SpeakerSystemRow("outdoor", "Outdoor", "alsa-outdoor", false, false, "ready", emptyList(), true)
            ),
            selectedRouteId = null,
            activeRouteId = null,
            enabledSystemIds = emptyList(),
            activeSystemIds = emptyList()
        ),
        isLoading = false,
        message = "All speaker systems are off."
    )
}

@Composable
private fun SimulatedControllerPreviewContent(
    dashboard: DashboardModel,
    isLoading: Boolean,
    message: String?
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            message?.let { MessageCard(text = it) }
            DashboardSection(
                dashboard = dashboard,
                isLoading = isLoading,
                onRefreshStatus = {},
                onReconnect = {},
                onRunWatchdog = {},
                onRestartService = {},
                onSetSpeakerSystemEnabled = { _, _ -> },
                onOpenSpotify = {}
            )
        }
    }
}

private fun simulatedDashboard(): DashboardModel {
    return DashboardModel(
        host = "simulated-pi.local",
        identity = TrustedIdentity(
            apiName = "pihouse-audio-api",
            contractVersion = "2026-06-phase3",
            deviceId = "simulated-pi",
            controllerInstanceId = "sim-controller"
        ),
        healthState = "healthy",
        phase = "phase3",
        healthReasons = emptyList(),
        observedBootId = "sim-boot",
        observedAt = "2026-06-12T18:00:00Z",
        speakers = listOf(
            ComponentRow("indoor-speaker", "Indoor speaker", "healthy", emptyList(), "Connected"),
            ComponentRow("outdoor-speaker", "Outdoor speaker", "healthy", emptyList(), "Connected")
        ),
        speakerSystems = listOf(
            SpeakerSystemRow("indoor", "Indoor", "alsa-indoor", true, true, "ready", emptyList(), true),
            SpeakerSystemRow("outdoor", "Outdoor", "alsa-outdoor", false, false, "ready", emptyList(), true)
        ),
        sinks = listOf(ComponentRow("alsa-indoor", "Indoor sink", "healthy", emptyList())),
        spotifyEndpoints = listOf(ComponentRow("spotifyd-main", "Spotify Connect", "healthy", emptyList())),
        routes = emptyList(),
        selectedRouteId = "indoor",
        activeRouteId = "indoor",
        enabledSystemIds = listOf("indoor"),
        activeSystemIds = listOf("indoor"),
        audioOutputLastChangedAt = "2026-06-12T18:00:00Z",
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
        watchdog = ComponentRow("bt_watchdog", "Bluetooth watchdog", "healthy", emptyList()),
        operations = emptyList(),
        supportedOperations = listOf("set_speaker_systems", "reconnect", "run_watchdog", "restart_service")
    )
}
