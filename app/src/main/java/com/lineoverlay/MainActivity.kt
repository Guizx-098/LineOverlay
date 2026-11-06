package com.lineoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var seek: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var pendingActivate = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0B0B")
        window.navigationBarColor = Color.parseColor("#0B0B0B")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(dp(28), dp(56), dp(28), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "LINE OVERLAY"
            setTextColor(Color.WHITE)
            textSize = 26f
            letterSpacing = 0.2f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        root.addView(View(this).apply { setBackgroundColor(Color.WHITE) },
            LinearLayout.LayoutParams(dp(64), dp(2)).apply { topMargin = dp(14); bottomMargin = dp(36) })

        status = TextView(this).apply {
            textSize = 14f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(36) })

        root.addView(button("ATIVAR LINHA", true) { activate() }, btnParams())
        root.addView(button("DESATIVAR", false) { deactivate() }, btnParams())

        root.addView(TextView(this).apply {
            text = "COMPRIMENTO"
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 12f
            letterSpacing = 0.15f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) })

        seek = SeekBar(this).apply {
            max = 95 // 5..100 %
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setPct(this@MainActivity, p + 5)
                    if (LineService.running) {
                        startService(Intent(this@MainActivity, LineService::class.java).setAction(LineService.ACTION_LENGTH))
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        setContentView(root)
    }

    private fun btnParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { bottomMargin = dp(14) }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        letterSpacing = 0.12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(27).toFloat()
            if (filled) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.WHITE) }
        }
        setOnClickListener { onClick() }
    }

    private fun activate() {
        if (!Settings.canDrawOverlays(this)) {
            pendingActivate = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, LineService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    private fun deactivate() {
        stopService(Intent(this, LineService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val on = LineService.running
        status.text = if (on) "ATIVA" else "DESATIVADA"
        status.setTextColor(if (on) Color.BLACK else Color.WHITE)
        status.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (on) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(1), Color.parseColor("#5A5A5A")) }
        }
        seek.progress = Prefs.pct(this) - 5
    }

    override fun onResume() {
        super.onResume()
        if (pendingActivate && Settings.canDrawOverlays(this)) {
            pendingActivate = false
            activate()
        }
        refresh()
    }
}
