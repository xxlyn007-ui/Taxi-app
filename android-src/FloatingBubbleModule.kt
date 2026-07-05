package ru.taxiimpulse.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.*

class FloatingBubbleModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

    override fun getName() = "FloatingBubble"

    @ReactMethod
    fun hasPermission(promise: Promise) {
        promise.resolve(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
        )
    }

    @ReactMethod
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(intent)
        }
    }

    @ReactMethod
    fun start(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            requestPermission()
            return
        }
        val intent = Intent(ctx, FloatingBubbleService::class.java)
            .putExtra(FloatingBubbleService.EXTRA_COUNT, count)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    @ReactMethod
    fun update(count: Int) {
        ctx.sendBroadcast(
            Intent(FloatingBubbleService.ACTION_UPDATE)
                .putExtra(FloatingBubbleService.EXTRA_COUNT, count)
        )
    }

    @ReactMethod
    fun stop() {
        ctx.stopService(Intent(ctx, FloatingBubbleService::class.java))
    }
}
