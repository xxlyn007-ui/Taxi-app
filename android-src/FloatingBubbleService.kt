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
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray

class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var badgeView: TextView
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelVisible = false

    private var currentOrdersJson = "[]"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_UPDATE -> {
                    val n = intent.getIntExtra(EXTRA_COUNT, 0)
                    Handler(Looper.getMainLooper()).post { refreshBadge(n) }
                }
                ACTION_ORDERS_UPDATE -> {
                    currentOrdersJson = intent.getStringExtra(EXTRA_ORDERS) ?: "[]"
                    val n = try { JSONArray(currentOrdersJson).length() } catch (e: Exception) { 0 }
                    Handler(Looper.getMainLooper()).post {
                        refreshBadge(n)
                        if (panelVisible) { hidePanel(); showPanel() }
                    }
                }
            }
        }
    }

    companion object {
        const val CHANNEL_ID   = "taxi_bubble_svc"
        const val NOTIF_ID     = 9001
        const val ACTION_UPDATE        = "ru.taxiimpulse.app.BUBBLE_UPDATE"
        const val ACTION_ORDERS_UPDATE = "ru.taxiimpulse.app.BUBBLE_ORDERS_UPDATE"
        const val EXTRA_COUNT  = "count"
        const val EXTRA_ORDERS = "orders"

        @Volatile var pendingAcceptOrderId: Int = 0
        @Volatile var pendingClose: Boolean = false
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        val f = IntentFilter().apply { addAction(ACTION_UPDATE); addAction(ACTION_ORDERS_UPDATE) }
        registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_COUNT, -1)?.takeIf { it >= 0 }?.let { refreshBadge(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        hidePanel()
        try { if (::bubbleView.isInitialized) wm.removeView(bubbleView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Bubble ───────────────────────────────────────────────────────────────

    private fun createBubble() {
        val size     = dp(60)
        val badgeSz  = dp(20)

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#7c3aed"))
            setStroke(dp(2), Color.parseColor("#a78bfa"))
        }

        bubbleView = FrameLayout(this).apply {
            background = circleBg
            elevation = 12f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }

        val icon = TextView(this).apply {
            text = "⚡"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }
        badgeView = TextView(this).apply {
            textSize = 10f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#ef4444")) }
            visibility = View.GONE
        }

        bubbleView.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bubbleView.addView(badgeView, FrameLayout.LayoutParams(badgeSz, badgeSz).apply {
            gravity = Gravity.TOP or Gravity.END; topMargin = dp(2); rightMargin = dp(2)
        })

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        bubbleParams = WindowManager.LayoutParams(size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(16); y = dp(300)
        }
        wm.addView(bubbleView, bubbleParams)
        attachDrag()
    }

    private fun attachDrag() {
        var sx = 0; var sy = 0; var rx = 0f; var ry = 0f; var t0 = 0L; var moved = false
        bubbleView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bubbleParams!!.x; sy = bubbleParams!!.y
                    rx = ev.rawX; ry = ev.rawY; t0 = System.currentTimeMillis(); moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rx).toInt(); val dy = (ev.rawY - ry).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        moved = true
                        bubbleParams!!.x = sx - dx; bubbleParams!!.y = sy + dy
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

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val sw      = resources.displayMetrics.widthPixels
        val panelW  = minOf(dp(320), sw - dp(16))
        val panelH  = dp(460)

        val orders = try { JSONArray(currentOrdersJson) } catch (_: Exception) { JSONArray() }

        // ── Root scroll ──
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }

        // ── Card container ──
        val card = LinearLayout(this).apply {
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
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "⚡"; textSize = 13f; setTextColor(Color.parseColor("#a78bfa"))
            setPadding(0, 0, dp(5), 0)
        })
        titleRow.addView(TextView(this).apply {
            text = "Доступные заказы"; textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        if (orders.length() > 0) {
            titleRow.addView(TextView(this).apply {
                text = orders.length().toString(); textSize = 11f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#7c3aed")); cornerRadius = dp(8).toFloat()
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = dp(6); layoutParams = lp
            })
        }
        header.addView(titleRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Close ×
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.parseColor("#9ca3af"))
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                pendingClose = true
                hidePanel()
                stopSelf()
                openApp()
            }
        })
        card.addView(header, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(divider())

        // ── Orders list ──
        if (orders.length() == 0) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
                addView(TextView(this).apply { text = "🕐"; textSize = 20f; gravity = Gravity.CENTER })
                addView(TextView(this).apply {
                    text = "Нет заказов"; textSize = 13f
                    setTextColor(Color.parseColor("#6b7280")); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                })
            }, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            for (i in 0 until minOf(orders.length(), 10)) {
                if (i > 0) card.addView(divider())
                val o       = orders.getJSONObject(i)
                val orderId = o.optInt("id", 0)
                val from    = o.optString("fromAddress", "—")
                val to      = o.optString("toAddress", "")
                val price   = o.optInt("price", 0)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }

                // address row
                val addrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                addrRow.addView(TextView(this).apply {
                    text = "📍"; textSize = 11f; setPadding(0, 0, dp(4), 0)
                })
                val addrCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                addrCol.addView(TextView(this).apply {
                    text = from; textSize = 11f; setTextColor(Color.parseColor("#c4b5fd"))
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                if (to.isNotBlank()) {
                    addrCol.addView(TextView(this).apply {
                        text = "→ $to"; textSize = 11f; setTextColor(Color.parseColor("#6b7280"))
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1) }
                    })
                }
                addrRow.addView(addrCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addrRow.addView(TextView(this).apply {
                    text = "$price ₽"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
                })
                row.addView(addrRow, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                // accept button
                row.addView(TextView(this).apply {
                    text = "Принять заказ"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#7c3aed")); cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    setOnClickListener {
                        pendingAcceptOrderId = orderId
                        hidePanel()
                        openApp()
                    }
                })
                card.addView(row, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        // ── Footer ──
        card.addView(divider())
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            addView(View(this@FloatingBubbleService).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#10b981")) }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
            })
            addView(TextView(this@FloatingBubbleService).apply {
                text = "GPS активен · обновление каждые 5 сек"
                textSize = 10f; setTextColor(Color.parseColor("#6b7280"))
            })
        }, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        scroll.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val by = bubbleParams?.y ?: dp(300)
        panelParams = WindowManager.LayoutParams(panelW, panelH, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = maxOf(dp(30), by - dp(300))
        }
        panelView = scroll
        wm.addView(scroll, panelParams)
    }

    private fun hidePanel() {
        if (!panelVisible) return
        panelVisible = false
        try { panelView?.let { wm.removeView(it) } } catch (_: Exception) {}
        panelView = null
        panelParams = null
    }

    private fun openApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }?.let { startActivity(it) }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1a1a3e"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Taxi Impulse Bubble", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Taxi Impulse")
            .setContentText("Виджет водителя активен")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
