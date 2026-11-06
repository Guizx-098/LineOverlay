package com.lineoverlay

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("line", Context.MODE_PRIVATE)

    fun pct(c: Context) = p(c).getInt("pct", 70)
    fun x(c: Context) = p(c).getInt("x", -1)
    fun y(c: Context) = p(c).getInt("y", -1)

    fun setPct(c: Context, v: Int) = p(c).edit().putInt("pct", v.coerceIn(5, 100)).apply()
    fun setPos(c: Context, x: Int, y: Int) = p(c).edit().putInt("x", x).putInt("y", y).apply()
}
