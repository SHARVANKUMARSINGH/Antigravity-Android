# Antigravity Linux — Comprehensive Troubleshooting & Diagnostic Guide

This guide provides technical explanations, root causes, diagnostics, and recovery procedures for issues encountered when installing, running, and managing the Arch Linux ARM64 environment and Google Antigravity Desktop on Android.

---

## 1. Storage & Pre-Flight Checks

### Issue 1.1: "Insufficient Storage: Less than 2 GB available"
* **Symptom**: Tapping **Initialize Arch Linux** fails immediately with an error dialog stating insufficient storage.
* **Root Cause**: The complete Arch Linux ARM64 base userspace plus essential graphical and development packages requires a minimum of 2.0 GB of free internal storage.
* **Resolution**:
  1. Free up at least 2.5 GB of internal storage in Android Settings -> Storage.
  2. The application checks `StatFs(context.filesDir)` directly. Ensure the app is installed on internal flash storage, not an unformatted or read-only SD card.

---

## 2. Architecture & Hardware Compatibility

### Issue 2.1: "Unsupported Architecture: Antigravity Linux strictly requires ARM64 (aarch64)"
* **Symptom**: The architecture badge in the header displays red, and initialization is refused.
* **Root Cause**: The Android device is running a 32-bit ARM kernel (`armeabi-v7a`), an x86/x86_64 emulator, or a 32-bit userspace on a 64-bit chip.
* **Technical Detail**: The glibc dynamic linker `/lib/ld-linux-aarch64.so.1` and all compiled Arch Linux binaries require the 64-bit ARMv8-A instruction set.
* **Verification**: Open Android Terminal or `adb shell` and run `uname -m`. The output must be `aarch64`.

---

## 3. Network & Rootfs Installation

### Issue 3.1: "Failed to download Arch Linux ARM64 root filesystem archive"
* **Symptom**: Download stops before reaching 100%, or connection times out.
* **Root Cause**: Network instability, firewall restrictions on port 80/443, or rate-limiting by the primary Arch Linux ARM mirror (`os.archlinuxarm.org`).
* **Resolution**:
  1. The app automatically attempts fallback to the high-speed Danish mirror (`mirrors.dotsrc.org`).
  2. Verify your device has an active Wi-Fi connection with unrestricted internet access.
  3. Ensure no local VPN or firewall application (like NetGuard or AdGuard) is blocking Android background downloads.

### Issue 3.2: "tar extraction failed"
* **Symptom**: Extraction phase halts or reports non-zero exit code.
* **Root Cause**: Corrupted partial download archive or interrupted storage write.
* **Resolution**: Delete `/data/data/com.antigravity.linux/cache/ArchLinuxARM-aarch64.tar.gz` or tap **Delete Arch Linux Environment** in Settings and retry the download.

---

## 4. PRoot & Virtualization Engine

### Issue 4.1: "Execution verification failed: /bin/bash not found or bad ELF interpreter"
* **Symptom**: Rootfs is extracted, but running `uname -m` fails.
* **Root Cause**: PRoot binary permissions or missing dynamic linker bind mount.
* **Technical Detail**: PRoot intercepts system calls using `ptrace`. If Android kernel security policy restricts `PTRACE_ATTACH` on child threads, PRoot requires the `-0` flag (fake root UID) and explicit bind mounts for `/dev`, `/proc`, and `/sys`.
* **Resolution**: The app's `CommandExecutor` automatically applies `-0 -b /dev -b /proc -b /sys`. Ensure your Android version does not enforce a custom restricted seccomp profile that disallows `ptrace`.

---

## 5. Desktop Session & Display Server (TigerVNC / X11)

### Issue 5.1: "Desktop failed to start. Port 5901 connection refused."
* **Symptom**: Tapping **Start Linux Desktop** transitions to "Starting..." and then "Error".
* **Root Cause**: Stale X11 lock files from a previous ungraceful Android process termination, or missing display packages.
* **Diagnosis**: Open **Terminal** inside the app and execute:
  ```bash
  rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
  /usr/local/bin/start-desktop
  ```
* **Resolution**:
  1. The `start-desktop` script automatically attempts cleanup of `/tmp/.X1-lock`.
  2. Ensure the display server package is installed inside Arch Linux:
     ```bash
     pacman -Sy tigervnc xfce4 xfce4-terminal
     ```

### Issue 5.2: Black screen or disconnected VNC canvas
* **Symptom**: `DesktopScreen` connects but displays a static black background with no window manager panel.
* **Root Cause**: Window manager (`xfce4-session` or `openbox`) failed to launch or crashed.
* **Resolution**: Verify that the window manager binary exists. In Terminal:
  ```bash
  which startxfce4 || which openbox-session
  ```

---

## 6. Real Google Antigravity Desktop

### Issue 6.1: "Antigravity binary not found at /opt/antigravity/antigravity"
* **Symptom**: Diagnostics report `Binary Present: No`.
* **Root Cause**: The official Google Antigravity Desktop package has not yet been extracted into `/opt/antigravity`.
* **Installation Procedure**:
  1. Download the official Linux ARM64 tarball from `https://antigravity.google/download` or build the AUR package:
     ```bash
     # Inside the app's Linux Terminal:
     cd /tmp
     curl -LO https://antigravity.google/download/Antigravity-linux-aarch64.tar.gz
     tar -xzf Antigravity-linux-aarch64.tar.gz
     mv antigravity /opt/antigravity
     chmod +x /opt/antigravity/antigravity
     ```
  2. Return to the **Dashboard** and tap **Launch Antigravity**.

### Issue 6.2: "The setuid sandbox provides API for new PID namespaces... --no-sandbox flag required"
* **Symptom**: Antigravity Desktop crashes immediately upon launch; log shows Chromium sandbox failure.
* **Root Cause**: Google Antigravity Desktop is built on Electron/Chromium. Chromium requires Linux user namespaces (`CLONE_NEWUSER`) for its sandbox, which are forbidden for unprivileged apps on Android.
* **Resolution**: The launcher script `/usr/local/bin/launch-antigravity` includes `--no-sandbox --disable-gpu-sandbox`. Always launch via the app's button or script, never by invoking the raw binary directly without flags.

### Issue 6.3: "Missing shared libraries: libgtk-3.so.0, libnss3.so, etc."
* **Symptom**: Diagnostics report missing libraries under `opt/antigravity/antigravity`.
* **Resolution**: In Terminal, install the missing desktop and browser dependencies:
  ```bash
  pacman -Sy gtk3 nss alsa-lib libx11 libxcomposite libxdamage libxext libxfixes libxrandr libxtst mesa
  ```

---

## 7. Keyboard & Mouse Input

### Issue 7.1: Software keyboard does not input text into Linux apps
* **Symptom**: Tapping keys on Gboard / Samsung Keyboard produces no characters in terminal or editor.
* **Root Cause**: Android Input Method Editors (IME) often deliver composed strings (committed text) rather than physical key codes.
* **Resolution**: Antigravity Linux uses an integrated `BasicTextField` bridge that intercepts text mutations and translates unicode characters directly into standard X11 keysyms via `KeySymbols.charToKeysym()`. Ensure the keyboard icon in the floating toolbar is toggled active.

### Issue 7.2: Mouse clicks registered off-target
* **Symptom**: Tapping a button clicks a different position on the Linux desktop.
* **Root Cause**: Display scaling mismatch between the Android screen aspect ratio and the desktop framebuffer (e.g. 1280x720).
* **Resolution**: The application implements coordinate transformation:
  $$\text{desktopX} = \frac{\text{touchX} - \text{offsetX}}{\text{scale}}$$
  Use the **Scaling Mode** button in the top bar to toggle between **Fit to Screen** and **1:1 Scale** with panning.

---

## 8. Bidirectional Clipboard

### Issue 8.1: "Android clipboard is empty" or Linux clipboard not updating
* **Symptom**: Tapping `A→L` displays a warning, or pasting inside Linux yields old text.
* **Root Cause**:
  - Android 10+ restricts background clipboard reading unless the application is in the foreground.
  - X11 applications must have `xclip` or `xsel` installed to communicate clipboard state with the X server selection buffer.
* **Resolution**:
  1. Ensure text is copied in Android immediately before switching to Antigravity Linux.
  2. Tap the explicit `A→L` (Android to Linux) button in the toolbar.
  3. Inside Arch Linux, verify `xclip` is installed:
     ```bash
     pacman -Sy xclip
     ```

---

## 9. Background Execution & Android Process Lifetime

### Issue 9.1: Linux environment terminates when switching apps
* **Symptom**: Returning to Antigravity Linux after 5 minutes shows the runtime as "Stopped".
* **Root Cause**: Android's Low Memory Killer (LMK) or manufacturer aggressive battery optimization (e.g., Samsung OneUI, Xiaomi MIUI) terminated the process.
* **Resolution**:
  1. Enable **Keep Linux Running in Background** in the app Settings. This starts `LinuxForegroundService` with a persistent notification and holds an active `PARTIAL_WAKE_LOCK`.
  2. In Android system settings -> Apps -> Antigravity Linux -> Battery, select **Unrestricted** (Don't optimize).

---

## 10. Complete Environment Reset

### Procedure to cleanly reinstall from scratch:
1. In the app, navigate to **Settings**.
2. Scroll to the **Danger Zone** at the bottom.
3. Tap **Delete Arch Linux Environment** and confirm.
4. All files in `/data/data/com.antigravity.linux/files/archlinux` are securely erased.
5. Return to **Dashboard** and tap **Initialize Arch Linux** to begin a fresh, verified installation.
