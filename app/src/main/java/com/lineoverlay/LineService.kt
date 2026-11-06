package com.lineoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class LineService : Service() {

    companion object {
        const val ACTION_LENGTH = "com.lineoverlay.LENGTH"
        private const val CHANNEL = "line_overlay"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private var lineView: LineView? = null
    private lateinit var lineLp: WindowManager.LayoutParams

    // 0 = centro (mover / pinça+giro com 2 dedos), 1 = ponta "+", 2 = ponta "-"
    private val handles = arrayOfNulls<View>(3)
    private val handleLps = arrayOfNulls<WindowManager.LayoutParams>(3)

    // estado da linha (px da tela); ang = direção da ponta "-" para a ponta "+", em radianos
    private var cx = 0f
    private var cy = 0f
    private var len = 0f
    private var ang = 0f
    private var sw = 0
    private var sh = 0

    private val density get() = resources.displayMetrics.density
    private val minLen get() = 120 * density
    private val maxLen get() = hypot(sw.toFloat(), sh.toFloat())
    private val piF = Math.PI.toFloat()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LENGTH && lineView != null) {
            len = (Prefs.len(this) * sw).coerceIn(minLen, maxLen)
            updateAll()
            save()
            return START_STICKY
        }
        startFg()
        if (lineView == null) show()
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

    private fun params(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun show() {
        val (w, h) = screen()
        sw = w; sh = h
        len = (Prefs.len(this) * sw).coerceIn(minLen, maxLen)
        cx = Prefs.cx(this).let { if (it < 0) sw / 2f else it.toFloat() }.coerceIn(0f, sw.toFloat())
        cy = Prefs.cy(this).let { if (it < 0) sh / 2f else it.toFloat() }.coerceIn(0f, sh.toFloat())
        ang = Math.toRadians(Prefs.ang(this).toDouble()).toFloat()

        // Janela de tela cheia só para desenhar: não captura toque nenhum.
        lineLp = params(sw, sh, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val lv = LineView(this)
        lineView = lv
        wm.addView(lv, lineLp)

        // Zonas de toque invisíveis (centro e duas pontas); o resto da tela segue livre.
        val sizes = intArrayOf(80, 56, 56)
        for (i in 0..2) {
            val z = (sizes[i] * density).toInt()
            val lp = params(z, z, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            val v = View(this)
            attach(v, i)
            handles[i] = v
            handleLps[i] = lp
            wm.addView(v, lp)
        }
        updateAll()
    }

    private fun setHandle(i: Int, x: Float, y: Float) {
        val v = handles[i] ?: return
        val lp = handleLps[i] ?: return
        lp.x = (x - lp.width / 2f).toInt()
        lp.y = (y - lp.height / 2f).toInt()
        wm.updateViewLayout(v, lp)
    }

    private fun updateAll() {
        val dx = cos(ang) * len / 2f
        val dy = sin(ang) * len / 2f
        lineView?.set(cx - dx, cy - dy, cx + dx, cy + dy)
        setHandle(0, cx, cy)
        setHandle(1, cx + dx, cy + dy)
        setHandle(2, cx - dx, cy - dy)
    }

    private fun save() {
        if (sw == 0) return
        Prefs.setState(this, cx.toInt(), cy.toInt(), Math.toDegrees(ang.toDouble()).toFloat(), len / sw)
    }

    private fun attach(v: View, idx: Int) {
        var lastX = 0f
        var lastY = 0f
        var rebase = true
        var fx = 0f; var fy = 0f   // ponta fixa (idx 1 e 2)
        var gx = 0f; var gy = 0f   // distância entre o dedo e a ponta ao tocar
        var pSpan = 0f
        var pAng = 0f

        v.setOnTouchListener { _, e ->
            fun rx(i: Int) = e.rawX - e.getX(0) + e.getX(i)
            fun ry(i: Int) = e.rawY - e.getY(0) + e.getY(i)

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; rebase = false
                    if (idx != 0) {
                        val s = if (idx == 1) 1f else -1f
                        val ex = cx + s * cos(ang) * len / 2f
                        val ey = cy + s * sin(ang) * len / 2f
                        fx = cx - s * cos(ang) * len / 2f
                        fy = cy - s * sin(ang) * len / 2f
                        gx = ex - e.rawX; gy = ey - e.rawY
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> rebase = true
                MotionEvent.ACTION_MOVE -> {
                    if (idx == 0) {
                        if (e.pointerCount == 1) {
                            if (rebase) {
                                lastX = e.rawX; lastY = e.rawY; rebase = false
                            } else {
                                cx = (cx + e.rawX - lastX).coerceIn(0f, sw.toFloat())
                                cy = (cy + e.rawY - lastY).coerceIn(0f, sh.toFloat())
                                lastX = e.rawX; lastY = e.rawY
                                updateAll()
                            }
                        } else if (e.pointerCount >= 2) {
                            val x0 = rx(0); val y0 = ry(0); val x1 = rx(1); val y1 = ry(1)
                            val span = hypot(x1 - x0, y1 - y0)
                            val a = atan2(y1 - y0, x1 - x0)
                            if (rebase) {
                                pSpan = span; pAng = a; rebase = false
                            } else {
                                if (pSpan > 1f) len = (len * span / pSpan).coerceIn(minLen, maxLen)
                                var d = a - pAng
                                while (d > piF) d -= 2 * piF
                                while (d < -piF) d += 2 * piF
                                ang += d
                                pSpan = span; pAng = a
                                updateAll()
                            }
                        }
                    } else {
                        val ex = e.rawX + gx
                        val ey = e.rawY + gy
                        val dx = ex - fx
                        val dy = ey - fy
                        val dist = hypot(dx, dy)
                        if (dist > 1f) {
                            val ux = dx / dist
                            val uy = dy / dist
                            val l = dist.coerceIn(minLen, maxLen)
                            cx = fx + ux * l / 2f
                            cy = fy + uy * l / 2f
                            len = l
                            ang = if (idx == 1) atan2(uy, ux) else atan2(-uy, -ux)
                            updateAll()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> save()
            }
            true
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (lineView == null) return
        val (w, h) = screen()
        sw = w; sh = h
        lineLp.width = sw
        lineLp.height = sh
        wm.updateViewLayout(lineView, lineLp)
        cx = cx.coerceIn(0f, sw.toFloat())
        cy = cy.coerceIn(0f, sh.toFloat())
        len = len.coerceIn(minLen, maxLen)
        updateAll()
    }

    override fun onDestroy() {
        lineView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        for (v in handles) v?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        lineView = null
        running = false
        super.onDestroy()
    }

    private inner class LineView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            strokeCap = Paint.Cap.BUTT
        }
        private val loc = IntArray(2)
        private var x1 = 0f; private var y1 = 0f; private var x2 = 0f; private var y2 = 0f

        fun set(a: Float, b: Float, c2: Float, d: Float) {
            x1 = a; y1 = b; x2 = c2; y2 = d
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }
}
