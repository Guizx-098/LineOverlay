package com.lineoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager

class LineService : Service() {

    companion object {
        const val ACTION_LENGTH = "com.lineoverlay.LENGTH"
        private const val CHANNEL = "line_overlay"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var view: View? = null
    private lateinit var lp: WindowManager.LayoutParams

    private val density get() = resources.displayMetrics.density
    private val minW get() = (48 * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LENGTH && view != null) {
            applyPct()
            return START_STICKY
        }
        startFg()
        if (view == null) show()
        running = true
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Line Overlay", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Line Overlay")
            .setContentText("Linha ativa")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun screen(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val d = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(d)
            d.widthPixels to d.heightPixels
        }

    private fun show() {
        val (sw, sh) = screen()
        val w = (sw * Prefs.pct(this) / 100).coerceIn(minW, sw)
        val h = (28 * density).toInt()
        lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.x(this@LineService).let { if (it < 0) (sw - w) / 2 else it.coerceIn(0, sw - w) }
            y = Prefs.y(this@LineService).let { if (it < 0) sh / 2 else it.coerceIn(0, sh - h) }
        }

        val v = LineView(this, 2 * density)
        var lastX = 0f
        var lastY = 0f
        var rebase = true

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val (sW, _) = screen()
                val old = lp.width
                val nw = (old * d.scaleFactor).toInt().coerceIn(minW, sW)
                lp.x = (lp.x + (old - nw) / 2).coerceIn(0, sW - nw)
                lp.width = nw
                wm.updateViewLayout(v, lp)
                return true
            }
        })

        v.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; rebase = false
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> rebase = true
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress) {
                    if (rebase) {
                        lastX = e.rawX; lastY = e.rawY; rebase = false
                    } else {
                        val (sW, sH) = screen()
                        lp.x = (lp.x + (e.rawX - lastX).toInt()).coerceIn(0, sW - lp.width)
                        lp.y = (lp.y + (e.rawY - lastY).toInt()).coerceIn(0, sH - lp.height)
                        lastX = e.rawX; lastY = e.rawY
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val (sW, _) = screen()
                    Prefs.setPos(this, lp.x, lp.y)
                    Prefs.setPct(this, lp.width * 100 / sW)
                }
            }
            true
        }

        wm.addView(v, lp)
        view = v
    }

    private fun applyPct() {
        val v = view ?: return
        val (sw, _) = screen()
        val nw = (sw * Prefs.pct(this) / 100).coerceIn(minW, sw)
        lp.x = (lp.x + (lp.width - nw) / 2).coerceIn(0, sw - nw)
        lp.width = nw
        wm.updateViewLayout(v, lp)
        Prefs.setPos(this, lp.x, lp.y)
    }

    override fun onDestroy() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        running = false
        super.onDestroy()
    }

    private class LineView(c: Context, private val thickness: Float) : View(c) {
        private val paint = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            val top = (height - thickness) / 2f
            canvas.drawRect(0f, top, width.toFloat(), top + thickness, paint)
        }
    }
}
