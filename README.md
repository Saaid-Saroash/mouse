# Remote Mouse (Android)

An AMOLED-friendly Android remote mouse/keyboard with two independent connection modes:

- **Bluetooth:** the phone registers as a Bluetooth Classic HID combo device (mouse + keyboard). Pair the phone from the laptop, then use the controls directly. The laptop does not need the Python server in this mode.
- **Wi-Fi:** the app connects to the existing laptop-side `mouse.py` server over WebSocket at `ws://LAPTOP-IP:8080/ws`. The server must already be running; the app does not start it.

The app reuses the event protocol already implemented by `mouse.py` (`move`, `click`, `scroll`, `text`, `key`, `set_sensitivity`). The laptop server source is included unchanged in `laptop_server/mouse.py`.

## Build the APK

Requirements: Android Studio (or Android SDK + Gradle), JDK 17, Android SDK Platform 35.

1. Open this `MouseLinkAndroid` folder in Android Studio.
2. Allow Gradle sync to finish.
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The debug APK is created at:

   `app/build/outputs/apk/debug/app-debug.apk`

Alternatively, from this folder run:

```bash
gradle --no-daemon assembleDebug
```

The APK is a debug-signed sideloadable APK. A release APK for distribution should be signed with your own keystore.

## Wi-Fi mode: laptop setup

1. Connect the laptop and phone to the same Wi-Fi network.
2. On the laptop, install the server dependencies:

   ```powershell
   py -m pip install aiohttp pynput
   ```

3. Start the server from the `laptop_server` folder:

   ```powershell
   py mouse.py
   ```

4. Keep that terminal open. The server prints an address similar to `http://192.168.1.20:8080`.
5. In the Android app, choose **Wi-Fi**, enter `192.168.1.20:8080` (use the actual address printed by your laptop), then tap **Connect**.
6. If Windows Firewall asks, allow Python on your private network. If the app cannot connect, allow inbound TCP port 8080 on the laptop's Private network profile. In an elevated PowerShell window:

   ```powershell
   New-NetFirewallRule -DisplayName "Remote Mouse 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
   ```

Do not use `127.0.0.1` or `localhost` in the phone app; those addresses refer to the phone itself, not the laptop.

## Bluetooth mode: pairing

1. Turn Bluetooth on for both devices.
2. In the app choose **Bluetooth** and grant the Nearby devices permissions.
3. Tap **Make discoverable**.
4. On Windows open **Settings → Bluetooth & devices → Add device → Bluetooth** and select **Remote Mouse**.
5. Complete pairing. If the connection does not start automatically, return to the app and tap **Choose laptop**, then select the paired Windows laptop.
6. Use the touch surface, click buttons, scroll controls, sensitivity controls, and keyboard field.

Bluetooth HID is a native Android platform profile introduced in Android 9 (API 28). The app requires Android 9 or newer. Some Android devices/ROMs do not expose the HID Device profile to third-party apps; on such a phone the app will report that HID is unavailable. This is a platform/firmware limitation, not a Wi-Fi server issue. Bluetooth keyboard input uses a US-layout HID map; unsupported Unicode characters are skipped.

## Controls

- Drag in the touch surface to move the pointer; tap for left click; two-finger touch for right click.
- Use the right-side rail or its arrow buttons to scroll.
- Use Left Click / Right Click for explicit clicks.
- Adjust sensitivity with − / +.
- Focus the keyboard field and type; Enter sends an Enter key.

## Notes

- Minimum Android version: Android 9 (API 28).
- Target SDK: 35.
- Wi-Fi uses cleartext local-network WebSocket (`ws://`) because the existing Python server does not provide TLS.
- The laptop server runs with the same security context as the user who launched it. Only use this app on a network you trust.
