package ru.taxiimpulse.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.*

class FloatingBubbleModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

    override fun getName() = "FloatingBubble"

    @ReactMethod
    fun hasPermission(callback: Callback) {
        callback.invoke(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
        )
    }

    @ReactMethod
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    @ReactMethod
    fun start(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            requestPermission(); return
        }
        val intent = Intent(ctx, FloatingBubbleService::class.java)
            .putExtra(FloatingBubbleService.EXTRA_COUNT, count)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    @ReactMethod
    fun update(count: Int) {
        ctx.sendBroadcast(
            Intent(FloatingBubbleService.ACTION_UPDATE)
                .putExtra(FloatingBubbleService.EXTRA_COUNT, count)
        )
    }

    /** Pass full orders JSON array so the native panel can show addresses + prices. */
    @ReactMethod
    fun updateOrders(ordersJson: String) {
        ctx.sendBroadcast(
            Intent(FloatingBubbleService.ACTION_ORDERS_UPDATE)
                .putExtra(FloatingBubbleService.EXTRA_ORDERS, ordersJson)
        )
    }

    @ReactMethod
    fun stop() {
        ctx.stopService(Intent(ctx, FloatingBubbleService::class.java))
        FloatingBubbleService.pendingAcceptOrderId = 0
        FloatingBubbleService.pendingClose = false
    }

    /**
     * Returns the orderId tapped in the native bubble panel (0 = none) and clears it.
     * Called by JS whenever the app comes back to the foreground.
     */
    @ReactMethod
    fun popPendingAccept(callback: Callback) {
        val id = FloatingBubbleService.pendingAcceptOrderId
        FloatingBubbleService.pendingAcceptOrderId = 0
        callback.invoke(id)
    }

    /**
     * Returns true if the driver pressed × in the native bubble panel, then clears it.
     */
    @ReactMethod
    fun popPendingClose(callback: Callback) {
        val v = FloatingBubbleService.pendingClose
        FloatingBubbleService.pendingClose = false
        callback.invoke(v)
    }
}
