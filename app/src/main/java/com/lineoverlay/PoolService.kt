package com.lineoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.acos
import kotlin.math.hypot

class PoolService : Service() {

    companion object {
        const val ACTION_CALIBRATE = "com.lineoverlay.CALIBRATE"
        private const val CHANNEL = "line_overlay"
    }

    private class Pocket(val x: Float, val y: Float, val r: Float)
    private enum class Mode { CALIBRATE, CUE, TARGET, POCKET, PLAY }

    private lateinit var wm: WindowManager
    private var canvasView: PoolView? = null
    private var button: TextView? = null
    private lateinit var canvasLp: WindowManager.LayoutParams
    private lateinit var btnLp: WindowManager.LayoutParams

    private val pockets = ArrayList<Pocket>()
    private val calTmp = ArrayList<Pocket>()
    private var ballR = 0f
    private var mode = Mode.CUE
    private var calStep = 0 // 0..5 caçapas, 6 bola
    private var cue: PointF? = null
    private var target: PointF? = null
    private var pocket: Pocket? = null

    private val density get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (canvasView == null) create()
        if (intent?.action == ACTION_CALIBRATE) startCalibration()
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Line Overlay", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Line Overlay")
            .setContentText("Modo sinuca ativo")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2, n)
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
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun loadCal() {
        pockets.clear(); ballR = 0f
        val parts = Prefs.pool(this).split(";").filter { it.isNotBlank() }
        if (parts.size != 7) return
        try {
            for (i in 0 until 6) {
                val v = parts[i].split(",")
                pockets.add(Pocket(v[0].toFloat(), v[1].toFloat(), v[2].toFloat()))
            }
            ballR = parts[6].toFloat()
        } catch (_: Exception) { pockets.clear(); ballR = 0f }
    }

    private fun saveCal() {
        Prefs.setPool(this, pockets.joinToString(";") { "${it.x},${it.y},${it.r}" } + ";" + ballR)
    }

    private fun create() {
        loadCal()
        val (sw, sh) = screen()
        canvasLp = params(sw, sh, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val cv = PoolView(this)
        canvasView = cv
        wm.addView(cv, canvasLp)

        val size = (56 * density).toInt()
        btnLp = params(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        btnLp.x = (8 * density).toInt()
        btnLp.y = (sh * 0.4f).toInt()
        val b = TextView(this).apply {
            text = "MIRA"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC111111"))
                setStroke((2 * density).toInt(), Color.WHITE)
            }
        }
        var sx = 0f; var sy = 0f; var bx = 0; var by = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; bx = btnLp.x; by = btnLp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && hypot(dx, dy) > 10 * density) moved = true
                    if (moved) {
                        btnLp.x = (bx + dx).toInt(); btnLp.y = (by + dy).toInt()
                        wm.updateViewLayout(b, btnLp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) newShot()
            }
            true
        }
        button = b
        wm.addView(b, btnLp)

        if (pockets.size < 6 || ballR <= 0f) startCalibration() else setMode(Mode.CUE)
    }

    private fun setMode(m: Mode) {
        mode = m
        val cv = canvasView ?: return
        val touchable = m != Mode.PLAY
        canvasLp.flags = if (touchable) canvasLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else canvasLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(cv, canvasLp)
        cv.invalidate()
    }

    private fun startCalibration() {
        calStep = 0
        calTmp.clear()
        cue = null; target = null; pocket = null
        setMode(Mode.CALIBRATE)
    }

    private fun newShot() {
        cue = null; target = null; pocket = null
        if (pockets.size < 6 || ballR <= 0f) startCalibration() else setMode(Mode.CUE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val cv = canvasView ?: return
        val (sw, sh) = screen()
        canvasLp.width = sw; canvasLp.height = sh
        wm.updateViewLayout(cv, canvasLp)
    }

    override fun onDestroy() {
        canvasView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        button?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        canvasView = null; button = null
        super.onDestroy()
    }

    private inner class PoolView(c: Context) : View(c) {
        private val loc = IntArray(2)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density
        }
        private val faint = Paint(line).apply { alpha = 110; strokeWidth = 1.5f * density }
        private val dash = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(14 * density, 10 * density), 0f) }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15 * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC000000") }

        private var dx0 = 0f; private var dy0 = 0f; private var dr = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (mode) {
                Mode.CALIBRATE -> calTouch(e)
                Mode.CUE, Mode.TARGET, Mode.POCKET -> tapTouch(e)
                Mode.PLAY -> return false
            }
            return true
        }

        private fun calTouch(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; dr = 0f }
                MotionEvent.ACTION_MOVE -> dr = hypot(e.rawX - dx0, e.rawY - dy0)
                MotionEvent.ACTION_UP -> {
                    if (dr >= 8 * density) {
                        if (calStep < 6) calTmp.add(Pocket(dx0, dy0, dr)) else ballR = dr
                        calStep++
                        if (calStep >= 7) {
                            pockets.clear(); pockets.addAll(calTmp)
                            saveCal()
                            setMode(Mode.CUE)
                        }
                    }
                    dr = 0f
                }
            }
            invalidate()
        }

        private fun tapTouch(e: MotionEvent) {
            if (e.actionMasked != MotionEvent.ACTION_UP) return
            val p = PointF(e.rawX, e.rawY)
            when (mode) {
                Mode.CUE -> { cue = p; mode = Mode.TARGET }
                Mode.TARGET -> { target = p; mode = Mode.POCKET }
                Mode.POCKET -> {
                    pocket = pockets.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
                    if (pocket != null) setMode(Mode.PLAY)
                }
                else -> {}
            }
            invalidate()
        }

        private fun banner(c: Canvas, text: String, y: Float, color: Int = Color.WHITE) {
            val w = txt.measureText(text)
            val pad = 12 * density
            val cxs = loc[0] + width / 2f
            c.drawRoundRect(RectF(cxs - w / 2 - pad, y - txt.textSize - pad * 0.4f, cxs + w / 2 + pad, y + pad * 0.6f), pad, pad, bg)
            txt.color = color
            c.drawText(text, cxs, y, txt)
        }

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val top = 72 * density

            when (mode) {
                Mode.CALIBRATE -> {
                    for (p in calTmp) c.drawCircle(p.x, p.y, p.r, line)
                    if (dr > 0f) c.drawCircle(dx0, dy0, dr, line)
                    banner(c, if (calStep < 6) "CAÇAPA ${calStep + 1}/6: ARRASTE DO CENTRO ATÉ A BORDA"
                    else "BOLA: ARRASTE DO CENTRO ATÉ A BORDA", top)
                }
                Mode.CUE, Mode.TARGET, Mode.POCKET -> {
                    for (p in pockets) c.drawCircle(p.x, p.y, p.r, faint)
                    cue?.let { c.drawCircle(it.x, it.y, ballR, line) }
                    target?.let { c.drawCircle(it.x, it.y, ballR, line) }
                    banner(c, when (mode) {
                        Mode.CUE -> "TOQUE NA BOLA BRANCA"
                        Mode.TARGET -> "TOQUE NA BOLA ALVO"
                        else -> "TOQUE NA CAÇAPA"
                    }, top)
                }
                Mode.PLAY -> {
                    val cu = cue; val t = target; val pk = pocket
                    if (cu != null && t != null && pk != null && ballR > 0f) drawShot(c, cu, t, pk, top)
                }
            }
        }

        private fun drawShot(c: Canvas, cu: PointF, t: PointF, pk: Pocket, top: Float) {
            val ux0 = pk.x - t.x; val uy0 = pk.y - t.y
            val dist = hypot(ux0, uy0)
            if (dist < 1f) return
            val ux = ux0 / dist; val uy = uy0 / dist
            // bola fantasma: onde a branca precisa estar no impacto
            val gx = t.x - ux * 2 * ballR
            val gy = t.y - uy * 2 * ballR
            val ax = gx - cu.x; val ay = gy - cu.y
            val al = hypot(ax, ay)
            if (al < 1f) return
            val dot = (ax / al) * ux + (ay / al) * uy
            val ang = Math.toDegrees(acos(dot.toDouble().coerceIn(-1.0, 1.0))).toInt()

            c.drawLine(cu.x, cu.y, gx, gy, line)
            c.drawCircle(gx, gy, ballR, line)
            c.drawCircle(cu.x, cu.y, ballR, faint)
            c.drawLine(t.x, t.y, pk.x, pk.y, dash)
            c.drawCircle(t.x, t.y, ballR, faint)
            c.drawCircle(pk.x, pk.y, pk.r, line)

            if (dot <= 0f) banner(c, "IMPOSSÍVEL: BRANCA ATRÁS DA BOLA ALVO", top, Color.parseColor("#FF6B6B"))
            else if (ang > 75) banner(c, "ÂNGULO MUITO FECHADO ($ang°)", top, Color.parseColor("#FFB347"))
        }
    }
}
