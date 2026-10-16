package com.antigravity.linux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.linux.antigravity.AuthManager
import com.antigravity.linux.antigravity.AuthStatus
import com.antigravity.linux.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    authManager: AuthManager,
    onBackToDashboard: () -> Unit
) {
    val authStatus by authManager.authStatus.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var fetchedLoginUrl by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authManager.checkAuthStatus()
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
                text = "Antigravity Authentication",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        // Account Status Card
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
                    Text(
                        text = "Account Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Surface(
                        color = if (authStatus.isAuthenticated) StatusGreenDim else StatusRedDim,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (authStatus.isAuthenticated) StatusGreen else StatusRed)
                    ) {
                        Text(
                            text = if (authStatus.isAuthenticated) "AUTHENTICATED" else "NOT LOGGED IN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (authStatus.isAuthenticated) StatusGreen else StatusRed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (authStatus.userEmail != null) {
                    Text(
                        text = "Signed in as: ${authStatus.userEmail}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = authStatus.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isChecking = true
                            authManager.checkAuthStatus()
                            isChecking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated),
                    enabled = !isChecking
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Violet40, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check Authentication Status", color = TextPrimary)
                }
            }
        }

        // Authentication Flow Card
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Sign In with Google",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Text(
                    text = "Antigravity Desktop requires official Google OAuth verification. Complete the sign-in in your mobile browser, and the credentials will synchronize with your Linux userspace session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                // Button: Open Antigravity Login
                Button(
                    onClick = { authManager.openLoginBrowser() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet40)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Open Antigravity Login", color = Color.White, fontWeight = FontWeight.Bold)
                }

                // Optional Button: Fetch Login Link
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val link = authManager.fetchLoginLink()
                            fetchedLoginUrl = link
                            if (link != null) {
                                snackbarMessage = "Login link retrieved"
                            } else {
                                snackbarMessage = "No active login link in Linux session. Launch Antigravity Desktop first."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch Login Link", color = TextPrimary)
                }

                // Display Fetched Login Link
                fetchedLoginUrl?.let { url ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCardElevated, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Active Login Link:", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = Violet40,
                            maxLines = 3
                        )
                        Button(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Antigravity Login URL", url))
                                snackbarMessage = "Login link copied to clipboard"
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Violet20)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Violet80, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Link", color = Violet80, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Security Notice
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
                    Text("Security Policy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }
                Text(
                    text = "• Credentials are never logged in plain text.\n• Authentication tokens are securely held in the user's isolated Linux home directory (/home/antigravity/.config/antigravity).\n• No credentials or tokens are transmitted to external non-Google servers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    lineHeight = 18.sp
                )
            }
        }

        // SnackBar
        snackbarMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                snackbarMessage = null
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = SurfaceCardElevated,
                border = BorderStroke(1.dp, Violet40)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
