package ru.taxiimpulse.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask

class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var badgeView: TextView
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false

    private var currentOrdersJson = "[]"

    private val uiHandler = Handler(Looper.getMainLooper())
    private var fetchTimer: Timer? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_UPDATE -> {
                    val n = intent.getIntExtra(EXTRA_COUNT, 0)
                    uiHandler.post { refreshBadge(n) }
                }
                ACTION_ORDERS_UPDATE -> {
                    currentOrdersJson = intent.getStringExtra(EXTRA_ORDERS) ?: "[]"
                    val n = try { JSONArray(currentOrdersJson).length() } catch (_: Exception) { 0 }
                    uiHandler.post {
                        refreshBadge(n)
                        if (panelVisible) rebuildOrdersInPanel()
                    }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID           = "taxi_bubble_svc"
        const val NOTIF_ID             = 9001
        const val ACTION_UPDATE        = "ru.taxiimpulse.app.BUBBLE_UPDATE"
        const val ACTION_ORDERS_UPDATE = "ru.taxiimpulse.app.BUBBLE_ORDERS_UPDATE"
        const val EXTRA_COUNT          = "count"
        const val EXTRA_ORDERS         = "orders"

        @Volatile var pendingAcceptOrderId: Int = 0
        @Volatile var pendingClose: Boolean = false

        // Set by FloatingBubbleModule.setDriverInfo — used to fetch orders natively
        @Volatile var driverToken: String = ""
        @Volatile var driverCity: String = ""
        @Volatile var apiBaseUrl: String = ""
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        val f = IntentFilter().apply {
            addAction(ACTION_UPDATE)
            addAction(ACTION_ORDERS_UPDATE)
        }
        // RECEIVER_NOT_EXPORTED (3-arg form) was added in API 33 (Android 13).
        // On older devices the method doesn't exist → NoSuchMethodError → broadcasts
        // are never received. Fall back to the 2-arg form on API < 33; for
        // internal-only broadcasts this is safe (no external app can send them).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
        // Start autonomous background fetch — runs every 12 s regardless of
        // whether broadcasts from the WebView arrive (handles backgrounded WebView).
        fetchTimer = Timer().also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { backgroundFetch() }
            }, 4000L, 12000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_COUNT, -1)?.takeIf { it >= 0 }?.let { refreshBadge(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        fetchTimer?.cancel(); fetchTimer = null
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        hidePanel()
        try { if (::bubbleView.isInitialized) wm.removeView(bubbleView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Bubble ───────────────────────────────────────────────────────────────

    private fun createBubble() {
        val size    = dp(60)
        val badgeSz = dp(20)

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#7c3aed"))
            setStroke(dp(2), Color.parseColor("#a78bfa"))
        }

        bubbleView = FrameLayout(this).apply {
            background = circleBg
            elevation = 12f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) =
                    o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }

        bubbleView.addView(
            TextView(this).apply {
                text = "⚡"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        badgeView = TextView(this).apply {
            textSize = 10f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#ef4444"))
            }
            visibility = View.GONE
        }
        bubbleView.addView(
            badgeView,
            FrameLayout.LayoutParams(badgeSz, badgeSz).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(2); rightMargin = dp(2)
            }
        )

        val overlayType = overlayWindowType()
        bubbleParams = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(300)
        }
        wm.addView(bubbleView, bubbleParams)
        attachDrag()
    }

    private fun attachDrag() {
        var sx = 0; var sy = 0; var rx = 0f; var ry = 0f
        var t0 = 0L; var moved = false

        bubbleView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bubbleParams!!.x; sy = bubbleParams!!.y
                    rx = ev.rawX; ry = ev.rawY
                    t0 = System.currentTimeMillis(); moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rx).toInt()
                    val dy = (ev.rawY - ry).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        bubbleParams!!.x = sx - dx
                        bubbleParams!!.y = sy + dy
                        wm.updateViewLayout(bubbleView, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - t0 < 400L) {
                        if (panelVisible) hidePanel() else showPanel()
                    }
                }
            }
            true
        }
    }

    private fun refreshBadge(count: Int) {
        if (count > 0) {
            badgeView.text = if (count > 9) "9+" else count.toString()
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView.visibility = View.GONE
        }
    }

    // ─── Panel ────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelVisible) return
        panelVisible = true

        // Show panel immediately with cached data
        val builtView = buildPanelView(currentOrdersJson)
        val by = bubbleParams?.y ?: dp(300)
        val sw = resources.displayMetrics.widthPixels
        val panelW = minOf(dp(320), sw - dp(16))

        panelParams = WindowManager.LayoutParams(
            panelW, dp(460), overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8); y = maxOf(dp(30), by - dp(300))
        }
        panelView = builtView
        try { wm.addView(builtView, panelParams) } catch (_: Exception) {}

        // Fetch fresh orders from the API in background only if cache is empty
        val token = driverToken
        val city  = driverCity
        val base  = apiBaseUrl
        val cachedCount = try { JSONArray(currentOrdersJson).length() } catch (_: Exception) { 0 }
        if (token.isNotBlank() && city.isNotBlank() && base.isNotBlank()) {
            Thread {
                val fresh = fetchOrdersFromApi(token, city, base)
                val freshCount = try { JSONArray(fresh).length() } catch (_: Exception) { -1 }
                uiHandler.post {
                    if (!panelVisible) return@post
                    // IMPORTANT: Only replace cached data if the fetch returned actual orders,
                    // or if the cache was empty. Never overwrite a non-empty cache with empty
                    // results — network/city-filter failures should not clear valid order data.
                    if (freshCount > 0 || cachedCount == 0) {
                        currentOrdersJson = fresh
                        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
                        val newView = buildPanelView(fresh)
                        panelView = newView
                        try { wm.addView(newView, panelParams) } catch (_: Exception) {}
                        refreshBadge(if (freshCount > 0) freshCount else 0)
                    }
                    // else: fetch returned empty but cache had orders → keep existing panel
                }
            }.start()
        }
    }

    /** Called when ORDERS_UPDATE broadcast arrives and panel is open — live refresh. */
    private fun rebuildOrdersInPanel() {
        if (!panelVisible) return
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        val newView = buildPanelView(currentOrdersJson)
        panelView = newView
        try { wm.addView(newView, panelParams) } catch (_: Exception) {}
    }

    /** Build the full scrollable panel view from a JSON string of orders. */
    private fun buildPanelView(ordersJson: String): ScrollView {
        val ctx: Context = this
        val orders = try { JSONArray(ordersJson) } catch (_: Exception) { JSONArray() }

        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = true }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0d0d1f"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#4c1d95"))
            }
            elevation = 24f
        }

        // ── Header ──
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = "⚡"; textSize = 13f; setTextColor(Color.parseColor("#a78bfa"))
            setPadding(0, 0, dp(5), 0)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "Доступные заказы"; textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        if (orders.length() > 0) {
            val badge = TextView(ctx).apply {
                text = orders.length().toString(); textSize = 11f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#7c3aed")); cornerRadius = dp(8).toFloat()
                }
            }
            val badgeLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            badgeLp.marginStart = dp(6)
            titleRow.addView(badge, badgeLp)
        }
        header.addView(titleRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Close (×) button
        header.addView(TextView(ctx).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#9ca3af"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                pendingClose = true
                hidePanel(); stopSelf(); openApp()
            }
        })
        card.addView(header, matchW())
        card.addView(hDivider(ctx))

        // ── Orders or empty state ──
        if (orders.length() == 0) {
            val empty = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            }
            empty.addView(TextView(ctx).apply {
                text = "🕐"; textSize = 20f; gravity = Gravity.CENTER
            })
            empty.addView(TextView(ctx).apply {
                text = "Нет заказов"; textSize = 13f
                setTextColor(Color.parseColor("#6b7280")); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
            card.addView(empty, matchW())
        } else {
            for (i in 0 until minOf(orders.length(), 10)) {
                if (i > 0) card.addView(hDivider(ctx))
                val o       = orders.getJSONObject(i)
                val orderId = o.optInt("id", 0)
                val from    = o.optString("fromAddress", "—")
                val to      = o.optString("toAddress", "")
                val price   = o.optInt("price", 0)

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

                val addrRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                }
                addrRow.addView(TextView(ctx).apply {
                    text = "📍"; textSize = 11f; setPadding(0, 0, dp(4), 0)
                })
                val addrCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                addrCol.addView(TextView(ctx).apply {
                    text = from; textSize = 11f; setTextColor(Color.parseColor("#c4b5fd"))
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                if (to.isNotBlank()) {
                    addrCol.addView(TextView(ctx).apply {
                        text = "→ $to"; textSize = 11f; setTextColor(Color.parseColor("#6b7280"))
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(1) }
                    })
                }
                addrRow.addView(addrCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addrRow.addView(TextView(ctx).apply {
                    text = "$price ₽"; textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) }
                })
                row.addView(addrRow, matchW())

                // Accept button
                row.addView(TextView(ctx).apply {
                    text = "Принять заказ"; textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#7c3aed")); cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener {
                        pendingAcceptOrderId = orderId
                        hidePanel(); openApp()
                    }
                })
                card.addView(row, matchW())
            }
        }

        // ── Footer ──
        card.addView(hDivider(ctx))
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        footer.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#10b981"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        })
        footer.addView(TextView(ctx).apply {
            text = "GPS активен · обновление каждые 5 сек"
            textSize = 10f; setTextColor(Color.parseColor("#6b7280"))
        })
        card.addView(footer, matchW())

        scroll.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        return scroll
    }

    private fun hidePanel() {
        if (!panelVisible) return
        panelVisible = false
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        panelView = null; panelParams = null
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            ?.let { startActivity(it) }
    }

    // ─── Background periodic fetch ────────────────────────────────────────────

    /** Runs on a background thread (Timer) every 12 s. Fetches orders from the
     *  API autonomously — does NOT rely on broadcasts from the WebView.
     *  This is the primary data source when the WebView is backgrounded/throttled. */
    private fun backgroundFetch() {
        val token = driverToken
        val city  = driverCity
        val base  = apiBaseUrl
        if (token.isBlank() || city.isBlank() || base.isBlank()) return
        try {
            val fresh = fetchOrdersFromApi(token, city, base)
            val freshArr = try { JSONArray(fresh) } catch (_: Exception) { return }
            val freshCount = freshArr.length()
            val prevCount  = try { JSONArray(currentOrdersJson).length() } catch (_: Exception) { 0 }
            // Update only if we got real orders OR the cache was empty
            if (freshCount > 0 || prevCount == 0) {
                currentOrdersJson = fresh
                uiHandler.post {
                    refreshBadge(freshCount)
                    if (panelVisible) rebuildOrdersInPanel()
                }
            }
        } catch (_: Exception) {}
    }

    // ─── Native HTTP fetch ────────────────────────────────────────────────────

    /**
     * Fetch pending orders for [city] using [token]. Returns a JSON string
     * of [{id, fromAddress, toAddress, price}] objects, or falls back to
     * [currentOrdersJson] on any error.
     */
    private fun fetchOrdersFromApi(token: String, city: String, base: String): String {
        return try {
            val encodedCity = URLEncoder.encode(city, "UTF-8")
            val conn = URL("$base/api/orders?status=pending&city=$encodedCity")
                .openConnection() as HttpURLConnection
            conn.apply {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 6000
                readTimeout    = 6000
            }
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return currentOrdersJson }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = JSONArray(body)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // API already filtered by ?status=pending&city=..., but keep status check
                // as a safety net. Do NOT filter by city again — city name comparison
                // can fail silently (whitespace, Unicode normalization) and drop valid orders.
                if (o.optString("status") != "pending") continue
                out.put(JSONObject().apply {
                    put("id", o.optInt("id"))
                    put("fromAddress", o.optString("fromAddress", "—"))
                    put("toAddress", o.optString("toAddress", ""))
                    put("price", o.optInt("price", 0))
                })
                if (out.length() >= 10) break
            }
            out.toString()
        } catch (_: Exception) {
            currentOrdersJson // network error — keep cached
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun matchW()   = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun hDivider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1a1a3e"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }
    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Taxi Impulse Bubble", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Taxi Impulse")
            .setContentText("Виджет водителя активен")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
