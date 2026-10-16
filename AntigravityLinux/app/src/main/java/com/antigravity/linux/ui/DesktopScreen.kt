package com.antigravity.linux.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.linux.desktop.DesktopManager
import com.antigravity.linux.desktop.KeySymbols
import com.antigravity.linux.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    desktopManager: DesktopManager,
    onBackToDashboard: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val framebuffer by desktopManager.framebuffer.collectAsState()
    val desktopInfo by desktopManager.desktopInfo.collectAsState()

    var showKeyboard by remember { mutableStateOf(false) }
    var showModifiers by remember { mutableStateOf(true) }
    var fitToScreen by remember { mutableStateOf(true) }
    var currentMouseMode by remember { mutableStateOf(1) } // 1 = Left, 2 = Middle, 4 = Right

    // Modifiers state
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    var superActive by remember { mutableStateOf(false) }

    // Hidden text field for soft keyboard input
    var textInput by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // SnackBar message for clipboard confirmation
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Desktop Framebuffer Canvas
        if (framebuffer != null) {
            val bmp = framebuffer!!
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bmp) {
                        detectTapGestures(
                            onTap = { offset ->
                                val (dx, dy) = calculateDesktopCoords(offset, size, bmp.width, bmp.height, fitToScreen)
                                desktopManager.sendPointerEvent(dx, dy, currentMouseMode)
                                desktopManager.sendPointerEvent(dx, dy, 0)
                            },
                            onLongPress = { offset ->
                                val (dx, dy) = calculateDesktopCoords(offset, size, bmp.width, bmp.height, fitToScreen)
                                desktopManager.sendPointerEvent(dx, dy, 4) // Right click
                                desktopManager.sendPointerEvent(dx, dy, 0)
                            }
                        )
                    }
                    .pointerInput(bmp) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val (dx, dy) = calculateDesktopCoords(offset, size, bmp.width, bmp.height, fitToScreen)
                                desktopManager.sendPointerEvent(dx, dy, currentMouseMode)
                            },
                            onDrag = { change, _ ->
                                val (dx, dy) = calculateDesktopCoords(change.position, size, bmp.width, bmp.height, fitToScreen)
                                desktopManager.sendPointerEvent(dx, dy, currentMouseMode)
                            },
                            onDragEnd = {
                                desktopManager.sendPointerEvent(0, 0, 0)
                            }
                        )
                    }
            ) {
                val canvasW = size.width
                val canvasH = size.height

                if (fitToScreen) {
                    val scaleX = canvasW / bmp.width
                    val scaleY = canvasH / bmp.height
                    val scale = minOf(scaleX, scaleY)
                    val drawW = (bmp.width * scale).toInt()
                    val drawH = (bmp.height * scale).toInt()
                    val offsetX = ((canvasW - drawW) / 2).toInt()
                    val offsetY = ((canvasH - drawH) / 2).toInt()

                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(
                            bmp,
                            null,
                            android.graphics.Rect(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
                            null
                        )
                    }
                } else {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawBitmap(bmp, 0f, 0f, null)
                    }
                }
            }
        } else {
            // Loading placeholder
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Violet40)
                Text(
                    text = "Connecting to Linux Desktop Session...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = desktopInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }

        // Invisible text field for software keyboard integration
        BasicTextField(
            value = textInput,
            onValueChange = { newValue ->
                if (newValue.text.length > textInput.text.length) {
                    // Added characters
                    val added = newValue.text.substring(textInput.text.length)
                    desktopManager.sendText(added)
                } else if (newValue.text.length < textInput.text.length) {
                    // Backspace pressed
                    val diff = textInput.text.length - newValue.text.length
                    repeat(diff) {
                        desktopManager.sendKeyStroke(KeySymbols.BACKSPACE)
                    }
                }
                textInput = newValue
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(
                onDone = {
                    desktopManager.sendKeyStroke(KeySymbols.RETURN)
                }
            )
        )

        // Top Header Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopCenter)
                .background(SurfaceDark.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackToDashboard, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text(
                text = desktopInfo,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { fitToScreen = !fitToScreen }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (fitToScreen) Icons.Default.ZoomOutMap else Icons.Default.FitScreen,
                        contentDescription = "Scaling",
                        tint = Violet40
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                }
            }
        }

        // Bottom Controls Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(SurfaceDark.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Modifiers Bar (Ctrl, Alt, Shift, Super, Esc, Tab, Shortcuts)
            if (showModifiers) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ctrl Toggle
                    FilterChip(
                        selected = ctrlActive,
                        onClick = {
                            ctrlActive = !ctrlActive
                            desktopManager.sendKeyEvent(KeySymbols.CONTROL_L, ctrlActive)
                        },
                        label = { Text("Ctrl", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Violet40,
                            selectedLabelColor = Color.White
                        )
                    )

                    // Alt Toggle
                    FilterChip(
                        selected = altActive,
                        onClick = {
                            altActive = !altActive
                            desktopManager.sendKeyEvent(KeySymbols.ALT_L, altActive)
                        },
                        label = { Text("Alt", fontSize = 12.sp) }
                    )

                    // Shift Toggle
                    FilterChip(
                        selected = shiftActive,
                        onClick = {
                            shiftActive = !shiftActive
                            desktopManager.sendKeyEvent(KeySymbols.SHIFT_L, shiftActive)
                        },
                        label = { Text("Shift", fontSize = 12.sp) }
                    )

                    // Esc
                    AssistChip(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.ESCAPE) },
                        label = { Text("Esc", fontSize = 12.sp) }
                    )

                    // Tab
                    AssistChip(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.TAB) },
                        label = { Text("Tab", fontSize = 12.sp) }
                    )

                    // Ctrl+C
                    AssistChip(
                        onClick = {
                            desktopManager.sendShortcut(KeySymbols.CONTROL_L, 'c')
                            snackbarMessage = "Ctrl+C sent"
                        },
                        label = { Text("Ctrl+C", fontSize = 12.sp) }
                    )

                    // Ctrl+V
                    AssistChip(
                        onClick = {
                            desktopManager.sendShortcut(KeySymbols.CONTROL_L, 'v')
                            snackbarMessage = "Ctrl+V sent"
                        },
                        label = { Text("Ctrl+V", fontSize = 12.sp) }
                    )

                    // Ctrl+Z
                    AssistChip(
                        onClick = { desktopManager.sendShortcut(KeySymbols.CONTROL_L, 'z') },
                        label = { Text("Ctrl+Z", fontSize = 12.sp) }
                    )

                    // Ctrl+S
                    AssistChip(
                        onClick = { desktopManager.sendShortcut(KeySymbols.CONTROL_L, 's') },
                        label = { Text("Ctrl+S", fontSize = 12.sp) }
                    )

                    // Arrows: Left, Up, Down, Right
                    IconButton(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.LEFT) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Left", tint = TextPrimary)
                    }
                    IconButton(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.UP) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = TextPrimary)
                    }
                    IconButton(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.DOWN) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Down", tint = TextPrimary)
                    }
                    IconButton(
                        onClick = { desktopManager.sendKeyStroke(KeySymbols.RIGHT) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "Right", tint = TextPrimary)
                    }
                }
            }

            // Compact Floating Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keyboard toggle
                IconButton(
                    onClick = {
                        showKeyboard = !showKeyboard
                        if (showKeyboard) {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        } else {
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "Keyboard",
                        tint = if (showKeyboard) Violet40 else TextSecondary
                    )
                }

                // Mouse Mode: Left (1), Middle (2), Right (4)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceCard),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { currentMouseMode = 1 },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Mouse,
                            contentDescription = "Left Click",
                            tint = if (currentMouseMode == 1) StatusGreen else TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { currentMouseMode = 4 },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.TouchApp,
                            contentDescription = "Right Click",
                            tint = if (currentMouseMode == 4) StatusOrange else TextSecondary
                        )
                    }
                }

                // Android -> Linux Clipboard
                Button(
                    onClick = {
                        val synced = desktopManager.syncAndroidToLinuxClipboard()
                        snackbarMessage = if (synced) "Android clipboard copied to Linux" else "Android clipboard is empty"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.ArrowForward, contentDescription = null, tint = StatusBlue, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("A→L", fontSize = 11.sp, color = TextPrimary)
                }

                // Linux -> Android Clipboard
                Button(
                    onClick = {
                        val synced = desktopManager.syncLinuxToAndroidClipboard()
                        snackbarMessage = if (synced) "Linux clipboard copied to Android" else "No Linux clipboard content"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("L→A", fontSize = 11.sp, color = TextPrimary)
                }

                // Modifiers Bar Visibility Toggle
                IconButton(
                    onClick = { showModifiers = !showModifiers },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (showModifiers) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Modifiers",
                        tint = TextSecondary
                    )
                }
            }
        }

        // Snackbar Toast Notification
        snackbarMessage?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                snackbarMessage = null
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                shape = RoundedCornerShape(8.dp),
                color = SurfaceCardElevated,
                border = BorderStroke(1.dp, Violet40)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun calculateDesktopCoords(
    screenOffset: Offset,
    canvasSize: IntSize,
    bmpWidth: Int,
    bmpHeight: Int,
    fitToScreen: Boolean
): Pair<Int, Int> {
    if (!fitToScreen) {
        return Pair(screenOffset.x.toInt(), screenOffset.y.toInt())
    }
    val scaleX = canvasSize.width.toFloat() / bmpWidth
    val scaleY = canvasSize.height.toFloat() / bmpHeight
    val scale = minOf(scaleX, scaleY)
    val drawW = bmpWidth * scale
    val drawH = bmpHeight * scale
    val offsetX = (canvasSize.width.toFloat() - drawW) / 2f
    val offsetY = (canvasSize.height.toFloat() - drawH) / 2f

    val relativeX = ((screenOffset.x - offsetX) / scale).toInt().coerceIn(0, bmpWidth - 1)
    val relativeY = ((screenOffset.y - offsetY) / scale).toInt().coerceIn(0, bmpHeight - 1)
    return Pair(relativeX, relativeY)
}
