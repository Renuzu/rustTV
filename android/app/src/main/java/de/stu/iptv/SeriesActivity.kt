package de.stu.iptv

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesActivity : AppCompatActivity() {

    private var episodes: List<Entry> = emptyList()
    private lateinit var adapter: EntryAdapter
    private lateinit var store: Store
    private lateinit var toolbar: MaterialToolbar
    private var seriesName = ""
    /** null = Staffel-Übersicht, sonst angezeigte Staffel */
    private var season: String? = null
    private val seasons get() = episodes.map { it.categoryId }.distinct()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        store = Store(this)
        val api = Xtream.from(store)
        val id = intent.getStringExtra("id") ?: run { finish(); return }

        toolbar = findViewById(R.id.toolbar)
        seriesName = intent.getStringExtra("name") ?: "Serie"
        toolbar.title = seriesName
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        season = savedInstanceState?.getString("season")

        val progress = findViewById<ProgressBar>(R.id.progress)
        val empty = findViewById<TextView>(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        adapter = EntryAdapter(
            onClick = { e ->
                if (season == null) { season = e.id.removePrefix("season_"); show() }
                else Play.start(this, Kind.SERIES, episodes, episodes.indexOfFirst { it.id == e.id })
            },
            onLongClick = { e ->
                if (season == null) { season = e.id.removePrefix("season_"); show(); return@EntryAdapter }
                val idx = episodes.indexOfFirst { it.id == e.id }
                val opts = arrayListOf("Im internen Player abspielen", "Mit externem Player öffnen")
                if (store.position(e.id) > 0) opts += "Fortsetzen-Position löschen"
                MaterialAlertDialogBuilder(this).setTitle(e.name).setItems(opts.toTypedArray()) { _, w ->
                    when (w) {
                        0 -> Play.start(this, Kind.SERIES, episodes, idx, forceInternal = true)
                        1 -> Play.external(this, Play.url(this, Kind.SERIES, e), e.name)
                        else -> { store.setPosition(e.id, 0); show() }
                    }
                }.show()
            },
            onFavToggle = null,
            onStartDrag = null
        )
        adapter.showLogos = store.showLogos
        list.adapter = adapter

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(api.episodes(id)) } catch (e: Exception) { Result.failure(e) }
            }
            progress.visibility = View.GONE
            res.onSuccess {
                episodes = it
                show()
                if (it.isEmpty()) { empty.text = "Keine Folgen gefunden"; empty.visibility = View.VISIBLE }
            }.onFailure {
                empty.text = "Laden fehlgeschlagen:\n${it.message}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() { super.onResume(); if (episodes.isNotEmpty()) show() }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("season", season) }

    private fun goBack() {
        if (season != null && seasons.size > 1) { season = null; show() } else finish()
    }

    /** Erst Staffeln, nach Antippen nur die Folgen dieser Staffel. */
    private fun show() {
        if (season == null && seasons.size == 1) season = seasons.first()
        if (season == null) {
            toolbar.title = seriesName
            toolbar.subtitle = "${seasons.size} Staffeln · ${episodes.size} Folgen"
            adapter.submit(seasons.map { sn ->
                val eps = episodes.filter { it.categoryId == sn }
                val started = eps.count { store.position(it.id) > 0 }
                Entry("season_$sn", "Staffel $sn", sn, eps.firstOrNull { !it.icon.isNullOrBlank() }?.icon, null,
                    "${eps.size} Folgen" + (if (started > 0) " · ▶ angefangen" else ""))
            })
            return
        }
        toolbar.title = "$seriesName – Staffel $season"
        toolbar.subtitle = null
        adapter.submit(episodes.filter { it.categoryId == season }.map {
            val pos = store.position(it.id)
            if (pos > 0) it.copy(subtitle = "${it.subtitle} · ▶ Fortsetzen bei ${fmt(pos)}") else it
        })
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
