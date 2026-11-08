package com.mouselink.remote

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Bluetooth Classic HID device: exposes this phone to the laptop as a real mouse + keyboard. */
class BluetoothHidController(
    private val activity: Activity,
    private val status: (String, Boolean) -> Unit
) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var connected = false
    private var activeDevice: BluetoothDevice? = null
    private var proxyRequested = false
    private var moveResidualX = 0f
    private var moveResidualY = 0f
    private var scrollResidual = 0f

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            if (isRegistered) {
                // Android may report the host that was already attached when
                // the HID app registered. Keep that host as the active target.
                if (pluggedDevice != null) {
                    activeDevice = pluggedDevice
                    connected = true
                    val name = try { pluggedDevice.name?.takeIf { it.isNotBlank() } ?: "LAPTOP" }
                        catch (_: SecurityException) { "LAPTOP" }
                    status("CONNECTED · $name", true)
                } else {
                    status("READY · PAIR FROM LAPTOP", false)
                }
            } else if (!connected) {
                status("HID REGISTRATION STOPPED", false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                activeDevice = device
                connected = true
                val name = try { device?.name?.takeIf { it.isNotBlank() } ?: "LAPTOP" }
                    catch (_: SecurityException) { "LAPTOP" }
                status("CONNECTED · $name", true)
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device == null || activeDevice == null || device.address == activeDevice?.address) {
                    connected = false
                    activeDevice = null
                }
                status("PAIRED · WAITING FOR LAPTOP", false)
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                status("CONNECTING TO LAPTOP…", false)
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as? BluetoothHidDevice
            val device = hid ?: run {
                status("BLUETOOTH HID NOT AVAILABLE", false)
                return
            }
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Remote Mouse",
                    "Bluetooth mouse and keyboard",
                    "MouseLink",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HID_REPORT_DESCRIPTOR
                )
                val executor = Executor { command -> mainHandler.post(command) }
                val requested = device.registerApp(sdp, null, null, executor, callback)
                if (!requested) status("HID REGISTRATION REQUEST FAILED", false)
                else status("STARTING BLUETOOTH HID…", false)
            } catch (e: SecurityException) {
                status("ALLOW NEARBY DEVICES PERMISSION", false)
            } catch (e: Exception) {
                status("BLUETOOTH HID UNAVAILABLE", false)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hid = null
                registered = false
                connected = false
                activeDevice = null
                status("BLUETOOTH SERVICE DISCONNECTED", false)
            }
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            status("ANDROID 9 OR NEWER REQUIRED", false)
            return
        }
        if (adapter == null) {
            status("BLUETOOTH NOT SUPPORTED", false)
            return
        }
        try {
            if (!adapter.isEnabled) {
                status("TURN BLUETOOTH ON FIRST", false)
                return
            }
            if (registered || proxyRequested) return
            proxyRequested = true
            val accepted = adapter.getProfileProxy(activity, serviceListener, BluetoothProfile.HID_DEVICE)
            if (!accepted) {
                proxyRequested = false
                status("THIS PHONE DOES NOT EXPOSE HID DEVICE", false)
            } else {
                status("STARTING BLUETOOTH HID…", false)
            }
        } catch (e: SecurityException) {
            proxyRequested = false
            status("ALLOW NEARBY DEVICES PERMISSION", false)
        } catch (e: Exception) {
            proxyRequested = false
            status("BLUETOOTH HID NOT SUPPORTED BY THIS PHONE", false)
        }
    }

    fun pairedDevices(): List<BluetoothDevice> {
        return try {
            adapter?.bondedDevices?.toList()?.sortedBy {
                try { it.name ?: it.address } catch (_: SecurityException) { it.address }
            } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        val profile = hid
        if (profile == null || !registered) {
            status("WAIT FOR BLUETOOTH HID TO START", false)
            return
        }
        try {
            activeDevice = device
            connected = false
            status("CONNECTING TO LAPTOP…", false)
            val started = profile.connect(device)
            if (!started) status("CONNECT REQUEST NOT ACCEPTED", false)
        } catch (_: SecurityException) {
            status("BLUETOOTH PERMISSION REQUIRED", false)
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
        // Keep the press and release as distinct HID reports on slower stacks.
        mainHandler.postDelayed({ if (connected) sendMouse(0, 0, 0, 0) }, 12L)
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
            else -> Unit
        }
    }

    fun typeText(value: String) {
        if (!connected || value.isEmpty()) return
        // Schedule short, ordered key reports so longer pasted/typed strings aren't flooded at once.
        value.forEachIndexed { index, character ->
            val pair = toHidKey(character) ?: return@forEachIndexed
            mainHandler.postDelayed({
                if (connected) pressKey(pair.first, pair.second)
            }, index * 12L)
        }
    }

    private fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = activeDevice ?: return
        try {
            val accepted = hid?.sendReport(
                device,
                REPORT_MOUSE,
                byteArrayOf(
                    buttons.toByte(),
                    dx.coerceIn(-127, 127).toByte(),
                    dy.coerceIn(-127, 127).toByte(),
                    wheel.coerceIn(-127, 127).toByte()
                )
            ) == true
            if (!accepted) status("MOUSE REPORT REJECTED · RECONNECT BLUETOOTH", false)
        } catch (_: SecurityException) {
            status("BLUETOOTH PERMISSION REQUIRED", false)
        }
    }

    private fun sendKeyboard(modifier: Int, keyCode: Int) {
        val device = activeDevice ?: return
        val report = ByteArray(8)
        report[0] = modifier.toByte()
        report[2] = keyCode.toByte()
        try {
            val accepted = hid?.sendReport(device, REPORT_KEYBOARD, report) == true
            if (!accepted) status("KEYBOARD REPORT REJECTED · RECONNECT BLUETOOTH", false)
        } catch (_: SecurityException) {
            status("BLUETOOTH PERMISSION REQUIRED", false)
        }
    }

    private fun pressKey(modifier: Int, keyCode: Int) {
        sendKeyboard(modifier, keyCode)
        mainHandler.postDelayed({ if (connected) sendKeyboard(0, 0) }, 12L)
    }

    private fun toHidKey(character: Char): Pair<Int, Int>? {
        val c = character
        if (c in 'a'..'z') return 0 to (0x04 + (c - 'a'))
        if (c in 'A'..'Z') return 0x02 to (0x04 + (c - 'A'))
        if (c in '1'..'9') return 0 to (0x1E + (c - '1'))
        if (c == '0') return 0 to 0x27
        if (c == ' ') return 0 to 0x2C
        if (c == '\n' || c == '\r') return 0 to 0x28
        val normal = mapOf(
            '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
            ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
        )
        normal[c]?.let { return 0 to it }
        val shifted = mapOf(
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
            '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
            ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38
        )
        shifted[c]?.let { return 0x02 to it }
        return null // Bluetooth HID keyboard reports are US-layout; unsupported Unicode is skipped.
    }

    fun close() {
        try {
            hid?.unregisterApp()
            adapter?.let { current -> hid?.let { current.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        } catch (_: Exception) {
            // The system also cleans up the HID registration when the app process exits.
        }
        hid = null
        connected = false
        registered = false
        proxyRequested = false
        activeDevice = null
    }

    companion object {
        private const val REPORT_MOUSE = 1
        private const val REPORT_KEYBOARD = 2

        // HID 1.11: 4-byte relative mouse report plus 8-byte boot-style keyboard report.
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
