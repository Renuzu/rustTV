package de.stu.iptv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bis zu 4 Live-Sender gleichzeitig. Ton kommt vom ausgewählten (umrandeten) Fenster. */
@UnstableApi
class MultiViewActivity : AppCompatActivity() {

    private class Slot(val frame: FrameLayout, val view: PlayerView, val label: TextView) {
        var entry: Entry? = null
        var player: ExoPlayer? = null
    }

    private lateinit var store: Store
    private val slots = ArrayList<Slot>()
    private var audioSlot = 0
    private var channels: List<Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        repeat(2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(2) {
                val frame = FrameLayout(this)
                val pv = PlayerView(this).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
                val label = TextView(this).apply {
                    setTextColor(Color.WHITE); textSize = 13f
                    setBackgroundColor(0x99000000.toInt())
                    setPadding(12, 6, 12, 6)
                    text = "＋ Tippen: Sender wählen"
                }
                frame.addView(pv, FrameLayout.LayoutParams(-1, -1))
                frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
                row.addView(frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(2, 2, 2, 2) })
                val slot = Slot(frame, pv, label)
                val i = slots.size
                frame.setOnClickListener { if (slot.entry == null) pick(i) else selectAudio(i) }
                frame.setOnLongClickListener { slotMenu(i); true }
                // Klicks auf das Video an den Rahmen weitergeben
                pv.setOnClickListener { frame.performClick() }
                pv.setOnLongClickListener { frame.performLongClick() }
                slots.add(slot)
            }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        highlight()
        Toast.makeText(this, "Tippen = Ton wählen · Lange drücken = Sender ändern", Toast.LENGTH_LONG).show()

        loadChannels {
            intent.getStringExtra("first")?.let { id -> channels.firstOrNull { it.id == id }?.let { setChannel(0, it) } }
        }
    }

    private fun loadChannels(then: () -> Unit) {
        fun apply(@Suppress("UNUSED_PARAMETER") all: List<Entry>) {
            val favs = store.favorites(Kind.LIVE)
            channels = Bouquets.normal(store, Kind.LIVE)
                .sortedWith(compareBy<Entry> { it.id !in favs }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            then()
        }
        Cache.entries[Kind.LIVE]?.let { apply(it); return }
        val api = Xtream.from(store)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(api.categories(Kind.LIVE) to api.entries(Kind.LIVE)) } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { (c, e) -> Cache.categories[Kind.LIVE] = c; Cache.entries[Kind.LIVE] = e; apply(e) }
                .onFailure { Toast.makeText(this@MultiViewActivity, "Senderliste konnte nicht geladen werden", Toast.LENGTH_LONG).show() }
        }
    }

    private fun pick(i: Int) {
        if (channels.isEmpty()) return
        Dialogs.pickEntry(this, "Sender für Fenster ${i + 1}", channels) { setChannel(i, it) }
    }

    private fun slotMenu(i: Int) {
        val s = slots[i]
        val opts = arrayListOf("Sender wählen")
        if (s.entry != null) { opts += "Im Vollbild ansehen"; opts += "Fenster leeren" }
        MaterialAlertDialogBuilder(this).setTitle(s.entry?.name ?: "Fenster ${i + 1}")
            .setItems(opts.toTypedArray()) { _, w ->
                when (w) {
                    0 -> pick(i)
                    1 -> {
                        val e = s.entry ?: return@setItems
                        Play.start(this, Kind.LIVE, channels, channels.indexOf(e).coerceAtLeast(0), forceInternal = true)
                        finish()
                    }
                    2 -> { s.player?.release(); s.player = null; s.entry = null; s.view.player = null; s.label.text = "＋ Tippen: Sender wählen" }
                }
            }.show()
    }

    private fun setChannel(i: Int, e: Entry) {
        val s = slots[i]
        s.entry = e
        s.label.text = e.name
        startSlot(s)
        if (slots.count { it.entry != null } == 1) audioSlot = i
        selectAudio(audioSlot)
    }

    private fun startSlot(s: Slot) {
        val e = s.entry ?: return
        s.player?.release()
        val http = DefaultHttpDataSource.Factory().setUserAgent(store.userAgent).setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http)).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare() }
                else s.label.text = "${e.name} – Fehler (evtl. Verbindungs-Limit des Anbieters)"
            }
        })
        p.setMediaItem(MediaItem.fromUri(Play.url(this, Kind.LIVE, e)))
        p.volume = 0f
        p.playWhenReady = true
        p.prepare()
        s.view.player = p
        s.player = p
    }

    private fun selectAudio(i: Int) {
        audioSlot = i
        slots.forEachIndexed { idx, s -> s.player?.volume = if (idx == i) 1f else 0f }
        highlight()
    }

    private fun highlight() {
        slots.forEachIndexed { idx, s ->
            s.frame.foreground = if (idx == audioSlot) GradientDrawable().apply {
                setStroke(6, 0xFF1E88E5.toInt()); setColor(Color.TRANSPARENT)
            } else null
        }
    }

    override fun onStart() {
        super.onStart()
        slots.forEach { if (it.entry != null && it.player == null) startSlot(it) }
        selectAudio(audioSlot)
    }

    override fun onStop() {
        super.onStop()
        slots.forEach { it.player?.release(); it.player = null; it.view.player = null }
    }
}
