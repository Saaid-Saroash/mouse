package com.mouselink.remote

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.wifi.WifiManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity() {
    private val bg = Color.rgb(0, 0, 0)
    private val surface = Color.rgb(3, 6, 7)
    private val surface2 = Color.rgb(8, 12, 14)
    private val border = Color.rgb(35, 45, 49)
    private val accent = Color.rgb(20, 184, 166)
    private val textMain = Color.rgb(239, 246, 248)
    private val textMuted = Color.rgb(139, 157, 164)

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var sensitivityText: TextView
    private lateinit var wifiModeButton: Button
    private lateinit var bluetoothModeButton: Button
    private lateinit var keyboardButton: Button
    private lateinit var keyboardInput: EditText
    @Volatile private var wifiDiscoveryRunning = false
    private var serverAuthority: String? = null
    private val discoveryPort = 39567

    private var mode = Mode.WIFI
    private var sensitivity = 1.1f
    private var socket: WebSocket? = null
    @Volatile private var socketOpen = false
    @Volatile private var bluetoothOpen = false
    private var lastConnectionHintAt = 0L
    private var wifiAutoReconnect = false
    private val wifiHandler = Handler(Looper.getMainLooper())
    private val wifiReconnectTask = Runnable {
        if (mode == Mode.WIFI && wifiAutoReconnect && !socketOpen) discoverWifiServer(showFailureToast = false)
    }
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var bluetoothController: BluetoothHidController? = null
    private val bluetooth: BluetoothHidController
        get() = bluetoothController ?: BluetoothHidController(this) { message, connected ->
            runOnUiThread {
                bluetoothOpen = connected
                if (mode == Mode.BLUETOOTH) setStatus(message, connected)
            }
        }.also { bluetoothController = it }

    enum class Mode { WIFI, BLUETOOTH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    private fun buildUi() {
        // Match the original mouse.py layout: top controls, a single flexible
        // touch surface with a slim scroll rail, and two bottom click buttons.
        // All dimensions are in dp and the content area is inset-aware so
        // Android 15 edge-to-edge does not cover the controls with system bars.
        val screenWidth = resources.configuration.screenWidthDp
        val compact = screenWidth < 360
        val basePadding = if (compact) dp(7) else dp(9)
        val railWidth = if (compact) dp(46) else dp(52)
        val topControlHeight = if (compact) dp(38) else dp(40)
        val bottomButtonHeight = if (resources.configuration.screenHeightDp < 620) dp(48) else dp(54)

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(basePadding, basePadding, basePadding, basePadding)
            background = rounded(Color.BLACK, 22f, Color.rgb(30, 40, 44))
            clipToPadding = true
            clipChildren = true
            fitsSystemWindows = false
        }

        val topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leftCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = rounded(Color.rgb(238, 91, 91), 100f)
            contentDescription = "Connection status"
            isClickable = true
            setOnClickListener { toast(statusText.text.toString()) }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(6) }
        }
        leftCluster.addView(statusDot)

        wifiModeButton = actionButton("Wi-Fi", true).apply {
            textSize = if (compact) 10f else 11f
            contentDescription = "Wi-Fi mode — automatically discover the laptop server. Tap again to retry."
            setOnClickListener { switchMode(Mode.WIFI) }
        }
        bluetoothModeButton = actionButton("BT", false).apply {
            textSize = if (compact) 10f else 11f
            contentDescription = "Bluetooth HID mode. Long press to select a paired laptop."
            setOnClickListener { switchMode(Mode.BLUETOOTH) }
            setOnLongClickListener { choosePairedLaptop(); true }
        }
        leftCluster.addView(wifiModeButton, LinearLayout.LayoutParams(if (compact) dp(42) else dp(46), topControlHeight).apply { rightMargin = dp(3) })
        leftCluster.addView(bluetoothModeButton, LinearLayout.LayoutParams(if (compact) dp(34) else dp(38), topControlHeight).apply { rightMargin = dp(6) })

        keyboardButton = actionButton("⌨️", false).apply {
            textSize = 18f
            contentDescription = "Open phone keyboard"
            setOnClickListener { openPhoneKeyboard() }
        }
        leftCluster.addView(keyboardButton, LinearLayout.LayoutParams(if (compact) dp(36) else dp(40), topControlHeight))
        topbar.addView(leftCluster, LinearLayout.LayoutParams(0, -2, 1f))

        val sensBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(5, 8, 9), 15f, Color.rgb(35, 45, 49))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val sensButtonWidth = if (compact) dp(30) else dp(34)
        val sensValueWidth = if (compact) dp(36) else dp(43)
        sensBox.addView(actionButton("−", false).apply {
            textSize = 18f
            contentDescription = "Decrease sensitivity"
            setOnClickListener { changeSensitivity(-0.1f) }
        }, LinearLayout.LayoutParams(sensButtonWidth, if (compact) dp(34) else dp(36)))
        sensitivityText = label("1.1×", 13f, textMain, true).apply { gravity = Gravity.CENTER }
        sensBox.addView(sensitivityText, LinearLayout.LayoutParams(sensValueWidth, -2))
        sensBox.addView(actionButton("+", false).apply {
            textSize = 18f
            contentDescription = "Increase sensitivity"
            setOnClickListener { changeSensitivity(0.1f) }
        }, LinearLayout.LayoutParams(sensButtonWidth, if (compact) dp(34) else dp(36)))
        topbar.addView(sensBox, LinearLayout.LayoutParams(-2, -2))
        shell.addView(topbar, LinearLayout.LayoutParams(-1, if (compact) dp(42) else dp(44)).apply { bottomMargin = dp(8) })

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            clipChildren = true
        }
        val touchSurface = TouchSurface(this).apply {
            isClickable = true
            onMove = { dx, dy -> sendMove(dx, dy) }
            onTap = { sendClick(false) }
            onSecondTap = { sendClick(true) }
        }
        mainRow.addView(touchSurface, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(8) })

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.FILL
        }
        scrollCol.addView(actionButton("▲", false).apply {
            textSize = 17f
            contentDescription = "Scroll up"
            setOnClickListener { sendScroll(1f) }
        }, LinearLayout.LayoutParams(railWidth, dp(42)).apply { bottomMargin = dp(6) })
        val scrollTrack = ScrollSurface(this) { delta -> sendScroll(delta) }.apply { isClickable = true }
        scrollCol.addView(scrollTrack, LinearLayout.LayoutParams(railWidth, 0, 1f).apply { bottomMargin = dp(6) })
        scrollCol.addView(actionButton("▼", false).apply {
            textSize = 17f
            contentDescription = "Scroll down"
            setOnClickListener { sendScroll(-1f) }
        }, LinearLayout.LayoutParams(railWidth, dp(42)))
        mainRow.addView(scrollCol, LinearLayout.LayoutParams(railWidth, -1))
        shell.addView(mainRow, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(8) })

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(actionButton("Left", false).apply {
            textSize = 16f
            contentDescription = "Left mouse click"
            setOnClickListener { sendClick(false) }
        }, LinearLayout.LayoutParams(0, bottomButtonHeight, 1f).apply { rightMargin = dp(8) })
        bottomRow.addView(actionButton("Right", false).apply {
            textSize = 16f
            contentDescription = "Right mouse click"
            setOnClickListener { sendClick(true) }
        }, LinearLayout.LayoutParams(0, bottomButtonHeight, 1f))
        shell.addView(bottomRow, LinearLayout.LayoutParams(-1, -2))

        // Keep the editor focusable for the IME without changing the visible layout.
        statusText = label("OFFLINE", 1f, textMuted, false).apply { visibility = View.GONE }
        keyboardInput = EditText(this).apply {
            alpha = 0.01f
            setSingleLine(true)
            textSize = 1f
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            isFocusable = true
            isFocusableInTouchMode = true
            background = null
        }
        shell.addView(keyboardInput, LinearLayout.LayoutParams(dp(2), dp(2)))
        shell.isFocusableInTouchMode = true
        shell.requestFocus()

        setContentView(shell, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applySystemBarInsets(shell, basePadding)
        installKeyboardForwarding()
        switchMode(Mode.WIFI)
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarInsets(root: View, basePadding: Int) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(basePadding + left, basePadding + top, basePadding + right, basePadding + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun openPhoneKeyboard() {
        keyboardInput.requestFocus()
        keyboardInput.post {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun installKeyboardForwarding() {
        var previous = ""
        var suppressForwarding = false
        keyboardInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString().orEmpty()
                if (suppressForwarding) {
                    previous = current
                    return
                }
                if (current == previous) return
                when {
                    current.startsWith(previous) -> sendText(current.substring(previous.length))
                    previous.startsWith(current) -> repeat(previous.length - current.length) { sendKey("BACKSPACE") }
                    else -> {
                        repeat(previous.length) { sendKey("BACKSPACE") }
                        sendText(current)
                    }
                }
                previous = current
            }
        })
        keyboardInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                sendKey("ENTER")
                suppressForwarding = true
                keyboardInput.text?.clear()
                suppressForwarding = false
                true
            } else false
        }
    }

    private fun switchMode(next: Mode) {
        if (mode == next && next == Mode.WIFI && socketOpen) return
        mode = next
        styleModeButton(wifiModeButton, next == Mode.WIFI)
        styleModeButton(bluetoothModeButton, next == Mode.BLUETOOTH)

        if (next == Mode.WIFI) {
            wifiAutoReconnect = true
            if (!hasWifiDiscoveryPermission()) return
            if (socketOpen) setStatus("CONNECTED · WI-FI", true)
            else discoverWifiServer(showFailureToast = true)
        } else {
            wifiAutoReconnect = false
            wifiHandler.removeCallbacks(wifiReconnectTask)
            socket?.close(1000, "Switched to Bluetooth")
            socket = null
            socketOpen = false
            setStatus("STARTING BLUETOOTH HID…", false)
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions()
                setStatus("BLUETOOTH PERMISSION REQUIRED", false)
            } else {
                startBluetoothHid()
                makeDiscoverable()
            }
        }
    }

    /**
     * Find the laptop server without asking the user for an IP or port.
     * mouse.py answers this UDP broadcast on discoveryPort and supplies its
     * automatically selected HTTP/WebSocket port.
     */
    private fun hasWifiDiscoveryPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 704)
            setStatus("NEARBY DEVICES PERMISSION REQUIRED", false)
            return false
        }
        return true
    }

    private fun discoverWifiServer(showFailureToast: Boolean) {
        if (wifiDiscoveryRunning || mode != Mode.WIFI || !wifiAutoReconnect) return
        wifiDiscoveryRunning = true
        setStatus("SEARCHING FOR LAPTOP…", false)
        Thread({
            var foundAuthority: String? = null
            try {
                val destinations = linkedSetOf<InetAddress>()
                destinations.add(InetAddress.getByName("255.255.255.255"))
                try {
                    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val dhcp = wifi.dhcpInfo
                    if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                        // WifiManager DHCP addresses are exposed in little-endian integer form.
                        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                        val bytes = byteArrayOf(
                            (broadcast and 0xff).toByte(),
                            ((broadcast ushr 8) and 0xff).toByte(),
                            ((broadcast ushr 16) and 0xff).toByte(),
                            ((broadcast ushr 24) and 0xff).toByte()
                        )
                        destinations.add(InetAddress.getByAddress(bytes))
                    }
                } catch (_: Exception) {
                    // Global broadcast remains as a fallback on devices without DHCP info.
                }

                DatagramSocket(null).use { udp ->
                    udp.reuseAddress = true
                    udp.broadcast = true
                    udp.bind(InetSocketAddress(0))
                    udp.soTimeout = 700

                    // Bind discovery traffic to Wi-Fi when Android has more than one active network.
                    try {
                        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val wifiNetwork = connectivity.allNetworks.firstOrNull { network ->
                            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                        }
                        wifiNetwork?.bindSocket(udp)
                    } catch (_: Exception) {
                        // The platform default network is used if explicit Wi-Fi binding is unavailable.
                    }

                    val requestBytes = "REMOTE_MOUSE_DISCOVER".toByteArray(Charsets.UTF_8)
                    for (attempt in 0 until 5) {
                        for (destination in destinations) {
                            udp.send(DatagramPacket(requestBytes, requestBytes.size, destination, discoveryPort))
                        }
                        val deadline = System.currentTimeMillis() + 700L
                        while (System.currentTimeMillis() < deadline) {
                            val bytes = ByteArray(1024)
                            val response = DatagramPacket(bytes, bytes.size)
                            try {
                                udp.receive(response)
                                val payload = String(response.data, 0, response.length, Charsets.UTF_8)
                                val json = JSONObject(payload)
                                val port = json.optInt("port", -1)
                                val service = json.optString("service")
                                val host = response.address.hostAddress
                                if (service == "remote-mouse" && port in 1..65535 && !host.isNullOrBlank()) {
                                    foundAuthority = "$host:$port"
                                    break
                                }
                            } catch (_: SocketTimeoutException) {
                                break
                            } catch (_: Exception) {
                                // Ignore unrelated or malformed UDP replies and keep listening.
                            }
                        }
                        if (foundAuthority != null) break
                    }
                }
            } catch (_: Exception) {
                // Discovery is retried below; a blocked broadcast must not crash the UI.
            }

            runOnUiThread {
                wifiDiscoveryRunning = false
                if (mode != Mode.WIFI || !wifiAutoReconnect) return@runOnUiThread
                if (foundAuthority != null) {
                    serverAuthority = foundAuthority
                    openWifiSocket(foundAuthority!!, showFailureToast)
                } else {
                    setStatus("LAPTOP SERVER NOT FOUND", false)
                    if (showFailureToast) toast("No laptop found. Start mouse.py, use the same Wi-Fi, and allow Python through Windows Firewall on Private networks.")
                    scheduleWifiReconnect()
                }
            }
        }, "RemoteMouseDiscovery").start()
    }

    private fun openWifiSocket(authority: String, showFailureToast: Boolean) {
        val wsUrl = "ws://$authority/ws"
        try {
            socket?.close(1000, "Reconnecting")
            socketOpen = false
            setStatus("CONNECTING…", false)
            val request = Request.Builder().url(wsUrl).build()
            val newSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        if (socket !== webSocket) return@runOnUiThread
                        socketOpen = true
                        wifiHandler.removeCallbacks(wifiReconnectTask)
                        if (mode == Mode.WIFI) setStatus("CONNECTED · WI-FI", true)
                        sendWifi(JSONObject().put("type", "set_sensitivity").put("value", sensitivity.toDouble()))
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnUiThread {
                        if (socket !== webSocket) return@runOnUiThread
                        socketOpen = false
                        if (mode == Mode.WIFI) setStatus("LAPTOP NOT REACHABLE · RETRYING", false)
                        if (showFailureToast) toast("Could not connect. Check that mouse.py is running on the laptop.")
                        scheduleWifiReconnect()
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runOnUiThread {
                        if (socket !== webSocket) return@runOnUiThread
                        socketOpen = false
                        if (mode == Mode.WIFI) setStatus("DISCONNECTED · RETRYING", false)
                        scheduleWifiReconnect()
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
            socket = newSocket
        } catch (e: IllegalArgumentException) {
            if (showFailureToast) toast("Laptop server address was invalid. Restart mouse.py and try again.")
            setStatus("INVALID SERVER ADDRESS", false)
            scheduleWifiReconnect()
        }
    }

    private fun scheduleWifiReconnect() {
        if (wifiAutoReconnect && mode == Mode.WIFI) {
            wifiHandler.removeCallbacks(wifiReconnectTask)
            wifiHandler.postDelayed(wifiReconnectTask, 3000L)
        }
    }

    private fun startBluetoothHid() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setStatus("ANDROID 9 OR NEWER REQUIRED", false)
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        bluetooth.start()
    }

    private fun makeDiscoverable() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            toast("This device does not have Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 702)
            return
        }
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 703)
        } catch (e: SecurityException) {
            toast("Allow Nearby devices permission, then try again")
        }
    }

    private fun choosePairedLaptop() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        try {
            val devices = bluetooth.pairedDevices()
            if (devices.isEmpty()) {
                toast("No paired laptop yet. Pair from the laptop's Bluetooth settings first.")
                makeDiscoverable()
                return
            }
            val names = devices.map { device ->
                val name = try { device.name ?: "Bluetooth device" } catch (_: SecurityException) { "Bluetooth device" }
                "$name\n${device.address}"
            }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Choose your laptop")
                .setItems(names) { _, which -> bluetooth.connect(devices[which]) }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: SecurityException) {
            toast("Bluetooth permission was not granted")
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE),
                701
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701) {
            if (hasBluetoothPermissions()) {
                startBluetoothHid()
                if (mode == Mode.BLUETOOTH) makeDiscoverable()
            } else toast("Bluetooth permissions are needed for HID pairing")
        } else if (requestCode == 704) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            ) {
                if (mode == Mode.WIFI && wifiAutoReconnect) discoverWifiServer(showFailureToast = true)
            } else {
                setStatus("WI-FI DISCOVERY PERMISSION DENIED", false)
                toast("Allow Nearby devices permission to find your laptop on Wi-Fi.")
            }
        }
    }

    @Deprecated("Deprecated by Android; kept for Bluetooth enable/discoverable activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 702 && resultCode == Activity.RESULT_OK) {
            startBluetoothHid()
            makeDiscoverable()
        }
        if (requestCode == 703 && resultCode != Activity.RESULT_CANCELED) {
            toast("Phone is discoverable. Add 'Remote Mouse' from the laptop's Bluetooth settings.")
        }
    }

    private fun hasActiveConnection(): Boolean = if (mode == Mode.WIFI) socketOpen else bluetoothOpen

    private fun showDisconnectedHint() {
        val now = System.currentTimeMillis()
        if (now - lastConnectionHintAt < 2200L) return
        lastConnectionHintAt = now
        val message = statusText.text?.toString()?.takeIf { it.isNotBlank() } ?: "Not connected"
        toast(message)
    }

    private fun sendMove(dx: Float, dy: Float) {
        if (!hasActiveConnection()) return
        if (mode == Mode.WIFI) {
            sendWifi(JSONObject().put("type", "move").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        } else {
            bluetooth.move(dx * sensitivity, dy * sensitivity)
        }
    }

    private fun sendClick(right: Boolean) {
        if (!hasActiveConnection()) { showDisconnectedHint(); return }
        if (mode == Mode.WIFI) {
            sendWifi(JSONObject().put("type", "click").put("button", if (right) "right" else "left"))
        } else bluetooth.click(right)
    }

    private fun sendScroll(dy: Float) {
        if (!hasActiveConnection()) { showDisconnectedHint(); return }
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "scroll").put("dy", dy.toDouble()))
        else bluetooth.scroll(dy)
    }

    private fun sendText(value: String) {
        if (value.isEmpty()) return
        if (!hasActiveConnection()) { showDisconnectedHint(); return }
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "text").put("text", value))
        else bluetooth.typeText(value)
    }

    private fun sendKey(key: String) {
        if (!hasActiveConnection()) { showDisconnectedHint(); return }
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "key").put("key", key))
        else bluetooth.key(key)
    }

    private fun sendWifi(message: JSONObject) {
        val activeSocket = socket
        if (socketOpen && activeSocket != null) {
            if (!activeSocket.send(message.toString())) {
                socketOpen = false
                if (mode == Mode.WIFI) setStatus("CONNECTION LOST · RETRYING", false)
                scheduleWifiReconnect()
            }
        }
    }

    private fun changeSensitivity(delta: Float) {
        sensitivity = (sensitivity + delta).coerceIn(0.8f, 3.0f)
        sensitivity = kotlin.math.round(sensitivity * 10f) / 10f
        sensitivityText.text = "${"%.1f".format(sensitivity)}×"
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "set_sensitivity").put("value", sensitivity.toDouble()))
    }

    private fun setStatus(message: String, connected: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (connected) accent else textMuted)
        statusDot.background = rounded(if (connected) accent else Color.rgb(238, 91, 91), 100f)
        statusDot.contentDescription = message
    }

    private fun styleModeButton(button: Button, selected: Boolean) {
        button.background = rounded(if (selected) surface2 else Color.TRANSPARENT, 12f, if (selected) accent else Color.TRANSPARENT)
        button.setTextColor(if (selected) accent else textMuted)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun actionButton(value: String, primary: Boolean): Button = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(0, 28, 24) else textMain)
        background = rounded(if (primary) accent else surface2, 13f, if (primary) accent else border)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        isClickable = true
        isFocusable = true
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        wifiAutoReconnect = false
        wifiHandler.removeCallbacks(wifiReconnectTask)
        socket?.close(1000, "App closed")
        bluetoothController?.close()
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private class TouchSurface(context: android.content.Context) : View(context) {
        var onMove: ((Float, Float) -> Unit)? = null
        var onTap: (() -> Unit)? = null
        var onSecondTap: (() -> Unit)? = null
        private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val glowPaint2 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 45, 49); style = android.graphics.Paint.Style.STROKE; strokeWidth = dpLocal(1f)
        }
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0f
        private var startY = 0f
        private var downAt = 0L
        private var moved = 0f
        private var secondTouch = false
        private var pointer = -1

        init { background = roundedLocal() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            // Subtle teal radial glows from the original mouse.py touch surface,
            // drawn over a true-black AMOLED base.
            glowPaint.shader = android.graphics.RadialGradient(
                width * 0.20f, height * 0.20f, width * 0.62f,
                intArrayOf(Color.argb(22, 20, 184, 166), Color.TRANSPARENT),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            glowPaint2.shader = android.graphics.RadialGradient(
                width * 0.82f, height * 0.82f, width * 0.66f,
                intArrayOf(Color.argb(16, 34, 193, 195), Color.TRANSPARENT),
                null, android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint2)
            canvas.drawRoundRect(
                dpLocal(1f), dpLocal(1f), width - dpLocal(1f), height - dpLocal(1f),
                dpLocal(19f), dpLocal(19f), edge
            )
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointer = event.getPointerId(0)
                    lastX = event.x; lastY = event.y
                    startX = event.x; startY = event.y
                    moved = 0f; secondTouch = false; downAt = System.currentTimeMillis()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    secondTouch = true
                    onSecondTap?.invoke()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointer).coerceAtLeast(0)
                    val x = event.getX(index); val y = event.getY(index)
                    val dx = x - lastX; val dy = y - lastY
                    moved += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (!secondTouch && (dx != 0f || dy != 0f)) onMove?.invoke(dx, dy)
                    lastX = x; lastY = y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (!secondTouch && moved < dpLocal(10f) && System.currentTimeMillis() - downAt < 280L) onTap?.invoke()
                    pointer = -1
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointer = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun dpLocal(v: Float) = v * resources.displayMetrics.density
        private fun roundedLocal() = GradientDrawable().apply {
            setColor(Color.rgb(1, 3, 4)); cornerRadius = dpLocal(20f)
            setStroke(dpLocal(1f).toInt(), Color.rgb(35, 45, 49))
        }
    }

    private class ScrollSurface(context: android.content.Context, val onScroll: (Float) -> Unit) : View(context) {
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 91, 98); strokeWidth = dpLocal(3f); strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private var lastY = 0f
        init {
            background = GradientDrawable().apply {
                setColor(Color.rgb(2, 4, 5)); cornerRadius = dpLocal(16f)
                setStroke(dpLocal(1f).toInt(), Color.rgb(35, 45, 49))
            }
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            canvas.drawLine(width / 2f, dpLocal(12f), width / 2f, height - dpLocal(12f), line)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true); return true }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.y - lastY
                    lastY = event.y
                    if (abs(delta) > 0.1f) onScroll(-delta / (18f * resources.displayMetrics.density))
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun dpLocal(v: Float) = v * resources.displayMetrics.density
    }
}
