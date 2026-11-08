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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot

class MainActivity : Activity() {
    private val bg = Color.rgb(0, 0, 0)
    private val surface = Color.rgb(12, 16, 19)
    private val surface2 = Color.rgb(19, 25, 29)
    private val border = Color.rgb(39, 52, 58)
    private val accent = Color.rgb(37, 230, 194)
    private val textMain = Color.rgb(239, 246, 248)
    private val textMuted = Color.rgb(139, 157, 164)

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var wifiPanel: LinearLayout
    private lateinit var bluetoothPanel: LinearLayout
    private lateinit var wifiHostInput: EditText
    private lateinit var sensitivityText: TextView
    private lateinit var wifiModeButton: Button
    private lateinit var bluetoothModeButton: Button
    private lateinit var keyboardInput: EditText

    private var mode = Mode.WIFI
    private var sensitivity = 1.1f
    private var socket: WebSocket? = null
    private var socketOpen = false
    private var wifiAutoReconnect = false
    private val wifiHandler = Handler(Looper.getMainLooper())
    private val wifiReconnectTask = Runnable {
        if (mode == Mode.WIFI && wifiAutoReconnect && !socketOpen) openWifiSocket(showFailureToast = false)
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
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            isVerticalScrollBarEnabled = false
            addView(page, android.widget.FrameLayout.LayoutParams(-1, -2))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("REMOTE MOUSE", 20f, textMain, true))
        titleCol.addView(label("WIRELESS CONTROL", 10f, textMuted, true).apply {
            letterSpacing = 0.16f
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface2, 999f, border)
            setPadding(dp(10), dp(8), dp(11), dp(8))
        }
        statusDot = View(this).apply {
            background = rounded(Color.rgb(238, 91, 91), 100f)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) }
        }
        statusText = label("OFFLINE", 10f, textMuted, true)
        badge.addView(statusDot)
        badge.addView(statusText)
        header.addView(badge)
        page.addView(header)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(surface, 16f, border)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        wifiModeButton = actionButton("Wi-Fi", true).apply { setOnClickListener { switchMode(Mode.WIFI) } }
        bluetoothModeButton = actionButton("Bluetooth", false).apply { setOnClickListener { switchMode(Mode.BLUETOOTH) } }
        modeRow.addView(wifiModeButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        modeRow.addView(bluetoothModeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        page.addView(modeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        wifiPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18f, border)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        wifiPanel.addView(label("LAPTOP SERVER", 10f, textMuted, true).apply { letterSpacing = 0.12f })
        val connectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(5))
        }
        wifiHostInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(textMain)
            setHintTextColor(textMuted)
            hint = "Laptop IP address:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(surface2, 12f, border)
        }
        connectRow.addView(wifiHostInput, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        val connectButton = actionButton("Connect", true).apply {
            setOnClickListener { connectWifi() }
        }
        connectRow.addView(connectButton, LinearLayout.LayoutParams(dp(104), dp(46)))
        wifiPanel.addView(connectRow)
        wifiPanel.addView(label("The server must already be running on your laptop. Both devices must use the same Wi-Fi network.", 11f, textMuted, false))
        page.addView(wifiPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        bluetoothPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(surface, 18f, border)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            visibility = View.GONE
        }
        bluetoothPanel.addView(label("BLUETOOTH HID", 10f, textMuted, true).apply { letterSpacing = 0.12f })
        val btButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(7))
        }
        btButtons.addView(actionButton("Make discoverable", true).apply {
            textSize = 12f
            setOnClickListener { makeDiscoverable() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(7) })
        btButtons.addView(actionButton("Choose laptop", false).apply {
            textSize = 12f
            setOnClickListener { choosePairedLaptop() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        bluetoothPanel.addView(btButtons)
        bluetoothPanel.addView(label("On Windows: Settings → Bluetooth & devices → Add device. Pair with “Remote Mouse”, then choose the paired laptop here if it does not connect automatically.", 11f, textMuted, false))
        page.addView(bluetoothPanel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        page.addView(label("TOUCH SURFACE", 10f, textMuted, true).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), dp(2), 0, dp(8))
        })

        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val touchSurface = TouchSurface(this).apply {
            onMove = { dx, dy -> sendMove(dx, dy) }
            onTap = { sendClick(false) }
            onSecondTap = { sendClick(true) }
        }
        padRow.addView(touchSurface, LinearLayout.LayoutParams(0, dp(270), 1f).apply { rightMargin = dp(9) })

        val scrollCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        scrollCol.addView(actionButton("▲", false).apply {
            textSize = 18f
            setOnClickListener { sendScroll(1f) }
        }, LinearLayout.LayoutParams(dp(52), dp(46)).apply { bottomMargin = dp(7) })
        val scrollTrack = ScrollSurface(this) { delta -> sendScroll(delta) }
        scrollCol.addView(scrollTrack, LinearLayout.LayoutParams(dp(52), 0, 1f).apply { bottomMargin = dp(7) })
        scrollCol.addView(actionButton("▼", false).apply {
            textSize = 18f
            setOnClickListener { sendScroll(-1f) }
        }, LinearLayout.LayoutParams(dp(52), dp(46)))
        padRow.addView(scrollCol, LinearLayout.LayoutParams(dp(52), dp(270)))
        page.addView(padRow, LinearLayout.LayoutParams(-1, dp(270)).apply { bottomMargin = dp(10) })

        val clickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clickRow.addView(actionButton("LEFT CLICK", false).apply {
            setOnClickListener { sendClick(false) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(8) })
        clickRow.addView(actionButton("RIGHT CLICK", false).apply {
            setOnClickListener { sendClick(true) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        page.addView(clickRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val sensCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 16f, border)
            setPadding(dp(12), dp(7), dp(10), dp(7))
        }
        sensCard.addView(label("SENSITIVITY", 10f, textMuted, true), LinearLayout.LayoutParams(0, -2, 1f))
        sensCard.addView(actionButton("−", false).apply {
            setOnClickListener { changeSensitivity(-0.1f) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })
        sensitivityText = label("1.1×", 14f, accent, true).apply { gravity = Gravity.CENTER }
        sensCard.addView(sensitivityText, LinearLayout.LayoutParams(dp(50), -2))
        sensCard.addView(actionButton("+", false).apply {
            setOnClickListener { changeSensitivity(0.1f) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
        page.addView(sensCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        page.addView(label("KEYBOARD", 10f, textMuted, true).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), 0, 0, dp(7))
        })
        keyboardInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(textMain)
            setHintTextColor(textMuted)
            hint = "Tap here to type on the laptop…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(surface, 14f, border)
        }
        page.addView(keyboardInput, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) })
        installKeyboardForwarding()

        page.addView(label("Wi-Fi: laptop server on :8080  •  Bluetooth: native HID pairing", 10f, textMuted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(6))
        })

        setContentView(scroll)
        switchMode(Mode.WIFI)
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
        mode = next
        wifiPanel.visibility = if (next == Mode.WIFI) View.VISIBLE else View.GONE
        bluetoothPanel.visibility = if (next == Mode.BLUETOOTH) View.VISIBLE else View.GONE
        styleModeButton(wifiModeButton, next == Mode.WIFI)
        styleModeButton(bluetoothModeButton, next == Mode.BLUETOOTH)
        if (next == Mode.WIFI) {
            setStatus(if (socketOpen) "CONNECTED · WI-FI" else "WI-FI NOT CONNECTED", socketOpen)
            if (wifiAutoReconnect && !socketOpen) scheduleWifiReconnect()
        } else {
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions()
                setStatus("BLUETOOTH PERMISSION REQUIRED", false)
            } else {
                startBluetoothHid()
            }
        }
    }

    private fun connectWifi() {
        wifiAutoReconnect = true
        wifiHandler.removeCallbacks(wifiReconnectTask)
        openWifiSocket(showFailureToast = true)
    }

    private fun openWifiSocket(showFailureToast: Boolean) {
        val raw = wifiHostInput.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            if (showFailureToast) toast("Enter the laptop's local IP address, for example 192.168.1.20:8080")
            return
        }
        val authority = raw.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://").trimEnd('/')
        val wsUrl = "ws://$authority/ws"
        try {
            socket?.close(1000, "Reconnecting")
            socketOpen = false
            setStatus(if (showFailureToast) "CONNECTING…" else "RECONNECTING…", false)
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
                        if (showFailureToast) toast("Cannot reach laptop. Check its IP, Wi-Fi and port 8080 firewall rule.")
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
            if (showFailureToast) toast("Use a valid laptop address, such as 192.168.1.20:8080")
            setStatus("INVALID SERVER ADDRESS", false)
        }
    }

    private fun scheduleWifiReconnect() {
        if (wifiAutoReconnect && mode == Mode.WIFI) {
            wifiHandler.removeCallbacks(wifiReconnectTask)
            wifiHandler.postDelayed(wifiReconnectTask, 2500L)
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
            if (hasBluetoothPermissions()) startBluetoothHid()
            else toast("Bluetooth permissions are needed for HID pairing")
        }
    }

    @Deprecated("Deprecated by Android; kept for Bluetooth enable/discoverable activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 702 && resultCode == Activity.RESULT_OK) startBluetoothHid()
        if (requestCode == 703 && resultCode != Activity.RESULT_CANCELED) {
            toast("Phone is discoverable. Add 'Remote Mouse' from the laptop's Bluetooth settings.")
        }
    }

    private fun sendMove(dx: Float, dy: Float) {
        if (mode == Mode.WIFI) {
            sendWifi(JSONObject().put("type", "move").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        } else {
            bluetooth.move(dx * sensitivity, dy * sensitivity)
        }
    }

    private fun sendClick(right: Boolean) {
        if (mode == Mode.WIFI) {
            sendWifi(JSONObject().put("type", "click").put("button", if (right) "right" else "left"))
        } else bluetooth.click(right)
    }

    private fun sendScroll(dy: Float) {
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "scroll").put("dy", dy.toDouble()))
        else bluetooth.scroll(dy)
    }

    private fun sendText(value: String) {
        if (value.isEmpty()) return
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "text").put("text", value))
        else bluetooth.typeText(value)
    }

    private fun sendKey(key: String) {
        if (mode == Mode.WIFI) sendWifi(JSONObject().put("type", "key").put("key", key))
        else bluetooth.key(key)
    }

    private fun sendWifi(message: JSONObject) {
        if (socketOpen) socket?.send(message.toString())
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
        setPadding(dp(8), 0, dp(8), 0)
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
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 15, 18) }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(37, 230, 194); style = android.graphics.Paint.Style.STROKE; strokeWidth = dpLocal(1f)
        }
        private val muted = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(86, 112, 119); textSize = dpLocal(11f); textAlign = android.graphics.Paint.Align.CENTER
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
            val cx = width / 2f; val cy = height / 2f
            val radius = dpLocal(26f)
            canvas.drawCircle(cx, cy, radius, edge.apply { alpha = 90 })
            canvas.drawCircle(cx, cy, dpLocal(3f), fill.apply { color = Color.rgb(37, 230, 194) })
            canvas.drawText("DRAG TO MOVE", cx, height - dpLocal(20f), muted)
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
                MotionEvent.ACTION_CANCEL -> { pointer = -1; return true }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun dpLocal(v: Float) = v * resources.displayMetrics.density
        private fun roundedLocal() = GradientDrawable().apply {
            setColor(Color.rgb(9, 13, 16)); cornerRadius = dpLocal(20f)
            setStroke(dpLocal(1f).toInt(), Color.rgb(39, 52, 58))
        }
    }

    private class ScrollSurface(context: android.content.Context, val onScroll: (Float) -> Unit) : View(context) {
        private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 91, 98); strokeWidth = dpLocal(3f); strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private var lastY = 0f
        init {
            background = GradientDrawable().apply {
                setColor(Color.rgb(10, 14, 17)); cornerRadius = dpLocal(16f)
                setStroke(dpLocal(1f).toInt(), Color.rgb(39, 52, 58))
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        private fun dpLocal(v: Float) = v * resources.displayMetrics.density
    }
}
