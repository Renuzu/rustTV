package de.stu.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eigene Oberfläche für Fernseher (Android TV / Google TV / Fire TV). Auf dem Handy wird sie nie benutzt. */
object Tv {
    fun isTv(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = ctx.packageManager
        return pm.hasSystemFeature("android.software.leanback") && !pm.hasSystemFeature("android.hardware.touchscreen")
    }

    const val TEXT = 0xFFF3F6FF.toInt()
    const val MUTED = 0xFF93A0BD.toInt()
    const val BLUE = 0xFF6EA8FF.toInt()

    /** Kategorien + Einträge einer Art laden (aus dem Zwischenspeicher, sonst vom Anbieter). */
    suspend fun load(ctx: Context, kind: Kind): Result<Unit> {
        if (Cache.entries.containsKey(kind)) return Result.success(Unit)
        val api = Xtream.from(Store(ctx))
        return withContext(Dispatchers.IO) {
            try {
                val c = api.categories(kind); val e = api.entries(kind)
                Cache.categories[kind] = c; Cache.entries[kind] = e
                Result.success(Unit)
            } catch (ex: Exception) { Result.failure(ex) }
        }
    }

    fun hm(ts: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ts * 1000))

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Untere Leiste mit den Tasten-Hinweisen. Farben: r g y b = Farbtasten. */
    fun hints(ctx: Context, vararg items: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 28), dp(ctx, 6), dp(ctx, 28), dp(ctx, 14))
        items.forEach { s ->
            val color = when (s.firstOrNull()) { 'r' -> 0xFFE53935; 'g' -> 0xFF43A047; 'y' -> 0xFFFDD835; 'b' -> 0xFF1E88E5; else -> null }
            if (color != null && s.getOrNull(1) == ':') {
                addView(View(ctx).apply { background = GradientDrawable().apply { setColor(color.toInt()); cornerRadius = dp(ctx, 3).toFloat() } },
                    LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12)).apply { marginEnd = dp(ctx, 6) })
                addView(text(ctx, s.substring(2), 12f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(ctx, 26) })
            } else addView(text(ctx, s, 12f, MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(ctx, 26) })
        }
    }

    fun clock(): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
}

// ======================================================================= list rows
class TvRow(val id: String, val title: String, val sub: String? = null, val right: String? = null,
            val icon: String? = null, val no: String? = null, val entry: Entry? = null, val kind: Kind? = null)

class TvRowAdapter(private val onFocus: (Int) -> Unit, private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<TvRowAdapter.VH>() {
    var items: List<TvRow> = emptyList()
    var activated = -1
    var showLogos = false
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val no: TextView = v.findViewById(R.id.no); val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name); val sub: TextView = v.findViewById(R.id.sub); val right: TextView = v.findViewById(R.id.right)
    }
    fun submit(l: List<TvRow>) { items = l; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.tv_item_row, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        h.name.text = r.title
        h.sub.text = r.sub ?: ""; h.sub.visibility = if (r.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.right.text = r.right ?: ""
        h.no.text = r.no ?: ""; h.no.visibility = if (r.no == null) View.GONE else View.VISIBLE
        h.logo.visibility = if (showLogos) View.VISIBLE else View.GONE
        if (showLogos) h.logo.load(r.icon) { crossfade(false) }
        h.itemView.isActivated = pos == activated
        h.itemView.setOnFocusChangeListener { _, f -> if (f) onFocus(h.bindingAdapterPosition) }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}

class TvPosterAdapter(private val onFocus: (Int) -> Unit, private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<TvPosterAdapter.VH>() {
    var items: List<Entry> = emptyList()
    class VH(v: View) : RecyclerView.ViewHolder(v) { val img: ImageView = v.findViewById(R.id.img); val name: TextView = v.findViewById(R.id.name) }
    fun submit(l: List<Entry>) { items = l; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.tv_item_poster, p, false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val e = items[pos]
        h.name.text = e.name
        h.img.load(e.icon) { crossfade(false) }
        h.itemView.setOnFocusChangeListener { v, f ->
            v.animate().scaleX(if (f) 1.06f else 1f).scaleY(if (f) 1.06f else 1f).setDuration(120).start()
            h.name.setTextColor(if (f) Tv.TEXT else 0xFFCFD7EA.toInt())
            if (f) onFocus(h.bindingAdapterPosition)
        }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}

/** Gemeinsame Basis: Hintergrund, Kopfzeile, Farbtasten. */
abstract class TvBase : AppCompatActivity() {
    protected lateinit var store: Store
    protected lateinit var root: LinearLayout
    private var clockView: TextView? = null
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { clockView?.text = Tv.clock(); h.postDelayed(this, 20000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.tv_bg) }
        setContentView(root)
        @Suppress("DEPRECATION") window.statusBarColor = 0xFF0A0F1C.toInt()
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    protected fun header(title: String, sub: String? = null, logo: Boolean = false): LinearLayout {
        val d = { v: Int -> Tv.dp(this, v) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(28), d(18), d(28), d(10)) }
        if (logo) {
            bar.addView(Tv.text(this, "rust", 30f, bold = true))
            bar.addView(Tv.text(this, "TV", 30f, 0xFF4F8DFF.toInt(), true))
        } else {
            bar.addView(Tv.text(this, title, 21f, bold = true))
            if (sub != null) bar.addView(Tv.text(this, "  ›  $sub", 19f, Tv.MUTED).apply { maxLines = 1 })
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        clockView = Tv.text(this, Tv.clock(), 21f, bold = true)
        bar.tag = "header"
        bar.addView(clockView)
        return bar
    }

    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    protected fun openSearch() = startActivity(Intent(this, TvSearchActivity::class.java))

    protected open fun play(kind: Kind, list: List<Entry>, idx: Int) {
        if (kind == Kind.SERIES) {
            val e = list.getOrNull(idx) ?: return
            store.addHistory(Kind.SERIES, e.id)
            startActivity(Intent(this, SeriesActivity::class.java).putExtra("id", e.id).putExtra("name", e.name))
        } else Play.start(this, kind, list, idx)
    }

    protected fun toggleFav(kind: Kind, e: Entry) {
        val f = store.favorites(kind).toMutableSet()
        if (!f.remove(e.id)) { f.add(e.id); toast("★ Zu Favoriten hinzugefügt") } else toast("Aus Favoriten entfernt")
        store.setFavorites(kind, f)
    }
}

// ======================================================================= home
class TvHomeActivity : TvBase() {
    private val wg = WgStarter(this, { this }) { err -> if (err != null) Wg.setEnabled(this, false); refreshVpn() }
    private lateinit var vpnTile: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> Tv.dp(this, v) }
        val acc = store.account
        root.addView(header("", logo = true).apply {
            addView(Tv.text(this@TvHomeActivity, (acc?.name ?: "") + "   ", 14f, Tv.MUTED), childCount - 1)
        })

        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(36), d(10), d(36), 0) }
        fun tile(title: String, sub: String, bg: Int, weight: Float, onClick: () -> Unit) = FrameLayout(this).apply {
            setBackgroundResource(bg); foreground = getDrawable(R.drawable.tv_ring)
            isFocusable = true; isClickable = true
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(120).start() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(d(24), 0, d(24), d(20))
                addView(Tv.text(context, title, 30f, bold = true)); addView(Tv.text(context, sub, 14f, 0xDDFFFFFF.toInt()))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }.also { big.addView(it, LinearLayout.LayoutParams(0, d(190), weight).apply { setMargins(d(10), d(10), d(10), d(10)) }) }
        val live = tile("Live-TV", "Sender & Programm", R.drawable.tv_tile_live, 1.4f) { open(Kind.LIVE) }
        tile("Filme", "nach Kategorien", R.drawable.tv_tile_movie, 1f) { open(Kind.MOVIE) }
        tile("Serien", "mit Staffeln", R.drawable.tv_tile_series, 1f) { open(Kind.SERIES) }
        root.addView(big)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(36), d(4), d(36), 0) }
        fun small(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 16f; setTextColor(Tv.TEXT); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.tv_tile_small); foreground = getDrawable(R.drawable.tv_ring)
            isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, d(64), 1f).apply { setMargins(d(10), d(10), d(10), d(10)) }) }
        small("★ Favoriten") { open(Kind.LIVE, MainActivity.CAT_FAV) }
        small("Zuletzt gesehen") { open(Kind.LIVE, MainActivity.CAT_HIST) }
        small("Suche") { openSearch() }
        vpnTile = small("VPN") { toggleVpn() }
        small("Konto") { Dialogs.accounts(this) { Cache.clear(); recreate() } }
        small("Einstellungen") { startActivity(Intent(this, SettingsActivity::class.java)) }
        root.addView(row)
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Tv.hints(this, "◀ ▶ ▲ ▼ auswählen", "OK öffnen", "b:Suche", "Zurück beenden"))
        live.requestFocus()

        UpdateWorker.schedule(this)
        if (Wg.enabled(this) && !Wg.isUp(this)) wg.start()
    }

    override fun onResume() { super.onResume(); refreshVpn(); Updater.check(this) }

    private fun open(kind: Kind, cat: String? = null) =
        startActivity(Intent(this, TvBrowseActivity::class.java).putExtra("kind", kind.ordinal).putExtra("cat", cat))

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE || keyCode == KeyEvent.KEYCODE_SEARCH) { openSearch(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // VPN – wie auf der Handy-Startseite
    private fun toggleVpn() {
        if (Wg.describe(this) == null) {
            if (!store.proxyConfigured) { toast("Kein VPN eingerichtet – siehe Einstellungen → VPN"); return }
            store.proxyOn = !store.proxyOn; Cache.clear(); refreshVpn(); return
        }
        if (Wg.enabled(this) && Wg.isUp(this)) {
            Wg.setEnabled(this, false)
            Thread { Wg.down(this); runOnUiThread { Cache.clear(); refreshVpn() } }.start()
        } else { Wg.setEnabled(this, true); vpnTile.text = "VPN …"; wg.start() }
    }
    private fun refreshVpn() {
        if (!::vpnTile.isInitialized) return
        val hasWg = Wg.describe(this) != null
        val on = if (hasWg) Wg.isUp(this) else store.proxyOn && store.proxyConfigured
        vpnTile.text = if (!hasWg && !store.proxyConfigured) "VPN: –" else if (on) "VPN: an" else "VPN: aus"
        vpnTile.setTextColor(if (on) 0xFF6EE7A0.toInt() else Tv.TEXT)
    }
}

// ======================================================================= live / movies / series
class TvBrowseActivity : TvBase() {
    private lateinit var kind: Kind
    private lateinit var catRv: RecyclerView
    private lateinit var listRv: RecyclerView
    private lateinit var catAdapter: TvRowAdapter
    private var rowAdapter: TvRowAdapter? = null
    private var posterAdapter: TvPosterAdapter? = null
    private lateinit var subTitle: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoText: TextView
    private var infoImg: ImageView? = null
    private var previewView: PlayerView? = null
    private var preview: ExoPlayer? = null
    private var cats: List<TvRow> = emptyList()
    private var entries: List<Entry> = emptyList()
    private var catIndex = 0
    private var focused = 0
    private val h = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var digits = ""
    private val S_SEARCH = "__search"; private val S_ALL = "__all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = Kind.entries[intent.getIntExtra("kind", 0)]
        val d = { v: Int -> Tv.dp(this, v) }
        val head = header(kind.title)
        subTitle = Tv.text(this, "", 19f, Tv.MUTED); head.addView(subTitle, 1)
        root.addView(head)

        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(d(24), 0, d(24), 0) }
        catRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); setBackgroundResource(R.drawable.tv_panel); setPadding(d(8), d(8), d(8), d(8)); clipToPadding = false }
        catAdapter = TvRowAdapter({ i -> onCatFocus(i) }, { i -> onCatClick(i) })
        catRv.adapter = catAdapter
        cols.addView(catRv, LinearLayout.LayoutParams(d(210), -1).apply { marginEnd = d(12) })

        if (kind == Kind.LIVE) {
            listRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); setBackgroundResource(R.drawable.tv_panel); setPadding(d(8), d(8), d(8), d(8)); clipToPadding = false }
            rowAdapter = TvRowAdapter({ i -> onItemFocus(i) }, { i -> play(kind, entries, i) }).apply { showLogos = store.showLogos }
            listRv.adapter = rowAdapter
            cols.addView(listRv, LinearLayout.LayoutParams(d(340), -1).apply { marginEnd = d(12) })
            val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            previewView = PlayerView(this).apply { useController = false; setBackgroundColor(0xFF0B1324.toInt()); isFocusable = false }
            side.addView(previewView, LinearLayout.LayoutParams(-1, d(200)).apply { bottomMargin = d(12) })
            val epg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.tv_panel); setPadding(d(16), d(14), d(16), d(14)) }
            infoTitle = Tv.text(this, "", 17f, bold = true); infoText = Tv.text(this, "", 13f, Tv.MUTED)
            epg.addView(infoTitle); epg.addView(infoText)
            side.addView(epg, LinearLayout.LayoutParams(-1, 0, 1f))
            cols.addView(side, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundResource(R.drawable.tv_panel); setPadding(d(14), d(14), d(14), d(14)) }
            infoImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            info.addView(infoImg, LinearLayout.LayoutParams(d(80), d(118)).apply { marginEnd = d(16) })
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            infoTitle = Tv.text(this, "", 20f, bold = true).apply { maxLines = 2 }; infoText = Tv.text(this, "", 13f, Tv.BLUE)
            t.addView(infoTitle); t.addView(infoText); info.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            right.addView(info, LinearLayout.LayoutParams(-1, d(146)).apply { bottomMargin = d(8) })
            listRv = RecyclerView(this).apply { layoutManager = GridLayoutManager(context, 6); clipToPadding = false; setPadding(0, d(6), 0, d(6)) }
            posterAdapter = TvPosterAdapter({ i -> onItemFocus(i) }, { i -> play(kind, entries, i) })
            listRv.adapter = posterAdapter
            right.addView(listRv, LinearLayout.LayoutParams(-1, 0, 1f))
            cols.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root.addView(cols, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(if (kind == Kind.LIVE) Tv.hints(this, "OK Vollbild", "0–9 Sendernummer", "r:Favorit", "g:Sortierung", "y:Programm / Archiv", "b:Suche")
                     else Tv.hints(this, "OK " + (if (kind == Kind.SERIES) "Staffeln" else "abspielen"), "r:Favorit", "g:Sortierung", "b:Suche"))

        lifecycleScope.launch {
            infoTitle.text = "Lade …"
            Tv.load(this@TvBrowseActivity, kind).onSuccess { infoTitle.text = ""; buildCats(intent.getStringExtra("cat")) }
                .onFailure { infoTitle.text = "Laden fehlgeschlagen"; infoText.text = it.message ?: "" }
        }
    }

    override fun onPause() { super.onPause(); stopPreview() }
    override fun onDestroy() { super.onDestroy(); preview?.release(); preview = null }
    override fun onResume() { super.onResume(); if (entries.isNotEmpty() && kind == Kind.LIVE) { catAdapter.notifyDataSetChanged(); rowAdapter?.notifyDataSetChanged() } }

    private fun buildCats(want: String?) {
        val hidden = store.hiddenCats(kind)
        val locked = MainActivity.lockedCategories(store, kind)
        val vc = store.vaultCats(kind)
        val normal = Bouquets.normal(store, kind)
        val counts = normal.groupingBy { it.categoryId }.eachCount()
        val l = mutableListOf(
            TvRow(S_SEARCH, "Suche (alles)"),
            TvRow(MainActivity.CAT_FAV, "★ Favoriten", right = store.favorites(kind).size.toString()),
            TvRow(MainActivity.CAT_HIST, "Zuletzt gesehen"),
            TvRow(S_ALL, "Alle", right = normal.size.toString()))
        Bouquets.ordered(store, kind).filter { it.id !in hidden && it.id !in vc }.forEach {
            val lk = it.id in locked && !Cache.unlocked
            l.add(TvRow(it.id, (if (lk) "🔒 " else "") + it.name, right = if (lk) "" else (counts[it.id] ?: 0).toString()))
        }
        cats = l
        catAdapter.submit(l)
        val start = (want ?: store.p.getString("tv_cat_${kind.name}", S_ALL)).let { w -> l.indexOfFirst { it.id == w }.takeIf { it >= 0 } ?: 3 }
        selectCat(start, focusList = true)
    }

    private fun onCatFocus(i: Int) {
        val c = cats.getOrNull(i) ?: return
        // beim Blättern nur laden – Suche und gesperrte Kategorien erst mit OK
        if (i == catIndex || c.id == S_SEARCH || (c.id in MainActivity.lockedCategories(store, kind) && !Cache.unlocked)) return
        pending?.let { h.removeCallbacks(it) }
        pending = Runnable { selectCat(i, false) }.also { h.postDelayed(it, 250) }
    }
    private fun onCatClick(i: Int) {
        if (cats.getOrNull(i)?.id == S_SEARCH) { openSearch(); return }
        selectCat(i, true)
    }

    private fun selectCat(i: Int, focusList: Boolean) {
        val c = cats.getOrNull(i) ?: return
        val locked = MainActivity.lockedCategories(store, kind)
        if (c.id in locked && !Cache.unlocked) { Dialogs.askPin(this) { buildCats(c.id) }; return }
        val old = catIndex
        catIndex = i
        catAdapter.activated = i; catAdapter.notifyItemChanged(old); catAdapter.notifyItemChanged(i)
        subTitle.text = "  ›  " + c.title
        if (c.id != MainActivity.CAT_FAV && c.id != MainActivity.CAT_HIST) store.p.edit().putString("tv_cat_${kind.name}", c.id).apply()
        val all = Bouquets.normal(store, kind)
        val list = when (c.id) {
            MainActivity.CAT_FAV -> { val f = store.favorites(kind); (Cache.entries[kind] ?: emptyList()).filter { it.id in f } }
            MainActivity.CAT_HIST -> { val byId = (Cache.entries[kind] ?: emptyList()).associateBy { it.id }; store.history(kind).mapNotNull { byId[it] } }
            S_ALL -> sorted(all)
            else -> sorted((Cache.entries[kind] ?: emptyList()).filter { it.categoryId == c.id })
        }
        entries = list
        if (kind == Kind.LIVE) {
            rowAdapter?.submit(list.mapIndexed { n, e -> TvRow(e.id, e.name, sub = if (e.archive) "⏪ Archiv" else null, right = if (e.id in store.favorites(kind)) "★" else null, icon = e.icon, no = (n + 1).toString(), entry = e) })
        } else posterAdapter?.submit(list)
        listRv.scrollToPosition(0)
        if (list.isEmpty()) { infoTitle.text = "Keine Einträge"; infoText.text = ""; infoImg?.setImageDrawable(null) }
        if (focusList && list.isNotEmpty()) focusItem(0) else if (focusList) catRv.post { catRv.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() }
    }

    private fun sorted(l: List<Entry>): List<Entry> = when (store.p.getString("tv_sort_${kind.name}", "prov")) {
        "az" -> l.sortedBy { it.name.lowercase() }
        "za" -> l.sortedByDescending { it.name.lowercase() }
        else -> l
    }

    private fun focusItem(i: Int, tries: Int = 0) {
        if (tries == 0) listRv.scrollToPosition(i)
        listRv.postDelayed({
            val v = listRv.findViewHolderForAdapterPosition(i)?.itemView
            if (v != null) v.requestFocus() else if (tries < 5) focusItem(i, tries + 1)
        }, 60)
    }

    private fun onItemFocus(i: Int) {
        focused = i
        val e = entries.getOrNull(i) ?: return
        if (kind != Kind.LIVE) {
            infoTitle.text = e.name
            val catName = Cache.categories[kind]?.firstOrNull { it.id == e.categoryId }?.name ?: ""
            infoText.text = listOfNotNull(catName.ifEmpty { null }, if (e.id in store.favorites(kind)) "★ Favorit" else null, e.subtitle).joinToString("  ·  ")
            infoImg?.load(e.icon) { crossfade(false) }
            return
        }
        infoTitle.text = e.name; infoText.text = "Programm lädt …"
        pending?.let { h.removeCallbacks(it) }
        pending = Runnable { loadEpg(e); startPreview(e) }.also { h.postDelayed(it, 700) }
    }

    private fun loadEpg(e: Entry) {
        val api = Xtream.from(store)
        lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { try { api.shortEpg(e.id, 4) } catch (x: Exception) { emptyList() } }
            if (entries.getOrNull(focused) != e) return@launch
            val now = System.currentTimeMillis() / 1000
            val cur = l.filter { it.endTs > now }
            infoText.text = if (cur.isEmpty()) "Kein Programm verfügbar." else buildString {
                val a = cur[0]
                append("JETZT  ${Tv.hm(a.startTs)} – ${Tv.hm(a.endTs)}\n${a.title}\n")
                if (a.description.isNotBlank()) append("\n${a.description.take(220)}\n")
                cur.getOrNull(1)?.let { append("\nDANACH  ${Tv.hm(it.startTs)}\n${it.title}") }
            }
        }
    }

    private fun startPreview(e: Entry) {
        if (!store.p.getBoolean("tv_preview", true)) return
        val pv = previewView ?: return
        val p = preview ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(Net.client(this)).setUserAgent(store.userAgent)))
            .build().also { preview = it; pv.player = it }
        p.setMediaItem(MediaItem.fromUri(Play.url(this, Kind.LIVE, e)))
        p.prepare(); p.playWhenReady = true
    }
    private fun stopPreview() { pending?.let { h.removeCallbacks(it) }; preview?.stop() }

    override fun play(kind: Kind, list: List<Entry>, idx: Int) { stopPreview(); super.play(kind, list, idx) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val inList = listRv.hasFocus()
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_SEARCH -> { openSearch(); return true }
            KeyEvent.KEYCODE_PROG_RED -> { entries.getOrNull(focused)?.takeIf { inList }?.let { toggleFav(kind, it); selectCatKeep() }; return true }
            KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_MENU -> { sortMenu(); return true }
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO -> {
                if (kind == Kind.LIVE) entries.getOrNull(focused)?.let { stopPreview(); Dialogs.showEpg(this, it) }; return true
            }
        }
        if (kind == Kind.LIVE && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digits += (keyCode - KeyEvent.KEYCODE_0).toString()
            toast("Sender $digits")
            pending?.let { h.removeCallbacks(it) }
            pending = Runnable {
                val n = digits.toIntOrNull() ?: 0; digits = ""
                if (n in 1..entries.size) focusItem(n - 1) else toast("Sender $n gibt es hier nicht")
            }.also { h.postDelayed(it, 1500) }
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) && entries.isNotEmpty()) {
            val step = if (kind == Kind.LIVE) 8 else 18
            val up = keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP
            focusItem((focused + if (up) -step else step).coerceIn(0, entries.size - 1)); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun selectCatKeep() { val f = focused; selectCat(catIndex, false); focusItem(f) }

    private fun sortMenu() {
        val keys = arrayOf("prov", "az", "za"); val labels = arrayOf("Anbieter-Reihenfolge", "Name A–Z", "Name Z–A")
        val cur = keys.indexOf(store.p.getString("tv_sort_${kind.name}", "prov")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Sortierung").setSingleChoiceItems(labels, cur) { dlg, w ->
            store.p.edit().putString("tv_sort_${kind.name}", keys[w]).apply(); dlg.dismiss(); selectCat(catIndex, true)
        }.show()
    }
}

// ======================================================================= search
class TvSearchActivity : TvBase() {
    private lateinit var input: EditText
    private lateinit var adapter: TvRowAdapter
    private var results: List<TvRow> = emptyList()
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = { v: Int -> Tv.dp(this, v) }
        root.addView(header("Suche", "Sender, Filme und Serien – in allen Kategorien"))
        input = EditText(this).apply {
            hint = "Suchbegriff eingeben"; textSize = 20f; setTextColor(Tv.TEXT); setHintTextColor(Tv.MUTED); isSingleLine = true
            background = GradientDrawable().apply { setColor(0xFF0F1830.toInt()); cornerRadius = d(14).toFloat(); setStroke(d(2), 0xFF22325A.toInt()) }
            setPadding(d(18), d(12), d(18), d(12))
            setText(store.p.getString("tv_search", ""))
            setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(d(3), if (f) 0xFFEF2B33.toInt() else 0xFF22325A.toInt()) }
        }
        root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { setMargins(d(36), 0, d(36), d(10)) })
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); setBackgroundResource(R.drawable.tv_panel); setPadding(d(8), d(8), d(8), d(8)); clipToPadding = false }
        adapter = TvRowAdapter({}, { i -> open(i) }).apply { showLogos = true }
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(d(36), 0, d(36), 0) })
        root.addView(Tv.hints(this, "▼ zu den Ergebnissen", "OK öffnen", "Zurück zurück"))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { h.removeCallbacksAndMessages(null); h.postDelayed({ run() }, 400) }
        })
        input.requestFocus()
        lifecycleScope.launch {
            adapter.submit(listOf(TvRow("", "Lade Senderlisten …")))
            Kind.entries.forEach { Tv.load(this@TvSearchActivity, it) }
            run()
        }
    }

    private fun run() {
        val q = input.text.toString().trim()
        store.p.edit().putString("tv_search", q).apply()
        if (q.length < 2) { adapter.submit(listOf(TvRow("", "Mindestens 2 Buchstaben eingeben."))); results = emptyList(); return }
        val words = q.lowercase().split(Regex("\\s+"))
        val out = ArrayList<TvRow>()
        for (k in Kind.entries) {
            val label = when (k) { Kind.LIVE -> "Live-TV"; Kind.MOVIE -> "Film"; Kind.SERIES -> "Serie" }
            for (e in Bouquets.normal(store, k)) {
                if (out.size >= 300) break
                val n = e.name.lowercase()
                if (words.all { n.contains(it) }) out.add(TvRow(e.id, e.name, sub = label, icon = e.icon, entry = e, kind = k))
            }
        }
        results = out
        adapter.submit(if (out.isEmpty()) listOf(TvRow("", "Nichts gefunden.")) else out)
    }

    private fun open(i: Int) {
        val r = results.getOrNull(i) ?: return
        val k = r.kind ?: return
        val same = results.filter { it.kind == k }.mapNotNull { it.entry }
        play(k, same, same.indexOf(r.entry))
    }
}
