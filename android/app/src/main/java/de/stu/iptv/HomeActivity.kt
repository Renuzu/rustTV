package de.stu.iptv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Startseite: Auswahl Live-TV / Filme / Serien + „Zuletzt gesehen“ mit Bildern. */
class HomeActivity : AppCompatActivity() {

    private val wg = WgStarter(this, { this }) { err -> if (err != null) Wg.setEnabled(this, false); refreshVpn() }
    private var vpnBtn: com.google.android.material.button.MaterialButton? = null

    companion object {
        fun restart(a: Activity) {
            a.startActivity(Intent(a, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            a.finish()
        }
    }

    private lateinit var store: Store
    private lateinit var api: Xtream
    private val cardViews = HashMap<Kind, View>()
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (!store.loggedIn) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        api = Xtream.from(store)
        setContentView(R.layout.activity_home)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "rustTV"
        toolbar.subtitle = store.account?.name
        toolbar.inflateMenu(R.menu.home)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.h_search -> globalSearch()
                R.id.h_bouquets -> startActivity(Intent(this, BouquetActivity::class.java))
                R.id.h_multiview -> startActivity(Intent(this, MultiViewActivity::class.java))
                R.id.h_accounts -> Dialogs.accounts(this) { restart(this) }
                R.id.h_accinfo -> Dialogs.accountInfo(this)
                R.id.h_reload -> { Cache.clear(); loadAll() }
                R.id.h_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }

        Design.apply(this, findViewById<View>(R.id.content).parent.parent as View, toolbar)
        val cards = findViewById<LinearLayout>(R.id.cards)
        // Design-Auswahl als Chips
        val dScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val dGroup = com.google.android.material.chip.ChipGroup(this).apply { isSingleLine = true; isSingleSelection = true; isSelectionRequired = true }
        dGroup.addView(TextView(this).apply { text = "Design:"; setTextColor(Design.colors(store.design).muted); gravity = android.view.Gravity.CENTER_VERTICAL })
        Design.NAMES.forEachIndexed { i, name ->
            val chip = designChip(name, i == store.design)
            chip.setOnClickListener { if (store.design != i) { store.design = i; recreate() } }
            dGroup.addView(chip)
        }
        dScroll.addView(dGroup)
        cards.addView(dScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
        // VPN-Knopf (nur sichtbar, wenn eine WireGuard-Datei importiert ist)
        vpnBtn = com.google.android.material.button.MaterialButton(this).apply {
            isAllCaps = false
            textSize = 16f
            setOnClickListener { toggleVpn() }
        }
        cards.addView(vpnBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() })
        rows = findViewById(R.id.rows)
        val inf = LayoutInflater.from(this)
        val icons = mapOf(Kind.LIVE to R.drawable.ic_live, Kind.MOVIE to R.drawable.ic_movie, Kind.SERIES to R.drawable.ic_series)
        val bgs = mapOf(Kind.LIVE to R.drawable.card_live, Kind.MOVIE to R.drawable.card_movie, Kind.SERIES to R.drawable.card_series)
        Kind.entries.forEach { k ->
            val v = inf.inflate(R.layout.home_card, cards, false)
            v.setBackgroundResource(bgs[k]!!)
            v.clipToOutline = true
            v.findViewById<View>(R.id.shade).setBackgroundResource(bgs[k]!!)
            v.findViewById<View>(R.id.shade).alpha = 0.78f
            v.findViewById<ImageView>(R.id.icon).setImageResource(icons[k]!!)
            v.findViewById<TextView>(R.id.label).text = k.title
            v.findViewById<TextView>(R.id.count).text = "wird geladen …"
            v.setOnClickListener { openList(k) }
            cards.addView(v)
            cardViews[k] = v
        }
        // Tresor-Kachel (PIN-geschützt)
        val vaultCard = inf.inflate(R.layout.home_card, cards, false)
        vaultCard.background = Design.rounded(Design.colors(store.design).surface, 16f, this)
        vaultCard.clipToOutline = true
        vaultCard.findViewById<TextView>(R.id.label).text = "🔐 Tresor"
        vaultCard.findViewById<TextView>(R.id.count).text = "Geschützte Inhalte – PIN nötig"
        vaultCard.findViewById<ImageView>(R.id.icon).visibility = View.GONE
        vaultCard.setOnClickListener { openVault() }
        cards.addView(vaultCard)

        if (savedInstanceState == null && store.startLast && store.lastLive.isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("kind", Kind.LIVE.ordinal).putExtra("autostart", true))
        }
        loadAll()
        UpdateWorker.schedule(this)
        if (intent.getBooleanExtra("check_update", false)) Updater.check(this, manual = true)
        askNotificationPermission()
        // WireGuard nur für rustTV: beim Start verbinden, wenn eingeschaltet
        if (Wg.enabled(this) && !Wg.isUp(this)) wg.start()
    }

    override fun onResume() {
        super.onResume()
        refreshVpn()
        Updater.check(this)   // bei jedem Öffnen (höchstens alle 5 Minuten)
        if (Kind.entries.all { Cache.entries.containsKey(it) }) showAll()
    }

    private fun designChip(name: String, on: Boolean) = com.google.android.material.chip.Chip(this).apply {
        text = name
        isCheckable = true
        isChecked = on
        isCheckedIconVisible = false
        val c = Design.colors(store.design)
        chipBackgroundColor = Design.tint(if (on) Design.ACCENT else c.surface)
        setTextColor(0xFFFFFFFF.toInt())
        chipStrokeWidth = 0f
    }

    private fun openList(k: Kind) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("kind", k.ordinal))
    }

    /** Lädt alle drei Listen parallel (falls noch nicht im Speicher). */
    private fun loadAll() {
        showAll()
        lifecycleScope.launch {
            val jobs = Kind.entries.filter { !Cache.entries.containsKey(it) }.map { k ->
                k to async(Dispatchers.IO) {
                    try { Result.success(api.categories(k) to api.entries(k)) } catch (e: Exception) { Result.failure(e) }
                }
            }
            jobs.forEach { (k, job) ->
                job.await().onSuccess { (c, e) ->
                    Cache.categories[k] = c; Cache.entries[k] = e
                }.onFailure {
                    cardViews[k]?.findViewById<TextView>(R.id.count)?.text = "Fehler beim Laden – tippen zum Öffnen"
                }
                showAll()
            }
        }
    }

    /** Einträge außerhalb des Tresors, die der Jugendschutz gerade erlaubt. */
    private fun visible(k: Kind): List<Entry> = Bouquets.normal(store, k)

    private fun openVault() {
        val empty = Kind.entries.all { store.vaultCats(it).isEmpty() && store.vaultItems(it).isEmpty() }
        if (empty) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("🔐 Tresor ist leer")
                .setMessage("So legst du Inhalte hinein:\n• Menü → Bouquets verwalten → bei einer Kategorie 🔐 antippen\n• oder einen Sender/Film lange drücken → „In Tresor verschieben“")
                .setPositiveButton("OK", null).show()
            return
        }
        Dialogs.askPin(this, unlock = false) {
            val k = Kind.entries.firstOrNull { store.vaultCats(it).isNotEmpty() || store.vaultItems(it).isNotEmpty() } ?: Kind.LIVE
            startActivity(Intent(this, MainActivity::class.java).putExtra("kind", k.ordinal).putExtra("vault", true))
        }
    }

    private fun showAll() {
        Kind.entries.forEach { k ->
            val list = Cache.entries[k] ?: return@forEach
            val card = cardViews[k] ?: return@forEach
            card.findViewById<TextView>(R.id.count).text = "${list.size} ${if (k == Kind.LIVE) "Sender" else "Titel"}" +
                (store.favorites(k).size.takeIf { it > 0 }?.let { " · $it Favoriten" } ?: "")
            fillCollage(card, k)
        }
        buildRows()
    }

    /** Hintergrund der Kachel: Cover der zuletzt gesehenen (oder ersten) Titel. */
    private fun fillCollage(card: View, k: Kind) {
        val box = card.findViewById<LinearLayout>(R.id.collage)
        if (!store.showLogos) { box.removeAllViews(); return }
        val vis = visible(k)
        val byId = vis.associateBy { it.id }
        val pics = (store.history(k).mapNotNull { byId[it] } + vis)
            .filter { !it.icon.isNullOrBlank() }.distinctBy { it.id }.take(4)
        val key = pics.joinToString { it.id }
        if (box.tag == key) return
        box.tag = key
        box.removeAllViews()
        pics.forEach { e ->
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            iv.load(e.icon) { crossfade(true) }
            box.addView(iv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Waagerechte Reihen „Zuletzt gesehen“ mit Bildern. */
    private fun buildRows() {
        rows.removeAllViews()
        val d = resources.displayMetrics.density
        Kind.entries.forEach { k ->
            val vis = visible(k).associateBy { it.id }
            val recent = store.history(k).mapNotNull { vis[it] }.take(20)
            if (recent.isEmpty()) return@forEach

            val title = TextView(this).apply {
                text = "Zuletzt gesehen – ${k.title}"
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (16 * d).toInt(), 0, (8 * d).toInt())
            }
            rows.addView(title)

            val rv = RecyclerView(this)
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            val adapter = EntryAdapter(
                onClick = { e -> open(k, recent, e) },
                onLongClick = { e -> open(k, recent, e) },
                onFavToggle = null, onStartDrag = null
            )
            adapter.grid = true
            adapter.ratio = if (k == Kind.LIVE) 1f else 1.5f
            adapter.fixedWidth = ((if (k == Kind.LIVE) 96 else 110) * d).toInt()
            adapter.categoryNames = (Cache.categories[k] ?: emptyList()).associate { it.id to it.name }
            adapter.submit(recent.map { e ->
                val pos = store.position(e.id)
                if (pos > 0) e.copy(subtitle = "▶ weiterschauen") else e
            })
            rv.adapter = adapter
            rows.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun open(k: Kind, list: List<Entry>, e: Entry) {
        if (k == Kind.SERIES) {
            store.addHistory(Kind.SERIES, e.id)
            startActivity(Intent(this, SeriesActivity::class.java).putExtra("id", e.id).putExtra("name", e.name))
        } else Play.start(this, k, list, list.indexOfFirst { it.id == e.id }.coerceAtLeast(0))
    }

    /** Suche über Live-TV, Filme und Serien gleichzeitig. */
    private fun globalSearch() {
        val all = Kind.entries.flatMap { k -> visible(k).map { k to it } }
        if (all.isEmpty()) return
        val tagged = all.map { (k, e) -> e.copy(subtitle = k.title) }
        val kindOf = HashMap<Entry, Pair<Kind, Entry>>()
        tagged.forEachIndexed { i, t -> kindOf[t] = all[i] }
        Dialogs.pickEntry(this, "Überall suchen", tagged) { t ->
            val (k, e) = kindOf[t] ?: return@pickEntry
            open(k, listOf(e), e)
        }
    }

    /** Android 13+: Erlaubnis für die Update-Benachrichtigung (einmalig fragen). */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val p = getSharedPreferences("updater", MODE_PRIVATE)
        if (p.getBoolean("asked_notify", false)) return
        p.edit().putBoolean("asked_notify", true).apply()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    // ---------------- VPN: WireGuard (nur rustTV) oder Proxy-Zugang ----------------
    private fun toggleVpn() {
        val store = Store(this)
        if (Wg.describe(this) == null) {
            // Proxy-Zugang an/aus
            store.proxyOn = !store.proxyOn
            Cache.clear()
            Toast.makeText(this, if (store.proxyOn) "VPN/Proxy eingeschaltet" else "VPN/Proxy ausgeschaltet", Toast.LENGTH_SHORT).show()
            refreshVpn()
            return
        }
        if (Wg.enabled(this) && Wg.isUp(this)) {
            Wg.setEnabled(this, false)
            vpnBtn?.isEnabled = false
            Thread {
                Wg.down(this)
                runOnUiThread { Cache.clear(); refreshVpn(); Toast.makeText(this, "VPN getrennt", Toast.LENGTH_SHORT).show() }
            }.start()
        } else {
            Wg.setEnabled(this, true)
            vpnBtn?.apply { isEnabled = false; text = "⏳ VPN wird verbunden …" }
            wg.start()
        }
    }

    private fun refreshVpn() {
        val b = vpnBtn ?: return
        val store = Store(this)
        val hasWg = Wg.describe(this) != null
        if (!hasWg && !store.proxyConfigured) { b.visibility = View.GONE; return }
        b.visibility = View.VISIBLE
        b.isEnabled = true
        val on = if (hasWg) {
            val up = Wg.isUp(this)
            if (!up && Wg.enabled(this)) {
                b.text = "⏳ VPN wird verbunden …"
                b.postDelayed({ if (!isFinishing) refreshVpn() }, 1500)
                return
            }
            up
        } else store.proxyOn
        b.text = if (on) "🔒 VPN an – tippen zum Trennen" else "🔓 VPN aus – tippen zum Verbinden"
        b.setBackgroundColor(if (on) 0xFF2E7D32.toInt() else 0xFF455A64.toInt())
    }
}
