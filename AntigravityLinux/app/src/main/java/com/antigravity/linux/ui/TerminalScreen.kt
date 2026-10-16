package com.antigravity.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.linux.terminal.TerminalSession
import com.antigravity.linux.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBackToDashboard: () -> Unit
) {
    val outputLines by terminalSession.terminalOutput.collectAsState()
    val isRunning by terminalSession.isRunning.collectAsState()

    var inputCommand by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-start session if not already running
    LaunchedEffect(Unit) {
        if (!isRunning) {
            terminalSession.startSession()
        }
    }

    // Scroll to bottom on new output
    LaunchedEffect(outputLines.size) {
        if (outputLines.isNotEmpty()) {
            listState.animateScrollToItem(outputLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBackToDashboard, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Column {
                    Text(
                        text = "Arch Linux ARM64 Terminal",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isRunning) "Session Active (PRoot bash)" else "Disconnected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) StatusGreen else StatusRed
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { terminalSession.startSession() },
                    modifier = Modifier.size(36.dp),
                    enabled = !isRunning
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = Violet40)
                }
            }
        }

        // Terminal Log Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(outputLines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = parseAnsiColors(line),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Quick Command Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AssistChip(
                onClick = { terminalSession.sendCommand("uname -a") },
                label = { Text("uname -a", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            )
            AssistChip(
                onClick = { terminalSession.sendCommand("cat /etc/os-release") },
                label = { Text("os-release", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            )
            AssistChip(
                onClick = { terminalSession.sendCommand("dpkg -l | head -20") },
                label = { Text("packages", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            )
            AssistChip(
                onClick = { terminalSession.sendCommand("ls -la /opt/antigravity") },
                label = { Text("check antigravity", fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            )
            AssistChip(
                onClick = { terminalSession.sendCtrlC() },
                label = { Text("Ctrl+C", fontSize = 11.sp, color = StatusRed) }
            )
            AssistChip(
                onClick = { terminalSession.sendTab() },
                label = { Text("Tab", fontSize = 11.sp, color = Violet40) }
            )
            AssistChip(
                onClick = { terminalSession.sendCtrlD() },
                label = { Text("Exit", fontSize = 11.sp) }
            )
        }

        // Command Prompt Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // History Up/Down
            IconButton(
                onClick = {
                    val prev = terminalSession.getPreviousHistory()
                    if (prev != null) inputCommand = prev
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "History Up", tint = TextSecondary)
            }
            IconButton(
                onClick = {
                    val next = terminalSession.getNextHistory()
                    if (next != null) inputCommand = next
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "History Down", tint = TextSecondary)
            }

            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type Linux command...", fontSize = 13.sp, color = TextTertiary) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet40,
                    unfocusedBorderColor = SurfaceBorder,
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputCommand.isNotBlank()) {
                            terminalSession.sendCommand(inputCommand)
                            inputCommand = ""
                        }
                    }
                )
            )

            IconButton(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        terminalSession.sendCommand(inputCommand)
                        inputCommand = ""
                    }
                },
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Violet40)
            }
        }
    }
}

private fun parseAnsiColors(line: String): Color {
    return when {
        line.contains("\u001B[31m") || line.contains("[-] ") || line.contains("error", ignoreCase = true) -> StatusRed
        line.contains("\u001B[32m") || line.contains("[+] ") -> StatusGreen
        line.contains("\u001B[36m") || line.contains("Arch Linux", ignoreCase = true) -> StatusBlue
        line.contains("\u001B[33m") || line.contains("warning", ignoreCase = true) -> StatusOrange
        else -> TextPrimary
    }
}
