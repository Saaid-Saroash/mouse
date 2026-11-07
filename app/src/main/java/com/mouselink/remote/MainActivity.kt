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
