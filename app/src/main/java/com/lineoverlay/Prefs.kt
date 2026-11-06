package com.lineoverlay

import android.content.Context
import kotlin.math.roundToInt

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("line", Context.MODE_PRIVATE)

    // comprimento como fração da largura da tela
    fun len(c: Context) = p(c).getFloat("len", 0.7f)
    fun cx(c: Context) = p(c).getInt("cx", -1)
    fun cy(c: Context) = p(c).getInt("cy", -1)
    fun ang(c: Context) = p(c).getFloat("ang", 0f)

    fun pct(c: Context) = (len(c) * 100).roundToInt().coerceIn(5, 100)
    fun setPct(c: Context, v: Int) = p(c).edit().putFloat("len", v.coerceIn(5, 100) / 100f).apply()

    fun setState(c: Context, cx: Int, cy: Int, angDeg: Float, len: Float) =
        p(c).edit().putInt("cx", cx).putInt("cy", cy).putFloat("ang", angDeg).putFloat("len", len).apply()
}
