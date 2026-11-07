package de.stu.iptv

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Bild im Poster-Format (Breite : Höhe = 2 : 3). */
class RatioImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {
    var ratio = 1.5f
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * ratio).toInt())
    }
}
