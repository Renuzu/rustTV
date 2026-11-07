package de.stu.iptv

import android.annotation.SuppressLint
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import java.util.Collections

/** Bouquet-Verwaltung: Kategorien sortieren, ein-/ausblenden, sperren, in den Tresor legen. */
class BouquetActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var adapter: BAdapter
    private lateinit var touch: ItemTouchHelper
    private lateinit var progress: ProgressBar
    private var kind = Kind.LIVE
    private var pinOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        kind = Kind.entries.getOrNull(intent.getIntExtra("kind", 0)) ?: Kind.LIVE

        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            title = "Bouquets verwalten"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.bouquet)
            setOnMenuItemClickListener { onMenu(it.itemId); true }
        }
        val tabs = TabLayout(this)
        Kind.entries.forEach { tabs.addTab(tabs.newTab().setText(it.title)) }
        tabs.getTabAt(kind.ordinal)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { kind = Kind.entries[t.position]; load() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
        val hint = TextView(this).apply {
            text = "≡ ziehen = Reihenfolge   👁 ein/aus   🔒 PIN nötig   🔐 in den Tresor"
            textSize = 12f; alpha = 0.7f
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
        }
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        adapter = BAdapter()
        rv.adapter = adapter
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(r: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                Collections.swap(adapter.items, a.bindingAdapterPosition, b.bindingAdapterPosition)
                adapter.notifyItemMoved(a.bindingAdapterPosition, b.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(v: RecyclerView.ViewHolder, dir: Int) {}
            override fun clearView(r: RecyclerView, v: RecyclerView.ViewHolder) {
                super.clearView(r, v); saveOrder()
            }
        })
        touch.attachToRecyclerView(rv)
        progress = ProgressBar(this).apply { visibility = View.GONE }

        root.addView(toolbar); root.addView(tabs); root.addView(hint); root.addView(progress)
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        val k = kind
        if (Cache.categories.containsKey(k)) { show(); return }
        progress.visibility = View.VISIBLE
        adapter.items.clear(); adapter.notifyDataSetChanged()
        lifecycleScope.launch {
            val api = Xtream.from(store)
            val res = withContext(Dispatchers.IO) { try { Result.success(api.categories(k)) } catch (e: Exception) { Result.failure(e) } }
            progress.visibility = View.GONE
            res.onSuccess { Cache.categories[k] = it; if (k == kind) show() }
                .onFailure { Toast.makeText(this@BouquetActivity, "Laden fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun show() {
        adapter.items.clear()
        adapter.items.addAll(Bouquets.ordered(store, kind))
        val counts = Cache.entries[kind]?.groupingBy { it.categoryId }?.eachCount() ?: emptyMap()
        adapter.counts = counts
        adapter.notifyDataSetChanged()
    }

    private fun saveOrder() = store.setCatOrder(kind, adapter.items.map { it.id })

    /** PIN einmal pro Besuch abfragen, bevor Sperre/Tresor geändert werden. */
    private fun withPin(action: () -> Unit) {
        if (pinOk) { action(); return }
        Dialogs.askPin(this, unlock = false) { pinOk = true; action() }
    }

    private fun prefixes(): List<Pair<String, Int>> =
        adapter.items.mapNotNull { Bouquets.prefix(it.name) }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.toList().sortedByDescending { it.second }

    private fun onMenu(id: Int) {
        when (id) {
            R.id.b_country -> {
                val p = prefixes()
                if (p.isEmpty()) { Toast.makeText(this, "Keine Länderkürzel erkannt", Toast.LENGTH_SHORT).show(); return }
                MaterialAlertDialogBuilder(this).setTitle("Welches Land nach oben?")
                    .setItems(p.map { "${it.first}  (${it.second} Kategorien)" }.toTypedArray()) { _, w ->
                        val code = p[w].first
                        val (top, rest) = adapter.items.partition { Bouquets.prefix(it.name) == code }
                        adapter.items.clear(); adapter.items.addAll(top + rest)
                        adapter.notifyDataSetChanged(); saveOrder()
                        Toast.makeText(this, "$code steht jetzt oben", Toast.LENGTH_SHORT).show()
                    }.show()
            }
            R.id.b_hidecountry -> {
                val p = prefixes()
                if (p.isEmpty()) { Toast.makeText(this, "Keine Länderkürzel erkannt", Toast.LENGTH_SHORT).show(); return }
                val hidden = store.hiddenCats(kind)
                val checked = BooleanArray(p.size) { i ->
                    adapter.items.any { Bouquets.prefix(it.name) == p[i].first && it.id !in hidden }
                }
                MaterialAlertDialogBuilder(this).setTitle("Länder anzeigen (Haken = sichtbar)")
                    .setMultiChoiceItems(p.map { "${it.first}  (${it.second})" }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("Speichern") { _, _ ->
                        val h = store.hiddenCats(kind)
                        p.forEachIndexed { i, (code, _) ->
                            adapter.items.filter { Bouquets.prefix(it.name) == code }.forEach {
                                if (checked[i]) h.remove(it.id) else h.add(it.id)
                            }
                        }
                        store.setHiddenCats(kind, h); adapter.notifyDataSetChanged()
                    }.setNegativeButton("Abbrechen", null).show()
            }
            R.id.b_az -> { adapter.items.sortBy { it.name.lowercase() }; adapter.notifyDataSetChanged(); saveOrder() }
            R.id.b_za -> { adapter.items.sortByDescending { it.name.lowercase() }; adapter.notifyDataSetChanged(); saveOrder() }
            R.id.b_showall -> { store.setHiddenCats(kind, emptySet()); adapter.notifyDataSetChanged() }
            R.id.b_reset -> { store.setCatOrder(kind, emptyList()); show() }
        }
    }

    private inner class BAdapter : RecyclerView.Adapter<BAdapter.VH>() {
        val items = ArrayList<Category>()
        var counts: Map<String, Int> = emptyMap()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val drag: ImageView = v.findViewById(R.id.drag)
            val name: TextView = v.findViewById(R.id.name)
            val sub: TextView = v.findViewById(R.id.sub)
            val eye: TextView = v.findViewById(R.id.eye)
            val lock: TextView = v.findViewById(R.id.lock)
            val vault: TextView = v.findViewById(R.id.vault)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_bouquet, p, false))

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            val hidden = c.id in store.hiddenCats(kind)
            val locked = c.id in MainActivity.lockedCategories(store, kind)
            val inVault = c.id in store.vaultCats(kind)
            h.name.text = c.name
            h.name.paintFlags = if (hidden) h.name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else h.name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            h.name.alpha = if (hidden) 0.5f else 1f
            h.sub.text = listOfNotNull(
                counts[c.id]?.let { "$it Einträge" },
                if (hidden) "ausgeblendet" else null,
                if (locked) "gesperrt" else null,
                if (inVault) "im Tresor" else null
            ).joinToString(" · ")
            h.eye.alpha = if (hidden) 0.25f else 1f
            h.lock.alpha = if (locked) 1f else 0.25f
            h.vault.alpha = if (inVault) 1f else 0.25f

            h.drag.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(h); false
            }
            h.eye.setOnClickListener {
                val s = store.hiddenCats(kind); if (!s.add(c.id)) s.remove(c.id)
                store.setHiddenCats(kind, s); notifyItemChanged(h.bindingAdapterPosition)
            }
            h.lock.setOnClickListener {
                withPin {
                    val s = store.lockedCats(kind)
                    if (locked && c.id !in s) {
                        Toast.makeText(this@BouquetActivity, "Automatisch gesperrt (Erwachsenen-Inhalt) – in den Einstellungen änderbar", Toast.LENGTH_LONG).show()
                    } else {
                        if (!s.add(c.id)) s.remove(c.id)
                        store.setLockedCats(kind, s); Cache.unlocked = false
                    }
                    notifyItemChanged(h.bindingAdapterPosition)
                }
            }
            h.vault.setOnClickListener {
                withPin {
                    val s = store.vaultCats(kind); if (!s.add(c.id)) s.remove(c.id)
                    store.setVaultCats(kind, s); notifyItemChanged(h.bindingAdapterPosition)
                }
            }
        }
    }
}
