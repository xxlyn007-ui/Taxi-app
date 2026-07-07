package ru.taxiimpulse.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: FrameLayout
    private lateinit var bubbleLabel: TextView
    private lateinit var badgeView: TextView
    private var layoutParams: WindowManager.LayoutParams? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val count = intent.getIntExtra(EXTRA_COUNT, 0)
            updateBubble(count)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE), RECEIVER_NOT_EXPORTED)
        createBubble()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun createBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val size = dp(60)
        val badgeSize = dp(20)

        // Круглый фон пузырька (фиолетовый градиент)
        val circleDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#7c3aed"))
            setStroke(dp(2), Color.parseColor("#a78bfa"))
        }

        // Основной контейнер — круг
        bubbleView = FrameLayout(this).apply {
            background = circleDrawable
            layoutParams = FrameLayout.LayoutParams(size, size)
            elevation = 12f
        }

        // Иконка молнии ⚡ внутри пузырька
        bubbleLabel = TextView(this).apply {
            text = "⚡"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        // Красный бейдж с количеством заказов (правый верхний угол)
        val badgeDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#ef4444"))
        }
        badgeView = TextView(this).apply {
            text = ""
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = badgeDrawable
            visibility = View.GONE
        }

        bubbleView.addView(bubbleLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        bubbleView.addView(badgeView, FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(2)
            rightMargin = dp(2)
        })

        // Тень (elevation) + rim glow через наружную обводку
        bubbleView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        bubbleView.clipToOutline = true

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            size, size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16)
            y = dp(300)
        }

        windowManager.addView(bubbleView, layoutParams)
        makeDraggable()
    }

    private fun makeDraggable() {
        var startX = 0; var startY = 0
        var rawX = 0f; var rawY = 0f
        var pressTime = 0L

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams!!.x; startY = layoutParams!!.y
                    rawX = event.rawX; rawY = event.rawY
                    pressTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams!!.x = startX - (event.rawX - rawX).toInt()
                    layoutParams!!.y = startY + (event.rawY - rawY).toInt()
                    windowManager.updateViewLayout(bubbleView, layoutParams)
                }
                MotionEvent.ACTION_UP -> {
                    if (System.currentTimeMillis() - pressTime < 300L) openApp()
                }
            }
            true
        }
    }

    private fun updateBubble(count: Int) {
        Handler(Looper.getMainLooper()).post {
            if (count > 0) {
                badgeView.text = if (count > 9) "9+" else count.toString()
                badgeView.visibility = View.VISIBLE
            } else {
                badgeView.visibility = View.GONE
            }
        }
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return
        startActivity(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        updateBubble(count)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
        try { if (::bubbleView.isInitialized) windowManager.removeView(bubbleView) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            .setContentText("Виджет активен — нажмите чтобы открыть")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "taxi_bubble_svc"
        const val NOTIF_ID = 9001
        const val ACTION_UPDATE = "ru.taxiimpulse.app.BUBBLE_UPDATE"
        const val EXTRA_COUNT = "count"
    }
}
