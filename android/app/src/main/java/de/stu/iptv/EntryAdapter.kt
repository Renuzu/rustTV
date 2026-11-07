package de.stu.iptv

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Collections

class EntryAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLongClick: (Entry) -> Unit,
    private val onFavToggle: ((Entry) -> Unit)?,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)?
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    val items = ArrayList<Entry>()
    var favorites: Set<String> = emptySet()
    var categoryNames: Map<String, String> = emptyMap()
    var dragEnabled = false
    var showLogos = true
    /** true = großes Bilder-Raster (Poster) statt Liste */
    var grid = false
    /** feste Breite in Pixel für waagerechte Reihen (Startseite), 0 = automatisch */
    var fixedWidth = 0
    /** Bildverhältnis Höhe/Breite im Raster (1.5 = Poster, 1.0 = Quadrat für Senderlogos) */
    var ratio = 1.5f

    override fun getItemViewType(position: Int) = if (grid) 1 else 0

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Entry>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val fav: ImageView = v.findViewById(R.id.fav)
        val drag: ImageView = v.findViewById(R.id.drag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_poster else R.layout.item_entry
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        if (viewType == 1) {
            (v.findViewById<View>(R.id.logo) as? RatioImageView)?.ratio = ratio
            if (fixedWidth > 0) v.layoutParams = v.layoutParams.apply { width = fixedWidth }
        }
        return VH(v)
    }

    override fun getItemCount() = items.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.name.text = e.name
        val sub = e.subtitle ?: categoryNames[e.categoryId] ?: ""
        h.sub.text = if (e.archive) "⏪ Archiv · $sub" else sub
        if (showLogos || grid) {
            h.logo.visibility = View.VISIBLE
            h.logo.load(e.icon) {
                crossfade(true)
                val ph = if (grid) android.R.drawable.ic_menu_gallery else android.R.drawable.ic_media_play
                placeholder(ph)
                error(ph)
            }
        } else h.logo.visibility = View.GONE
        if (onFavToggle != null) {
            h.fav.visibility = View.VISIBLE
            h.fav.setImageResource(if (e.id in favorites) R.drawable.ic_star else R.drawable.ic_star_off)
            h.fav.setOnClickListener { onFavToggle.invoke(e) }
        } else h.fav.visibility = View.GONE

        h.drag.visibility = if (dragEnabled) View.VISIBLE else View.GONE
        h.drag.setOnTouchListener { _, ev ->
            if (dragEnabled && ev.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag?.invoke(h)
            false
        }
        h.itemView.setOnClickListener { onClick(e) }
        h.itemView.setOnLongClickListener { onLongClick(e); true }
    }
}
