package com.mouselink.remote

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Android Bluetooth Classic HID device: phone appears to the laptop as a mouse + keyboard. */
class BluetoothHidController(
    private val activity: Activity,
    private val status: (String, Boolean) -> Unit,
    private val onRegistered: () -> Unit
) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val profileTimeout = Runnable {
        if (hid == null && proxyRequested) {
            proxyRequested = false
            status("HID PROFILE DID NOT START · PHONE FIRMWARE MAY NOT SUPPORT HID DEVICE", false)
        }
    }
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var registered = false
    @Volatile private var connected = false
    @Volatile private var activeDevice: BluetoothDevice? = null
    private var proxyRequested = false
    private var pendingDevice: BluetoothDevice? = null
    private var moveResidualX = 0f
    private var moveResidualY = 0f
    private var scrollResidual = 0f

    private fun deviceLabel(device: BluetoothDevice?): String {
        if (device == null) return "LAPTOP"
        return try { device.name?.takeIf { it.isNotBlank() } ?: "LAPTOP" }
        catch (_: SecurityException) { "LAPTOP" }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (!isRegistered) {
                connected = false
                activeDevice = null
                status("HID APP UNREGISTERED · RETURN TO APP TO RETRY", false)
                return
            }

            if (pluggedDevice != null) {
                activeDevice = pluggedDevice
                connected = true
                pendingDevice = null
                status("CONNECTED · ${deviceLabel(pluggedDevice)}", true)
                return
            }

            // The host may already be connected when Android confirms registration.
            val existing = try { hid?.connectedDevices?.firstOrNull() } catch (_: SecurityException) { null }
            if (existing != null) {
                activeDevice = existing
                connected = true
                pendingDevice = null
                status("CONNECTED · ${deviceLabel(existing)}", true)
                return
            }

            connected = false
            status("HID READY · PAIR OR SELECT LAPTOP", false)
            val queued = pendingDevice
            if (queued != null) connectRegistered(queued) else onRegistered()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    activeDevice = device
                    connected = device != null
                    if (connected) {
                        pendingDevice = null
                        status("CONNECTED · ${deviceLabel(device)}", true)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> status("CONNECTING TO ${deviceLabel(device)}…", false)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == null || activeDevice == null || device.address == activeDevice?.address) {
                        connected = false
                        activeDevice = null
                    }
                    status("PAIRED · TAP BT DEVICE TO RETRY", false)
                }
                BluetoothProfile.STATE_DISCONNECTING -> status("DISCONNECTING…", false)
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val device = proxy as? BluetoothHidDevice
            if (device == null) {
                hid = null
                proxyRequested = false
                status("HID DEVICE PROFILE NOT AVAILABLE ON THIS PHONE", false)
                return
            }
            mainHandler.removeCallbacks(profileTimeout)
            hid = device
            registerHidApp(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                registered = false
                connected = false
                activeDevice = null
                mainHandler.removeCallbacks(profileTimeout)
                proxyRequested = false
                status("BLUETOOTH HID SERVICE LOST · TAP BT TO RETRY", false)
            }
        }
    }

    private fun registerHidApp(device: BluetoothHidDevice) {
        if (registered) return
        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Remote Mouse",
                "Bluetooth mouse and keyboard",
                "MouseLink",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HID_REPORT_DESCRIPTOR
            )
            val executor = Executor { command -> mainHandler.post(command) }
            val requestAccepted = device.registerApp(sdp, null, null, executor, callback)
            if (!requestAccepted) {
                registered = false
                status("ANDROID REJECTED HID REGISTRATION", false)
            } else {
                status("REGISTERING BLUETOOTH HID…", false)
            }
        } catch (e: SecurityException) {
            status("BLUETOOTH CONNECT PERMISSION REQUIRED", false)
        } catch (e: Exception) {
            status("HID REGISTRATION ERROR: ${e.javaClass.simpleName}", false)
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status("ANDROID 9 OR NEWER REQUIRED", false)
            return
        }
        val bt = adapter
        if (bt == null) {
            status("BLUETOOTH NOT SUPPORTED", false)
            return
        }
        try {
            if (!bt.isEnabled) {
                status("TURN BLUETOOTH ON FIRST", false)
                return
            }
            if (registered) {
                status("HID READY", false)
                return
            }
            val existingProfile = hid
            if (existingProfile != null) {
                registerHidApp(existingProfile)
                return
            }
            if (proxyRequested) return
            proxyRequested = true
            val accepted = bt.getProfileProxy(activity, serviceListener, BluetoothProfile.HID_DEVICE)
            if (!accepted) {
                proxyRequested = false
                status("HID DEVICE PROFILE NOT SUPPORTED BY THIS PHONE", false)
            } else {
                status("OPENING BLUETOOTH HID PROFILE…", false)
                mainHandler.removeCallbacks(profileTimeout)
                mainHandler.postDelayed(profileTimeout, 12000L)
            }
        } catch (e: SecurityException) {
            proxyRequested = false
            status("ALLOW NEARBY DEVICES PERMISSION", false)
        } catch (e: Exception) {
            proxyRequested = false
            status("BLUETOOTH HID ERROR: ${e.javaClass.simpleName}", false)
        }
    }

    fun isRegistered(): Boolean = registered
    fun isConnected(): Boolean = connected

    fun pairedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList()?.sortedBy { deviceLabel(it) } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }

    /** Queue the selected bonded host if Android hasn't completed HID registration yet. */
    fun connect(device: BluetoothDevice) {
        pendingDevice = device
        if (!registered || hid == null) {
            status("LAPTOP SELECTED · WAITING FOR HID REGISTRATION", false)
            start()
            return
        }
        connectRegistered(device)
    }

    private fun connectRegistered(device: BluetoothDevice) {
        val profile = hid
        if (profile == null || !registered) {
            pendingDevice = device
            status("HID NOT READY · SELECT LAPTOP AGAIN", false)
            return
        }
        try {
            val previous = activeDevice
            if (connected && previous != null && !previous.address.equals(device.address, ignoreCase = true)) {
                status("SWITCHING BLUETOOTH HOST…", false)
                pendingDevice = device
                connected = false
                activeDevice = null
                profile.disconnect(previous)
                mainHandler.postDelayed({
                    if (!connected && pendingDevice?.address == device.address) connectRegistered(device)
                }, 700L)
                return
            }
            when (profile.getConnectionState(device)) {
                BluetoothProfile.STATE_CONNECTED -> {
                    activeDevice = device
                    connected = true
                    pendingDevice = null
                    status("CONNECTED · ${deviceLabel(device)}", true)
                    return
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    pendingDevice = device
                    status("CONNECTING TO ${deviceLabel(device)}…", false)
                    return
                }
            }
            activeDevice = device
            connected = false
            pendingDevice = device
            status("REQUESTING HID CONNECTION · ${deviceLabel(device)}", false)
            val accepted = profile.connect(device)
            if (!accepted) status("HID CONNECT REJECTED · REMOVE OLD PAIRING AND PAIR AGAIN", false)
            // A true return means the request was sent, not that the host connected.
            // onConnectionStateChanged is the authority for the connected state.
        } catch (_: SecurityException) {
            status("BLUETOOTH CONNECT PERMISSION REQUIRED", false)
        } catch (e: Exception) {
            status("HID CONNECT ERROR: ${e.javaClass.simpleName}", false)
        }
    }

    fun move(dx: Float, dy: Float) {
        if (!connected) return
        val rawX = dx + moveResidualX
        val rawY = dy + moveResidualY
        val sendX = rawX.toInt().coerceIn(-127, 127)
        val sendY = rawY.toInt().coerceIn(-127, 127)
        moveResidualX = rawX - sendX
        moveResidualY = rawY - sendY
        if (sendX != 0 || sendY != 0) sendMouse(0, sendX, sendY, 0)
    }

    fun click(right: Boolean) {
        if (!connected) return
        val mask = if (right) 0x02 else 0x01
        sendMouse(mask, 0, 0, 0)
        mainHandler.postDelayed({ if (connected) sendMouse(0, 0, 0, 0) }, 20L)
    }

    fun scroll(amount: Float) {
        if (!connected) return
        val raw = amount + scrollResidual
        val wheel = raw.toInt().coerceIn(-127, 127)
        scrollResidual = raw - wheel
        if (wheel != 0) sendMouse(0, 0, 0, wheel)
    }

    fun key(key: String) {
        if (!connected) return
        when (key) {
            "BACKSPACE" -> pressKey(0, 0x2A)
            "ENTER" -> pressKey(0, 0x28)
        }
    }

    fun typeText(value: String) {
        if (!connected || value.isEmpty()) return
        value.forEachIndexed { index, character ->
            val pair = toHidKey(character) ?: return@forEachIndexed
            mainHandler.postDelayed({ if (connected) pressKey(pair.first, pair.second) }, index * 18L)
        }
    }

    private fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = activeDevice ?: return
        try {
            val accepted = hid?.sendReport(
                device, REPORT_MOUSE,
                byteArrayOf(buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte())
            ) == true
            if (!accepted) status("MOUSE REPORT NOT ACCEPTED BY HOST", false)
        } catch (_: SecurityException) { status("BLUETOOTH PERMISSION REQUIRED", false) }
    }

    private fun sendKeyboard(modifier: Int, keyCode: Int) {
        val device = activeDevice ?: return
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        report[2] = keyCode.toByte()
        try {
            val accepted = hid?.sendReport(device, REPORT_KEYBOARD, report) == true
            if (!accepted) status("KEYBOARD REPORT NOT ACCEPTED BY HOST", false)
        } catch (_: SecurityException) { status("BLUETOOTH PERMISSION REQUIRED", false) }
    }

    private fun pressKey(modifier: Int, keyCode: Int) {
        sendKeyboard(modifier, keyCode)
        mainHandler.postDelayed({ if (connected) sendKeyboard(0, 0) }, 18L)
    }

    private fun toHidKey(character: Char): Pair<Int, Int>? {
        if (character in 'a'..'z') return 0 to (0x04 + (character - 'a'))
        if (character in 'A'..'Z') return 0x02 to (0x04 + (character - 'A'))
        if (character in '1'..'9') return 0 to (0x1E + (character - '1'))
        if (character == '0') return 0 to 0x27
        if (character == ' ') return 0 to 0x2C
        if (character == '\n' || character == '\r') return 0 to 0x28
        val normal = mapOf('-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31, ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38)
        normal[character]?.let { return 0 to it }
        val shifted = mapOf('!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22, '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27, '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31, ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38)
        shifted[character]?.let { return 0x02 to it }
        return null
    }

    fun close() {
        mainHandler.removeCallbacks(profileTimeout)
        try {
            if (registered) hid?.unregisterApp()
            hid?.let { proxy -> adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
        } catch (_: Exception) { }
        hid = null
        connected = false
        registered = false
        proxyRequested = false
        activeDevice = null
        pendingDevice = null
    }

    companion object {
        private const val REPORT_MOUSE = 1
        private const val REPORT_KEYBOARD = 2
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x09, 0x01, 0xA1.toByte(), 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
            0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(),
            0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
            0xC0.toByte(), 0xC0.toByte(),
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02,
            0x05, 0x07, 0x19, 0x00, 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x75, 0x08, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
            0x75, 0x08, 0x95.toByte(), 0x06, 0x15, 0x00, 0x25, 0x65,
            0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte()
        )
    }
}
