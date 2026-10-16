package com.antigravity.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import com.antigravity.linux.data.StorageMetrics
import com.antigravity.linux.data.SystemDiagnostics
import com.antigravity.linux.data.UserSettings
import com.antigravity.linux.ui.theme.*

@Composable
fun SettingsScreen(
    diagnostics: SystemDiagnostics,
    storageMetrics: StorageMetrics,
    settings: UserSettings,
    vncLogs: String,
    onRefreshVncLogs: () -> Unit,
    onClearVncLogs: () -> Unit,
    onRestartVnc: () -> Unit,
    onUpdateAutoSyncClipboard: (Boolean) -> Unit,
    onUpdateRunInBackground: (Boolean) -> Unit,
    onUpdateResolution: (Int, Int) -> Unit,
    onDeleteEnvironment: () -> Unit,
    onBackToDashboard: () -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var autoStreamLogs by remember { mutableStateOf(true) }

    LaunchedEffect(autoStreamLogs) {
        while (autoStreamLogs) {
            onRefreshVncLogs()
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBackToDashboard, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = "Environment Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Section: System & Architecture Diagnostics
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("System Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                DiagnosticItem("Target Architecture", diagnostics.cpuArch + if (diagnostics.isArm64) " (Compatible ARM64)" else " (Incompatible)")
                DiagnosticItem("Kernel Version", diagnostics.kernelRelease)
                DiagnosticItem("glibc Support", diagnostics.glibcVersion)
                DiagnosticItem("PRoot Virtualization", if (diagnostics.prootAvailable) "Configured & Ready" else "Missing")
                DiagnosticItem("Rootfs Directory", diagnostics.rootfsPath)
            }
        }

        // Section: Antigravity Desktop Diagnostics
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Google Antigravity Desktop Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                DiagnosticItem("Binary Location", "/opt/antigravity/antigravity")
                DiagnosticItem("Binary Present", if (diagnostics.antigravityBinaryExists) "Yes" else "No")
                DiagnosticItem("ARM64 ELF Verified", if (diagnostics.antigravityArm64Verified) "Yes (EM_AARCH64)" else "No")
                DiagnosticItem("Process Status", if (diagnostics.antigravityProcessRunning) "Running" else "Stopped")

                if (diagnostics.missingLibraries.isNotEmpty()) {
                    Text("Missing Libraries:", style = MaterialTheme.typography.bodySmall, color = StatusRed)
                    Text(
                        diagnostics.missingLibraries.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                }

                Text(
                    text = diagnostics.compatibilitySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Violet40
                )
            }
        }

        // Section: Preferences
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                // Background running toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep Linux Running in Background", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Holds WakeLock and runs persistent notification", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                    Switch(
                        checked = settings.runInBackground,
                        onCheckedChange = { onUpdateRunInBackground(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Violet40)
                    )
                }

                Divider(color = SurfaceBorder)

                // Auto Sync Clipboard toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Sync Clipboard", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Requires user consent. Disabled by default.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                    Switch(
                        checked = settings.autoSyncClipboard,
                        onCheckedChange = { onUpdateAutoSyncClipboard(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Violet40)
                    )
                }

                Divider(color = SurfaceBorder)

                // Display Resolution
                Text("Desktop Display Resolution", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateResolution(1280, 720) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.desktopWidth == 1280) Violet40 else SurfaceCardElevated
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1280x720 (720p)", fontSize = 12.sp, color = TextPrimary)
                    }
                    Button(
                        onClick = { onUpdateResolution(1920, 1080) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.desktopWidth == 1920) Violet40 else SurfaceCardElevated
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1920x1080 (1080p)", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            }
        }

        // Section: VNC Server Live Logs
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "VNC Server Live Logs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (diagnostics.vncPortOpen) StatusGreen else StatusRed)
                            )
                            Text(
                                if (diagnostics.vncPortOpen) "Port 5901: Listening (Active)" else "Port 5901: Inactive",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (diagnostics.vncPortOpen) StatusGreen else StatusRed
                            )
                        }
                    }

                    // Auto-stream toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Live Stream", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Switch(
                            checked = autoStreamLogs,
                            onCheckedChange = { autoStreamLogs = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet40),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }

                // Log viewer container
                val logScrollState = rememberScrollState()
                LaunchedEffect(vncLogs) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                        .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = vncLogs.ifBlank { "No VNC server logs captured yet. Tap 'Start Linux Desktop' on Dashboard to initialize." },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = Color(0xFFC9D1D9),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(logScrollState)
                    )
                }

                // Actions: Refresh, Clear, Restart VNC
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshVncLogs,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextPrimary)
                        Spacer(Modifier.width(4.dp))
                        Text("Refresh", fontSize = 12.sp, color = TextPrimary)
                    }

                    OutlinedButton(
                        onClick = onClearVncLogs,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", fontSize = 12.sp, color = TextSecondary)
                    }

                    Button(
                        onClick = onRestartVnc,
                        colors = ButtonDefaults.buttonColors(containerColor = Violet40),
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Restart VNC", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        // Section: Storage Metrics
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Storage Usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                DiagnosticItem("Arch Rootfs Size", storageMetrics.rootfsFormatted)
                DiagnosticItem("Home (/home/antigravity)", storageMetrics.homeFormatted)
                DiagnosticItem("Device Free Space", storageMetrics.freeFormatted)
                DiagnosticItem("Total Internal Storage", storageMetrics.totalFormatted)
            }
        }

        // Section: Danger Zone
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, StatusRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = StatusRedDim),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Danger Zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StatusRed)
                Text(
                    "Completely delete the Arch Linux ARM64 rootfs and all user files. This action cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { showDeleteConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Arch Linux Environment", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Arch Linux Environment?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to permanently delete the Arch Linux ARM64 root filesystem and all files in /home/antigravity? You will need to re-download the environment to use Antigravity Linux again.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteEnvironment()
                        onBackToDashboard()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Delete Permanently", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = SurfaceCard,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun DiagnosticItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = TextPrimary)
    }
}
