package com.antigravity.linux.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.linux.data.*
import com.antigravity.linux.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    runtimeState: RuntimeState,
    desktopState: DesktopState,
    antigravityState: AntigravityState,
    storageMetrics: StorageMetrics,
    diagnostics: SystemDiagnostics,
    statusMessage: String,
    installProgress: Int,
    isInstalling: Boolean,
    onInitializeArchLinux: () -> Unit,
    onStartDesktop: () -> Unit,
    onLaunchAntigravity: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAuth: () -> Unit,
    onStopLinux: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        AppHeader(diagnostics = diagnostics)

        // Installation Progress Banner
        AnimatedVisibility(visible = isInstalling) {
            InstallationProgressCard(
                progress = installProgress,
                statusMessage = statusMessage
            )
        }

        // Environment Status Overview Card
        StatusOverviewCard(
            runtimeState = runtimeState,
            desktopState = desktopState,
            antigravityState = antigravityState,
            storageMetrics = storageMetrics,
            statusMessage = statusMessage
        )

        // Primary Control Actions
        ControlActionsCard(
            runtimeState = runtimeState,
            desktopState = desktopState,
            antigravityState = antigravityState,
            isInstalling = isInstalling,
            onInitializeArchLinux = onInitializeArchLinux,
            onStartDesktop = onStartDesktop,
            onLaunchAntigravity = onLaunchAntigravity,
            onOpenTerminal = onOpenTerminal,
            onOpenDesktop = onOpenDesktop,
            onOpenSettings = onOpenSettings,
            onOpenAuth = onOpenAuth,
            onStopLinux = onStopLinux
        )

        // Diagnostics Card
        QuickDiagnosticsCard(diagnostics = diagnostics)
    }
}

@Composable
private fun AppHeader(diagnostics: SystemDiagnostics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Antigravity Linux",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Ultra-Light Linux ARM64 Desktop",
                style = MaterialTheme.typography.bodyMedium,
                color = Violet40
            )
        }

        // Architecture Badge
        Surface(
            color = if (diagnostics.isArm64) StatusGreenDim else StatusRedDim,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (diagnostics.isArm64) StatusGreen else StatusRed)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (diagnostics.isArm64) StatusGreen else StatusRed)
                )
                Text(
                    text = diagnostics.cpuArch.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun StatusOverviewCard(
    runtimeState: RuntimeState,
    desktopState: DesktopState,
    antigravityState: AntigravityState,
    storageMetrics: StorageMetrics,
    statusMessage: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Environment Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            StatusRow(
                label = "Linux Userspace",
                value = runtimeState.label,
                stateColor = when (runtimeState) {
                    RuntimeState.RUNNING -> StatusGreen
                    RuntimeState.READY -> StatusBlue
                    RuntimeState.INITIALIZING, RuntimeState.DOWNLOADING, RuntimeState.EXTRACTING, RuntimeState.STARTING -> StatusOrange
                    RuntimeState.ERROR -> StatusRed
                    else -> TextSecondary
                }
            )

            StatusRow(
                label = "Desktop Session",
                value = desktopState.label,
                stateColor = when (desktopState) {
                    DesktopState.RUNNING -> StatusGreen
                    DesktopState.STARTING -> StatusOrange
                    DesktopState.ERROR -> StatusRed
                    else -> TextSecondary
                }
            )

            StatusRow(
                label = "Antigravity Desktop",
                value = antigravityState.label,
                stateColor = when (antigravityState) {
                    AntigravityState.RUNNING -> StatusGreen
                    AntigravityState.STARTING, AntigravityState.CHECKING -> StatusOrange
                    AntigravityState.ERROR, AntigravityState.UNSUPPORTED -> StatusRed
                    else -> TextSecondary
                }
            )

            StatusRow(
                label = "Rootfs Storage",
                value = "${storageMetrics.rootfsFormatted} (Free: ${storageMetrics.freeFormatted})",
                stateColor = TextPrimary
            )

            Divider(color = SurfaceBorder, thickness = 1.dp)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Violet40,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, stateColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun ControlActionsCard(
    runtimeState: RuntimeState,
    desktopState: DesktopState,
    antigravityState: AntigravityState,
    isInstalling: Boolean,
    onInitializeArchLinux: () -> Unit,
    onStartDesktop: () -> Unit,
    onLaunchAntigravity: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAuth: () -> Unit,
    onStopLinux: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Runtime Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            // 1. Initialize Ultra-Light Linux
            ActionButton(
                title = "1. Initialize Ultra-Light Linux",
                subtitle = "Fast 27 MB download • Pure ARM64 runtime for Antigravity",
                icon = Icons.Default.CloudDownload,
                containerColor = SurfaceCardElevated,
                contentColor = Violet40,
                enabled = !isInstalling && runtimeState != RuntimeState.RUNNING,
                onClick = onInitializeArchLinux
            )

            // 2. Start Linux Desktop
            ActionButton(
                title = "2. Start Linux Desktop",
                subtitle = "Launch X11 display server & Antigravity host",
                icon = Icons.Default.DesktopWindows,
                containerColor = SurfaceCardElevated,
                contentColor = StatusGreen,
                enabled = !isInstalling && runtimeState != RuntimeState.NOT_INSTALLED && desktopState != DesktopState.RUNNING,
                onClick = onStartDesktop
            )

            // 3. Launch Antigravity
            ActionButton(
                title = "3. Launch Antigravity",
                subtitle = "Start Google Antigravity Desktop on DISPLAY=:1",
                icon = Icons.Default.RocketLaunch,
                containerColor = SurfaceCardElevated,
                contentColor = Violet40,
                enabled = !isInstalling && runtimeState != RuntimeState.NOT_INSTALLED && antigravityState != AntigravityState.RUNNING,
                onClick = onLaunchAntigravity
            )

            // 4. Open Terminal
            ActionButton(
                title = "4. Open Terminal",
                subtitle = "Direct interactive bash shell & package manager (apt)",
                icon = Icons.Default.Terminal,
                containerColor = SurfaceCardElevated,
                contentColor = StatusBlue,
                enabled = runtimeState != RuntimeState.NOT_INSTALLED,
                onClick = onOpenTerminal
            )

            // 5. Open Desktop Session
            ActionButton(
                title = "5. Open Desktop Canvas",
                subtitle = "Interactive touch desktop with mouse & virtual keyboard",
                icon = Icons.Default.OpenInNew,
                containerColor = SurfaceCardElevated,
                contentColor = StatusGreen,
                enabled = desktopState == DesktopState.RUNNING,
                onClick = onOpenDesktop
            )

            // 6. Environment Settings & Auth
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = onOpenAuth,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated)
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Violet40, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Auth", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // 7. Stop Linux Environment
            Button(
                onClick = onStopLinux,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StatusRedDim),
                border = BorderStroke(1.dp, StatusRed),
                enabled = runtimeState == RuntimeState.RUNNING || desktopState == DesktopState.RUNNING
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, tint = StatusRed, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop Linux Environment", color = StatusRed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (enabled) contentColor.copy(alpha = 0.15f) else SurfaceBorder),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) TextPrimary else TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun InstallationProgressCard(progress: Int, statusMessage: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Violet40, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCardElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Setting up Ultra-Light Linux ARM64 (27 MB)...",
                    style = MaterialTheme.typography.titleSmall,
                    color = Violet40,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }

            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Violet40,
                trackColor = SurfaceBorder
            )

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun QuickDiagnosticsCard(diagnostics: SystemDiagnostics) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "System Compatibility",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Text(
                text = diagnostics.compatibilitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = if (diagnostics.isArm64) TextSecondary else StatusRed
            )

            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Architecture:", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                Text(diagnostics.cpuArch, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kernel Release:", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                Text(diagnostics.kernelRelease, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("glibc Support:", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                Text(diagnostics.glibcVersion, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
            }
        }
    }
}
