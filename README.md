# Remote Mouse (Android)

An Android mouse/keyboard app that keeps the original `mouse.py` touchpad arrangement: the compact top bar, keyboard button, sensitivity control, large touch surface, narrow right-side scroll rail, and Left/Right buttons. The only new control in that layout is a small **Wi-Fi / BT** mode switch. The screen is rendered with a pure-black AMOLED base and restrained teal accents.

## Connection modes

- **Wi-Fi:** the laptop's `mouse.py` server must already be running. The app automatically discovers the laptop and its selected TCP port on the same local Wi-Fi network. No IP address or port entry is required.
- **Bluetooth:** the Android phone registers as a native Bluetooth Classic HID mouse + keyboard. The Python server is not needed in this mode.

## Build the APK with GitHub Actions

1. Upload the contents of this project folder to the root of your `remote-mouse` GitHub repository.
2. Open **Actions → Build Remote Mouse Android APK → Run workflow**.
3. When the workflow succeeds, download the `Remote-Mouse-debug-apk` artifact.
4. Extract it and install `app-debug.apk` on the Android phone.

The project uses JDK 17, Gradle 8.11.1, Android SDK Platform 35, and a debug signing key for sideloading.

## Laptop server setup (Wi-Fi mode)

1. Connect the phone and laptop to the same Wi-Fi network. Guest networks or access-point client isolation may block discovery.
2. Install the Python dependencies on the laptop:

   ```powershell
   py -m pip install -r requirements.txt
   ```

   Run that command from the `laptop_server` folder. If needed, install directly with `py -m pip install aiohttp pynput`.

3. Start the server and leave its terminal open:

   ```powershell
   py mouse.py
   ```

4. Open Remote Mouse on the phone and tap **Wi-Fi**. It broadcasts a discovery request and connects to the laptop automatically.

### How automatic port selection works

The laptop server tries TCP ports **8080–8090** and selects the first one that is free. If all those ports are occupied, it asks the operating system for an available port. The selected port is announced over the local network using UDP discovery port **39567**. The app receives the laptop's IP and selected port automatically; there is no port field in the app.

On Windows, allow Python through Windows Defender Firewall on **Private networks**. If automatic discovery is blocked, run the following in an elevated PowerShell window:

```powershell
New-NetFirewallRule -DisplayName "Remote Mouse discovery" -Direction Inbound -Protocol UDP -LocalPort 39567 -Action Allow -Profile Private
New-NetFirewallRule -DisplayName "Remote Mouse TCP" -Direction Inbound -Protocol TCP -LocalPort 8080-8090 -Action Allow -Profile Private
```

If all ports 8080–8090 are occupied and the server falls back to an OS-assigned port, make sure Windows Firewall allows the Python server application itself on Private networks.

## Bluetooth mode

1. Turn Bluetooth on for the phone and laptop.
2. Tap **BT** in the app and grant the Nearby devices permissions.
3. The app starts its HID profile and requests discoverability. On Windows, open **Settings → Bluetooth & devices → Add device → Bluetooth** and pair with **Remote Mouse**.
4. Long-press the **BT** mode button to choose an already-paired laptop manually if Windows does not connect automatically.

Bluetooth HID requires Android 9 (API 28) or newer, and the phone's Bluetooth firmware must expose the HID Device profile to third-party apps. Some devices do not expose this profile. Bluetooth keyboard reports use a US-layout HID map; unsupported Unicode characters may be skipped.

## Controls

- Drag on the touch surface to move the pointer; tap for left click; two-finger touch for right click.
- Use the right-side scroll rail or the ▲ / ▼ buttons to scroll.
- Use the bottom Left / Right buttons for explicit clicks.
- Adjust pointer sensitivity with − / +.
- Tap the keyboard icon to open the phone keyboard; text and Enter are forwarded to the laptop.

## Technical notes

- Minimum Android version: Android 9 (API 28).
- Wi-Fi uses a local WebSocket (`ws://`) and UDP broadcast discovery. Keep `mouse.py` running on the laptop while using Wi-Fi mode.
- Bluetooth mode is direct HID and does not use the laptop Python server.
- The laptop server runs with the permissions of the Windows user who launched it. Use it only on a network you trust.
