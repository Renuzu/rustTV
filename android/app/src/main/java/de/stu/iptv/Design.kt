package de.stu.iptv

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.google.android.material.appbar.MaterialToolbar

/** Die vier wählbaren Designs (A–D): Farben + Name. */
object Design {
    val NAMES = arrayOf("A · Chips", "B · Leiste", "C · Kacheln", "D · Kino")
    const val ACCENT = 0xFFD81B21.toInt()

    data class Colors(val bg: Int, val surface: Int, val surface2: Int, val muted: Int)

    fun colors(d: Int) = when (d) {
        1 -> Colors(0xFF0E0E0E.toInt(), 0xFF1C1C1C.toInt(), 0xFF050505.toInt(), 0xFF9A9A9A.toInt())
        2 -> Colors(0xFF1B1F27.toInt(), 0xFF2F3542.toInt(), 0xFF262B36.toInt(), 0xFFA3ABB9.toInt())
        3 -> Colors(0xFF0B0909.toInt(), 0xFF2E2828.toInt(), 0xFF1E1A1A.toInt(), 0xFFB5ADAD.toInt())
        else -> Colors(0xFF1A1717.toInt(), 0xFF2B2626.toInt(), 0xFF262222.toInt(), 0xFFA9A1A1.toInt())
    }

    /** Hintergrund, Statusleiste und Toolbar an das gewählte Design anpassen. */
    fun apply(a: Activity, root: View, toolbar: MaterialToolbar?) {
        val c = colors(Store(a).design)
        root.setBackgroundColor(c.bg)
        @Suppress("DEPRECATION")
        a.window.statusBarColor = c.bg
        @Suppress("DEPRECATION")
        a.window.navigationBarColor = c.bg
        toolbar?.setBackgroundColor(c.bg)
    }

    fun rounded(color: Int, radiusDp: Float, a: Activity, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            val d = a.resources.displayMetrics.density
            cornerRadius = radiusDp * d
            setColor(color)
            if (strokeColor != null) setStroke((2 * d).toInt(), strokeColor)
        }

    fun tint(color: Int) = ColorStateList.valueOf(color)
}
