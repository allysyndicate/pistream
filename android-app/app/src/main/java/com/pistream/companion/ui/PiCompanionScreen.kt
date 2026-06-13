package com.pistream.companion.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
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
import com.pistream.companion.ui.theme.PiPalette
import com.pistream.companion.ui.theme.PiStreamTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiCompanionScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Speakers",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
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
            onRetryPairing = viewModel::retryPairing,
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
    onRetryPairing: () -> Unit,
    onRetry: () -> Unit,
    onForget: () -> Unit,
    onSetZoneOn: (String, Boolean) -> Unit,
    onSetAllZonesOn: (Boolean) -> Unit,
    onReconnectSpeaker: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Crossfade(
            targetState = state.stage,
            animationSpec = tween(durationMillis = 260),
            label = "home-stage"
        ) { stage ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (stage) {
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
                        onRetryPairing = onRetryPairing,
                        onCancel = onCancelConnect
                    )
                    HomeStage.Connected -> ConnectedPanel(
                        connectionLabel = state.connectionLabel,
                        dashboard = state.dashboard,
                        isBusy = state.isBusy,
                        statusMessage = state.statusMessage,
                        onSetZoneOn = onSetZoneOn,
                        onSetAllZonesOn = onSetAllZonesOn,
                        onReconnectSpeaker = onReconnectSpeaker,
                        onOpenSpotify = { openSpotify(context) }
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ConnectingPanel() {
    SoftCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Column {
                Text("Connecting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Talking to your Pi...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyPanel(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        SoftCard {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                BurgundyMonogram()
                Text(
                    "Add your first speaker",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Pair a Raspberry Pi running PiHouse Audio and we'll show your speakers here. The Pi handles the Bluetooth — this is the remote.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                PrimaryActionButton(
                    text = "Connect a speaker",
                    onClick = onConnect
                )
            }
        }
    }
}

@Composable
private fun BurgundyMonogram() {
    Surface(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                "Pi",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
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
    SoftCard {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusChip(
                text = "Offline",
                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "Can't reach your Pi",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                message ?: "The saved Pi at ${host ?: "(unknown)"} did not respond.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryActionButton(text = "Try again", onClick = onRetry)
                OutlinedButton(
                    onClick = onForget,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text("Forget Pi")
                }
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
    onRetryPairing: () -> Unit,
    onCancel: () -> Unit
) {
    SoftCard {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Find your Pi", style = MaterialTheme.typography.headlineSmall)
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
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Searching...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        discoveredPis.forEach { pi ->
                            DiscoveredPiRow(pi = pi, onClick = { onChooseDiscovered(pi) })
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Don't see your Pi?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = manualHostInput,
                    onValueChange = onUpdateManualHost,
                    label = { Text("Pi IP address or hostname") },
                    placeholder = { Text("e.g. 192.168.1.42") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onSubmitManualHost,
                    enabled = manualHostInput.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Add by address")
                }
            } else {
                PairingContent(
                    pairing = pairing,
                    manualTokenInput = manualTokenInput,
                    onUpdateManualToken = onUpdateManualToken,
                    onSubmitManualToken = onSubmitManualToken,
                    onRetryPairing = onRetryPairing
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
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(pi.label, style = MaterialTheme.typography.titleMedium)
            Text(
                pi.authority,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    onSubmitManualToken: () -> Unit,
    onRetryPairing: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pairing with ${pairing.host}", style = MaterialTheme.typography.titleMedium)

        if (pairing.busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Asking the Pi for a pairing token...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryActionButton(
                text = "Pair",
                onClick = onSubmitManualToken,
                enabled = manualTokenInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (pairing.canRetry) {
            pairing.note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PrimaryActionButton(
                text = "Try again",
                onClick = onRetryPairing,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            pairing.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ColumnScope.ConnectedPanel(
    connectionLabel: ConnectionLabel,
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

    ConnectionLabelChip(label = connectionLabel)

    AnimatedVisibility(
        visible = dashboard.healthReasons.isNotEmpty(),
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f)
    ) {
        HealthIssuesBanner(reasonCodes = dashboard.healthReasons)
    }

    SpeakersList(
        dashboard = dashboard,
        isBusy = isBusy,
        onSetZoneOn = onSetZoneOn,
        onReconnectSpeaker = onReconnectSpeaker
    )

    WholeHouseCard(
        dashboard = dashboard,
        isBusy = isBusy,
        onSetAllZonesOn = onSetAllZonesOn,
        onOpenSpotify = onOpenSpotify
    )

    UnavailableControlsHint(dashboard = dashboard)

    statusMessage?.let { MessageCard(it) }
}

@Composable
private fun ConnectionLabelChip(label: ConnectionLabel) {
    when (label) {
        ConnectionLabel.None, ConnectionLabel.Failed -> Unit
        is ConnectionLabel.Connected -> StatusChip(
            text = "Connected to ${label.host}",
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            dotColor = MaterialTheme.colorScheme.primary
        )
        is ConnectionLabel.Demo -> StatusChip(
            text = "Demo mode — ${label.host} is running the stub adapter",
            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            dotColor = MaterialTheme.colorScheme.tertiary
        )
        is ConnectionLabel.Degraded -> StatusChip(
            text = "${label.host} — degraded",
            backgroundColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            dotColor = MaterialTheme.colorScheme.error
        )
    }
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            SoftCard {
                Column(
                    modifier = Modifier.padding(24.dp),
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
    val isPlaying = !isDemoMode && readiness == SystemReadiness.READY && system.active

    val cardContainer by animateColorAsState(
        targetValue = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = 320),
        label = "speaker-card-bg"
    )
    val cardContent by animateColorAsState(
        targetValue = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 320),
        label = "speaker-card-fg"
    )
    val elevation by animateDpAsState(
        targetValue = if (isPlaying) 4.dp else 1.dp,
        animationSpec = tween(durationMillis = 280),
        label = "speaker-card-elevation"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardContainer,
            contentColor = cardContent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusDot(readiness = readiness, isDemoMode = isDemoMode)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        system.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPlaying) cardContent.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = system.enabled,
                    enabled = !isBusy && canToggle,
                    onCheckedChange = onSetOn,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            AnimatedVisibility(
                visible = isPlaying || isDemoMode,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
                Row {
                    if (isPlaying) {
                        PlayingPill()
                    } else if (isDemoMode) {
                        DemoChip()
                    }
                }
            }

            speaker?.let { row ->
                val tag = when (speakerLive) {
                    SpeakerLiveState.CONNECTED -> if (isDemoMode) "Bluetooth: simulated link" else "Bluetooth linked"
                    SpeakerLiveState.DISCONNECTED -> "Bluetooth: not linked"
                    SpeakerLiveState.UNASSIGNED -> "No speaker assigned"
                    SpeakerLiveState.UNKNOWN -> "Bluetooth: status unknown"
                }
                Text(
                    tag,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPlaying) cardContent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReasonList(row.reasonCodes, onMutedSurface = !isPlaying)
                if (onReconnect != null && speakerLive != SpeakerLiveState.UNASSIGNED) {
                    OutlinedButton(
                        onClick = onReconnect,
                        enabled = !isBusy && canReconnect,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
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

            ReasonList(system.reasonCodes, onMutedSurface = !isPlaying)
        }
    }
}

@Composable
private fun PlayingPill() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary)
            )
            Text(
                "Playing",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DemoChip() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Text(
            "Demo",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun WholeHouseCard(
    dashboard: DashboardModel,
    isBusy: Boolean,
    onSetAllZonesOn: (Boolean) -> Unit,
    onOpenSpotify: () -> Unit
) {
    val canSetSystems = dashboard.canRunOperation("set_speaker_systems")
    val allOn = dashboard.speakerSystems.isNotEmpty() &&
        dashboard.speakerSystems.all { it.enabled }
    SoftCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Whole house",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (allOn) "All zones on" else "Pick which zones to play",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = allOn,
                    enabled = !isBusy && canSetSystems && dashboard.speakerSystems.isNotEmpty(),
                    onCheckedChange = onSetAllZonesOn,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
            PrimaryActionButton(
                text = "Play on Spotify",
                onClick = onOpenSpotify,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Spotify Connect targets the enabled zones above. The Pi owns the Bluetooth link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    SoftCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Why some controls are off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            reasons.forEach { reason ->
                Text(
                    "• $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusDot(readiness: SystemReadiness, isDemoMode: Boolean) {
    val color = readinessColor(readiness, isDemoMode)
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
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
private fun HealthIssuesBanner(reasonCodes: List<String>) {
    SoftCard(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
private fun ReasonList(reasonCodes: List<String>, onMutedSurface: Boolean) {
    if (reasonCodes.isEmpty()) return
    val color = if (onMutedSurface) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReasonCodes.toPlainEnglishList(reasonCodes).forEach { sentence ->
            Text("• $sentence", style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun MessageCard(text: String) {
    SoftCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Last update",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SoftCard(
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun StatusChip(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    dotColor: Color? = null
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = backgroundColor,
        contentColor = contentColor,
        modifier = Modifier.widthIn(min = 80.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
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
            connectionLabel = ConnectionLabel.Connected("kitchen-pi.local"),
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
            connectionLabel = ConnectionLabel.Demo("kitchen-pi.local"),
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
            connectionLabel = ConnectionLabel.Degraded("kitchen-pi.local"),
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
            onRetryPairing = {},
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
            onRetryPairing = {},
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
            onRetryPairing = {},
            onCancel = {}
        )
    }
}

@Composable
private fun PreviewWrapper(content: @Composable ColumnScope.() -> Unit) {
    PiStreamTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) { content() }
        }
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
