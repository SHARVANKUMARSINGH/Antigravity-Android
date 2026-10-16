package com.antigravity.linux

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.antigravity.linux.antigravity.AntigravityManager
import com.antigravity.linux.antigravity.AuthManager
import com.antigravity.linux.data.DesktopState
import com.antigravity.linux.data.PreferencesRepository
import com.antigravity.linux.data.RuntimeState
import com.antigravity.linux.data.UserSettings
import com.antigravity.linux.desktop.DesktopManager
import com.antigravity.linux.runtime.InstallProgressListener
import com.antigravity.linux.runtime.LinuxRuntimeManager
import com.antigravity.linux.service.LinuxForegroundService
import com.antigravity.linux.terminal.TerminalSession
import com.antigravity.linux.ui.*
import com.antigravity.linux.ui.theme.AntigravityLinuxTheme
import com.antigravity.linux.ui.theme.SurfaceDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var runtimeManager: LinuxRuntimeManager
    private lateinit var desktopManager: DesktopManager
    private lateinit var terminalSession: TerminalSession
    private lateinit var antigravityManager: AntigravityManager
    private lateinit var authManager: AuthManager
    private lateinit var preferencesRepo: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Core Subsystems
        runtimeManager = LinuxRuntimeManager(this)
        desktopManager = DesktopManager(this)
        terminalSession = TerminalSession(runtimeManager.commandExecutor)
        antigravityManager = AntigravityManager(runtimeManager.rootfsDir, runtimeManager.commandExecutor)
        authManager = AuthManager(this, runtimeManager.rootfsDir, runtimeManager.commandExecutor)
        preferencesRepo = PreferencesRepository(this)

        setContent {
            AntigravityLinuxTheme {
                val navController = rememberNavController()

                val runtimeState by runtimeManager.runtimeState.collectAsState()
                val desktopState by runtimeManager.desktopState.collectAsState()
                val antigravityState by runtimeManager.antigravityState.collectAsState()
                val storageMetrics by runtimeManager.storageMetrics.collectAsState()
                val diagnostics by runtimeManager.diagnostics.collectAsState()
                val statusMessage by runtimeManager.statusMessage.collectAsState()
                val settings by preferencesRepo.settingsFlow.collectAsState(initial = UserSettings())

                var isInstalling by remember { mutableStateOf(false) }
                var installProgress by remember { mutableStateOf(0) }

                // Sync auto-sync clipboard setting with DesktopManager
                LaunchedEffect(settings.autoSyncClipboard) {
                    desktopManager.autoSyncClipboard = settings.autoSyncClipboard
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceDark
                ) {
                    NavHost(navController = navController, startDestination = "dashboard") {
                        // 1. Home Dashboard
                        composable("dashboard") {
                            DashboardScreen(
                                runtimeState = runtimeState,
                                desktopState = desktopState,
                                antigravityState = antigravityState,
                                storageMetrics = storageMetrics,
                                diagnostics = diagnostics,
                                statusMessage = statusMessage,
                                installProgress = installProgress,
                                isInstalling = isInstalling,
                                onInitializeArchLinux = {
                                    isInstalling = true
                                    installProgress = 0
                                    lifecycleScope.launch {
                                        val success = runtimeManager.initializeEnvironment(object : InstallProgressListener {
                                            override fun onProgress(stage: String, percent: Int, detail: String) {
                                                installProgress = percent
                                            }

                                            override fun onError(stage: String, error: String) {
                                                isInstalling = false
                                                Toast.makeText(this@MainActivity, "Install Failed: $error", Toast.LENGTH_LONG).show()
                                            }

                                            override fun onSuccess(detail: String) {
                                                isInstalling = false
                                                installProgress = 100
                                                Toast.makeText(this@MainActivity, detail, Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                        if (success && settings.runInBackground) {
                                            LinuxForegroundService.startService(this@MainActivity)
                                        }
                                    }
                                },
                                onStartDesktop = {
                                    lifecycleScope.launch {
                                        val started = runtimeManager.startDesktop()
                                        if (started) {
                                            desktopManager.connect("127.0.0.1", 5901)
                                            if (settings.runInBackground) {
                                                LinuxForegroundService.startService(this@MainActivity)
                                            }
                                            navController.navigate("desktop")
                                        } else {
                                            Toast.makeText(this@MainActivity, "Failed to start desktop", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLaunchAntigravity = {
                                    lifecycleScope.launch {
                                        val launched = runtimeManager.launchAntigravity()
                                        if (launched) {
                                            desktopManager.connect("127.0.0.1", 5901)
                                            navController.navigate("desktop")
                                        } else {
                                            Toast.makeText(this@MainActivity, "Could not launch Antigravity. Check Diagnostics.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onOpenTerminal = {
                                    navController.navigate("terminal")
                                },
                                onOpenDesktop = {
                                    lifecycleScope.launch {
                                        if (desktopState == DesktopState.RUNNING) {
                                            desktopManager.connect("127.0.0.1", 5901)
                                        }
                                        navController.navigate("desktop")
                                    }
                                },
                                onOpenSettings = {
                                    navController.navigate("settings")
                                },
                                onOpenAuth = {
                                    navController.navigate("auth")
                                },
                                onStopLinux = {
                                    lifecycleScope.launch {
                                        runtimeManager.stopAll()
                                        desktopManager.disconnect()
                                        LinuxForegroundService.stopService(this@MainActivity)
                                        Toast.makeText(this@MainActivity, "Linux environment stopped", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        // 2. Interactive Desktop Screen
                        composable("desktop") {
                            DesktopScreen(
                                desktopManager = desktopManager,
                                onBackToDashboard = {
                                    navController.popBackStack()
                                },
                                onOpenSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        // 3. Linux Terminal Screen
                        composable("terminal") {
                            TerminalScreen(
                                terminalSession = terminalSession,
                                onBackToDashboard = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        // 4. Settings Screen
                        composable("settings") {
                            SettingsScreen(
                                diagnostics = diagnostics,
                                storageMetrics = storageMetrics,
                                settings = settings,
                                onUpdateAutoSyncClipboard = { enabled ->
                                    lifecycleScope.launch {
                                        preferencesRepo.updateAutoSyncClipboard(enabled)
                                    }
                                },
                                onUpdateRunInBackground = { enabled ->
                                    lifecycleScope.launch {
                                        preferencesRepo.updateRunInBackground(enabled)
                                        if (!enabled) {
                                            LinuxForegroundService.stopService(this@MainActivity)
                                        } else if (runtimeState == RuntimeState.RUNNING) {
                                            LinuxForegroundService.startService(this@MainActivity)
                                        }
                                    }
                                },
                                onUpdateResolution = { w, h ->
                                    lifecycleScope.launch {
                                        preferencesRepo.updateDesktopResolution(w, h)
                                    }
                                },
                                onDeleteEnvironment = {
                                    lifecycleScope.launch {
                                        runtimeManager.deleteEnvironment()
                                        LinuxForegroundService.stopService(this@MainActivity)
                                        Toast.makeText(this@MainActivity, "Environment removed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onBackToDashboard = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        // 5. Antigravity Auth Screen
                        composable("auth") {
                            AuthScreen(
                                authManager = authManager,
                                onBackToDashboard = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        terminalSession.close()
        super.onDestroy()
    }
}
