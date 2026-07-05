package ru.taxiimpulse.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: TextView
    private var layoutParams: WindowManager.LayoutParams? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val count = intent.getIntExtra(EXTRA_COUNT, 0)
            updateBubbleText(count)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE), RECEIVER_NOT_EXPORTED)
        createBubble()
    }

    private fun createBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        bubbleView = TextView(this).apply {
            text = "🚕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7c3aed"))
            setPadding(28, 20, 28, 20)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 300
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
                    if (System.currentTimeMillis() - pressTime < 250L) openApp()
                }
            }
            true
        }
    }

    private fun updateBubbleText(count: Int) {
        Handler(Looper.getMainLooper()).post {
            bubbleView.text = if (count > 0) "🚕 $count" else "🚕"
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
        updateBubbleText(count)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        try { if (::bubbleView.isInitialized) windowManager.removeView(bubbleView) } catch (_: Exception) {}
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
            .setContentText("Пузырёк заказов активен — нажмите чтобы открыть")
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
