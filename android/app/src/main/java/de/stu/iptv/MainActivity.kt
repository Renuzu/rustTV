package de.stu.iptv

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Cache {
    val entries = HashMap<Kind, List<Entry>>()
    val categories = HashMap<Kind, List<Category>>()
    /** Nach PIN-Eingabe bis zum App-Ende entsperrt. */
    var unlocked = false
    fun clear() { entries.clear(); categories.clear() }
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val SORT_NAME_ASC = 0
        const val SORT_NAME_DESC = 1
        const val SORT_CATEGORY = 2
        const val SORT_CUSTOM = 3
        val SORT_LABELS = arrayOf("Name A–Z", "Name Z–A", "Nach Kategorie", "Eigene Reihenfolge")
        const val CAT_FAV = "__fav"
        const val CAT_HIST = "__hist"
        private val ADULT = Regex("adult|xxx|18\\+|porn|erotik|\\bsex", RegexOption.IGNORE_CASE)

        /** Gesperrte Kategorien (manuell + automatisch erkannte Erwachsenen-Kategorien). */
        fun lockedCategories(store: Store, kind: Kind): Set<String> {
            val set = store.lockedCats(kind)
            if (store.autoLockAdult) Cache.categories[kind]?.forEach { if (ADULT.containsMatchIn(it.name)) set.add(it.id) }
            return set
        }
    }

    private lateinit var store: Store
    private lateinit var api: Xtream
    private lateinit var adapter: EntryAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var design = 0
    /** Design C: Kachel gewählt → Liste zeigen */
    private var picked = false
    private data class CatOpt(val id: String?, val label: String, val count: Int, val locked: Boolean)
    private var opts: List<CatOpt> = emptyList()
    private lateinit var search: EditText
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var info: TextView
    private lateinit var toolbar: MaterialToolbar

    private var kind = Kind.LIVE
    private var selectedCategory: String? = null
    private var autoStartPending = false
    /** true = Tresor-Ansicht (nur geschützte Inhalte) */
    private var vault = false
    private lateinit var list: RecyclerView
    private val divider by lazy { DividerItemDecoration(this, DividerItemDecoration.VERTICAL) }
    private var gridMode: Boolean? = null

    /** Liste oder Bilder-Raster einstellen. */
    private fun applyLayout() {
        val grid = kind != Kind.LIVE && store.posterGrid
        if (grid == gridMode) return
        gridMode = grid
        adapter.grid = grid
        list.removeItemDecoration(divider)
        if (grid) {
            val dp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, (dp / 120).toInt().coerceAtLeast(3))
            list.setPadding(8, 8, 8, 8)
        } else {
            list.layoutManager = LinearLayoutManager(this)
            list.addItemDecoration(divider)
            list.setPadding(0, 0, 0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.loggedIn) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        api = Xtream.from(store)
        setContentView(R.layout.activity_main)
        autoStartPending = savedInstanceState == null && intent.getBooleanExtra("autostart", false) && !store.external
        kind = Kind.entries.getOrNull(intent.getIntExtra("kind", 0)) ?: Kind.LIVE
        vault = intent.getBooleanExtra("vault", false)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = title()
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }
        search = findViewById(R.id.search)
        design = store.design
        Design.apply(this, findViewById(R.id.root), toolbar)
        search.background = Design.rounded(Design.colors(design).surface2, 12f, this)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        info = findViewById(R.id.info)

        setupMenu()

        list = findViewById(R.id.list)
        adapter = EntryAdapter(
            onClick = { open(it) },
            onLongClick = { showItemMenu(it) },
            onFavToggle = { toggleFav(it) },
            onStartDrag = { touchHelper.startDrag(it) }
        )
        list.adapter = adapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (!adapter.dragEnabled) return false
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (adapter.dragEnabled) saveCustomOrder()
            }
        })
        touchHelper.attachToRecyclerView(list)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        Kind.entries.forEach { tabs.addTab(tabs.newTab().setText(it.title)) }
        tabs.getTabAt(kind.ordinal)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                kind = Kind.entries[tab.position]
                toolbar.title = title()
                selectedCategory = null
                picked = false
                search.setText("")
                load()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<View>(R.id.catBack).setOnClickListener { picked = false; updateCatViews(); refresh() }
        findViewById<View>(R.id.catBig).setOnClickListener { showCategorySheet() }
        findViewById<RecyclerView>(R.id.catGrid).layoutManager = androidx.recyclerview.widget.GridLayoutManager(this,
            ((resources.displayMetrics.widthPixels / resources.displayMetrics.density) / 160).toInt().coerceAtLeast(2))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (design == 2 && picked) { picked = false; updateCatViews(); refresh() } else finish()
            }
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (design == 2) updateCatViews(); refresh() }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && Cache.entries.containsKey(kind)) refresh()
    }

    private fun title() = if (vault) "🔐 Tresor – ${kind.title}" else kind.title

    private fun setupMenu() {
        toolbar.inflateMenu(R.menu.main)
        toolbar.menu.findItem(R.id.m_favfirst).isChecked = store.favFirst
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.m_sort -> showSortDialog()
                R.id.m_favfirst -> { store.favFirst = !store.favFirst; item.isChecked = store.favFirst; refresh() }
                R.id.m_multiview -> startActivity(Intent(this, MultiViewActivity::class.java))
                R.id.m_bouquets -> startActivity(Intent(this, BouquetActivity::class.java).putExtra("kind", kind.ordinal))
                R.id.m_accounts -> Dialogs.accounts(this) { HomeActivity.restart(this) }
                R.id.m_accinfo -> Dialogs.accountInfo(this)
                R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.m_reset_order -> MaterialAlertDialogBuilder(this)
                    .setMessage("Eigene Reihenfolge für „${kind.title}“ zurücksetzen?")
                    .setPositiveButton("Zurücksetzen") { _, _ -> store.setCustomOrder(kind, emptyList()); refresh() }
                    .setNegativeButton("Abbrechen", null).show()
                R.id.m_reload -> { Cache.clear(); load() }
            }
            true
        }
    }

    private fun showSortDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Sortieren")
            .setSingleChoiceItems(SORT_LABELS, store.sortMode) { d, which ->
                store.sortMode = which; d.dismiss(); refresh()
            }.show()
    }

    private fun load() {
        val k = kind
        if (Cache.entries.containsKey(k)) { setupCategories(); refresh(); return }
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        adapter.submit(emptyList())
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(api.categories(k) to api.entries(k)) } catch (e: Exception) { Result.failure(e) }
            }
            if (k != kind) return@launch
            progress.visibility = View.GONE
            result.onSuccess { (cats, ents) ->
                Cache.categories[k] = cats
                Cache.entries[k] = ents
                setupCategories()
                refresh()
                if (k == Kind.LIVE && autoStartPending) {
                    autoStartPending = false
                    val idx = adapter.items.indexOfFirst { it.id == store.lastLive }
                    if (idx >= 0) Play.start(this@MainActivity, Kind.LIVE, ArrayList(adapter.items), idx)
                }
            }.onFailure {
                empty.text = "Laden fehlgeschlagen:\n${it.message}\n\nMenü → Neu laden"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun setupCategories() {
        val hidden = store.hiddenCats(kind)
        val locked = lockedCategories(store, kind)
        val vc = store.vaultCats(kind)
        val vaultItemCats = if (vault) {
            val vi = store.vaultItems(kind)
            (Cache.entries[kind] ?: emptyList()).filter { it.id in vi }.map { it.categoryId }.toSet()
        } else emptySet()
        val cats = Bouquets.ordered(store, kind).filter {
            if (vault) it.id in vc || it.id in vaultItemCats else it.id !in hidden && it.id !in vc
        }
        val vi = store.vaultItems(kind)
        val counts = HashMap<String, Int>()
        (Cache.entries[kind] ?: emptyList()).forEach {
            if (Bouquets.isVault(it, vc, vi) == vault) counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1
        }
        opts = listOf(
            CatOpt(null, if (vault) "Alles im Tresor" else "Alle", counts.values.sum(), false),
            CatOpt(CAT_FAV, "★ Favoriten", store.favorites(kind).size, false),
            CatOpt(CAT_HIST, "Zuletzt gesehen", store.history(kind).size, false),
        ) + cats.map { CatOpt(it.id, it.name, counts[it.id] ?: 0, !vault && it.id in locked && !Cache.unlocked) }
        updateCatViews()
    }

    private fun label(o: CatOpt) = if (o.locked) "🔒 ${o.label}" else o.label

    /** Kategorie gewählt (mit PIN-Abfrage bei gesperrten Kategorien). */
    private fun choose(id: String?) {
        if (!vault && id != null && !Cache.unlocked && id in lockedCategories(store, kind)) {
            Dialogs.askPin(this) { selectedCategory = id; picked = true; setupCategories(); refresh() }
            return
        }
        selectedCategory = id
        picked = true
        updateCatViews()
        refresh()
    }

    /** Zeigt die Kategorie-Auswahl im gewählten Design. */
    private fun updateCatViews() {
        val c = Design.colors(design)
        val d = resources.displayMetrics.density
        val cur = opts.firstOrNull { it.id == selectedCategory } ?: opts.firstOrNull()
        val chipsScroll = findViewById<View>(R.id.catChipsScroll)
        val chips = findViewById<com.google.android.material.chip.ChipGroup>(R.id.catChips)
        val railScroll = findViewById<View>(R.id.railScroll)
        val rail = findViewById<android.widget.LinearLayout>(R.id.rail)
        val backRow = findViewById<View>(R.id.catBackRow)
        val big = findViewById<com.google.android.material.button.MaterialButton>(R.id.catBig)
        val grid = findViewById<RecyclerView>(R.id.catGrid)
        chipsScroll.visibility = if (design == 0) View.VISIBLE else View.GONE
        railScroll.visibility = if (design == 1) View.VISIBLE else View.GONE
        big.visibility = if (design == 3) View.VISIBLE else View.GONE
        val tilesMode = design == 2 && !picked && search.text.isNullOrBlank()
        backRow.visibility = if (design == 2 && !tilesMode) View.VISIBLE else View.GONE
        grid.visibility = if (tilesMode) View.VISIBLE else View.GONE
        list.visibility = if (tilesMode) View.GONE else View.VISIBLE
        when (design) {
            0 -> {
                chips.removeAllViews()
                opts.forEach { o ->
                    val on = o.id == selectedCategory
                    chips.addView(com.google.android.material.chip.Chip(this).apply {
                        text = "${label(o)}  ${o.count}"
                        isCheckable = true; isChecked = on; isCheckedIconVisible = false
                        chipBackgroundColor = Design.tint(if (on) Design.ACCENT else c.surface2)
                        setTextColor(0xFFFFFFFF.toInt())
                        setOnClickListener { choose(o.id) }
                    })
                }
            }
            1 -> {
                railScroll.setBackgroundColor(c.surface2)
                rail.removeAllViews()
                rail.addView(TextView(this).apply {
                    text = "KATEGORIEN"; textSize = 11f; setTextColor(c.muted); setPadding((8 * d).toInt(), (4 * d).toInt(), 0, (6 * d).toInt())
                })
                opts.forEach { o ->
                    val on = o.id == selectedCategory
                    rail.addView(TextView(this).apply {
                        text = "${label(o)}\n${o.count}"
                        textSize = 13f
                        setTextColor(0xFFFFFFFF.toInt())
                        minHeight = (48 * d).toInt()
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
                        if (on) { background = Design.rounded(Design.ACCENT, 10f, this@MainActivity); setTypeface(typeface, android.graphics.Typeface.BOLD) }
                        else setBackgroundResource(android.R.drawable.list_selector_background)
                        isClickable = true
                        setOnClickListener { choose(o.id) }
                    }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (2 * d).toInt() })
                }
            }
            2 -> {
                findViewById<TextView>(R.id.catTitle).text = cur?.let { label(it) } ?: ""
                grid.adapter = TileAdapter(opts, c) { choose(it) }
            }
            3 -> {
                big.text = "${cur?.let { label(it) } ?: ""}  ▾"
                big.strokeColor = Design.tint(Design.ACCENT)
                big.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    /** Design D: Kategorien als Blatt von unten, mit Suche. */
    private fun showCategorySheet() {
        val d = resources.displayMetrics.density
        val c = Design.colors(design)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            setBackgroundColor(c.surface2)
        }
        box.addView(TextView(this).apply { text = "Kategorie wählen"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, (8 * d).toInt()) })
        val q = EditText(this).apply { hint = "Kategorie suchen …"; isSingleLine = true; background = Design.rounded(c.surface, 12f, this@MainActivity); setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0) }
        box.addView(q, android.widget.LinearLayout.LayoutParams(-1, (48 * d).toInt()))
        val lv = android.widget.ListView(this)
        box.addView(lv, android.widget.LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.55).toInt()))
        fun fill() {
            val f = q.text.toString().trim().lowercase()
            val shown = opts.filter { f.isEmpty() || it.label.lowercase().contains(f) }
            lv.adapter = object : ArrayAdapter<CatOpt>(this, android.R.layout.simple_list_item_1, shown) {
                override fun getView(pos: Int, cv: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(pos, cv, parent) as TextView
                    val o = shown[pos]
                    v.text = "${label(o)}   ·  ${o.count}"
                    v.setTextColor(0xFFFFFFFF.toInt())
                    if (o.id == selectedCategory) v.background = Design.rounded(Design.ACCENT, 12f, this@MainActivity) else v.background = null
                    return v
                }
            }
            lv.setOnItemClickListener { _, _, pos, _ -> sheet.dismiss(); choose(shown[pos].id) }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill() }
        })
        fill()
        sheet.setContentView(box)
        sheet.show()
    }

    /** Design C: Kategorie-Kacheln. */
    private inner class TileAdapter(val items: List<CatOpt>, val c: Design.Colors, val onPick: (String?) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): RecyclerView.ViewHolder {
            val d = parent.resources.displayMetrics.density
            val v = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
                layoutParams = RecyclerView.LayoutParams(-1, (100 * d).toInt()).apply { setMargins((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt()) }
                isClickable = true
                addView(TextView(parent.context).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2 }, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
                addView(TextView(parent.context).apply { textSize = 12f })
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val o = items[pos]
            val v = h.itemView as android.widget.LinearLayout
            val fav = o.id == CAT_FAV
            v.background = Design.rounded(if (fav) 0xFFF2B807.toInt() else c.surface, 18f, this@MainActivity)
            val fg = if (fav) 0xFF1B1F27.toInt() else 0xFFFFFFFF.toInt()
            (v.getChildAt(0) as TextView).apply { text = label(o); setTextColor(fg) }
            (v.getChildAt(1) as TextView).apply { text = "${o.count}"; setTextColor(if (fav) fg else c.muted) }
            v.setOnClickListener { onPick(o.id) }
        }
    }

    /** Wendet Filter + Sortierung an und zeigt die Liste. */
    private fun refresh() {
        val all = Cache.entries[kind] ?: return
        val catNames = (Cache.categories[kind] ?: emptyList()).associate { it.id to it.name }
        val favs = store.favorites(kind)
        val hidden = store.hiddenCats(kind)
        val locked = if (Cache.unlocked || vault) emptySet() else lockedCategories(store, kind)
        val q = search.text.toString().trim().lowercase()
        val cat = selectedCategory
        val vc = store.vaultCats(kind)
        val vi = store.vaultItems(kind)

        var list = all.asSequence()
            .filter { Bouquets.isVault(it, vc, vi) == vault }
            .filter { vault || it.categoryId !in hidden || cat == CAT_FAV || cat == CAT_HIST }
            .filter { it.categoryId !in locked }
            .filter {
                when (cat) {
                    null -> true
                    CAT_FAV -> it.id in favs
                    CAT_HIST -> true
                    else -> it.categoryId == cat
                }
            }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .toList()

        if (cat == CAT_HIST) {
            val pos = store.history(kind).withIndex().associate { it.value to it.index }
            list = list.filter { it.id in pos }.sortedBy { pos[it.id] }
        } else {
            list = when (store.sortMode) {
                SORT_NAME_ASC -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                SORT_NAME_DESC -> list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
                SORT_CATEGORY -> {
                    val order = Bouquets.ordered(store, kind).withIndex().associate { it.value.id to it.index }
                    list.sortedWith(compareBy<Entry> { order[it.categoryId] ?: Int.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
                else -> {
                    val pos = fullCustomOrder().withIndex().associate { it.value to it.index }
                    list.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
                }
            }
            if (store.favFirst && cat != CAT_FAV) {
                val (f, rest) = list.partition { it.id in favs }
                list = f + rest
            }
        }

        applyLayout()
        adapter.favorites = favs
        adapter.categoryNames = catNames
        adapter.showLogos = store.showLogos
        adapter.dragEnabled = store.sortMode == SORT_CUSTOM && q.isEmpty() && cat != CAT_HIST
        adapter.submit(list)

        toolbar.subtitle = if (cat == CAT_HIST) "Zuletzt gesehen" else SORT_LABELS[store.sortMode]
        info.text = buildString {
            append("${list.size} Einträge")
            if (adapter.dragEnabled) append(" · ≡ gedrückt halten und ziehen")
            if (adapter.grid) append(" · lange drücken = Optionen")
            else if (store.sortMode == SORT_CUSTOM && q.isNotEmpty()) append(" · Verschieben nur ohne Suche")
            if (locked.isNotEmpty()) append(" · 🔒 gesperrte Kategorien ausgeblendet")
        }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) empty.text = "Keine Einträge"
    }

    /** Gespeicherte Reihenfolge + neue Einträge (in Server-Reihenfolge) hinten angehängt. */
    private fun fullCustomOrder(): List<String> {
        val all = Cache.entries[kind] ?: return emptyList()
        val allIds = all.map { it.id }
        val known = allIds.toHashSet()
        val saved = store.customOrder(kind).filter { it in known }
        val savedSet = saved.toHashSet()
        return saved + allIds.filter { it !in savedSet }
    }

    /** Neue Reihenfolge der sichtbaren Einträge in die Gesamtliste übernehmen. */
    private fun saveCustomOrder() {
        val global = fullCustomOrder().toMutableList()
        val shown = adapter.items.map { it.id }
        val shownSet = shown.toHashSet()
        val slots = global.indices.filter { global[it] in shownSet }
        slots.forEachIndexed { i, slot -> global[slot] = shown[i] }
        store.setCustomOrder(kind, global)
    }

    private fun toggleFav(e: Entry) {
        val favs = store.favorites(kind)
        if (!favs.add(e.id)) favs.remove(e.id)
        store.setFavorites(kind, favs)
        refresh()
    }

    private fun open(e: Entry, forceInternal: Boolean = false) {
        if (kind == Kind.SERIES) {
            startActivity(Intent(this, SeriesActivity::class.java)
                .putExtra("id", e.id).putExtra("name", e.name))
            store.addHistory(Kind.SERIES, e.id)
            return
        }
        val list = ArrayList(adapter.items)
        Play.start(this, kind, list, list.indexOfFirst { it.id == e.id }.coerceAtLeast(0), forceInternal)
    }

    private fun showItemMenu(e: Entry) {
        val isFav = e.id in store.favorites(kind)
        val opts = ArrayList<Pair<String, () -> Unit>>()
        when (kind) {
            Kind.SERIES -> opts += "Folgen anzeigen" to { open(e) }
            else -> {
                opts += "Im internen Player abspielen" to { open(e, forceInternal = true) }
                opts += "Mit externem Player öffnen" to {
                    store.addHistory(kind, e.id); Play.external(this, Play.url(this, kind, e), e.name)
                }
            }
        }
        if (kind == Kind.LIVE) {
            opts += (if (e.archive) "TV-Programm / Archiv (Catch-up)" else "TV-Programm") to { Dialogs.showEpg(this, e) }
            opts += "In Multi-View öffnen" to {
                startActivity(Intent(this, MultiViewActivity::class.java).putExtra("first", e.id))
            }
        }
        if (kind == Kind.MOVIE && store.position(e.id) > 0)
            opts += "Fortsetzen-Position löschen" to { store.setPosition(e.id, 0) }
        opts += (if (isFav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen") to { toggleFav(e) }
        val inVaultItem = e.id in store.vaultItems(kind)
        if (!vault || inVaultItem) opts += (if (inVaultItem) "🔐 Aus Tresor entfernen" else "🔐 In Tresor verschieben") to {
            Dialogs.askPin(this, unlock = false) {
                val vi = store.vaultItems(kind)
                if (!vi.add(e.id)) vi.remove(e.id)
                store.setVaultItems(kind, vi)
                setupCategories(); refresh()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }
}
