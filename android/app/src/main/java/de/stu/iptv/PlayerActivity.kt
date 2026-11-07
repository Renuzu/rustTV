package de.stu.iptv

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private var current: PlayerActivity? = null
        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        private val RESIZE_LABELS = arrayOf("Einpassen", "Zoom (ausfüllen)", "Strecken")
    }

    private lateinit var store: Store
    private lateinit var api: Xtream
    private lateinit var view: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var epgView: TextView
    private lateinit var gestureInfo: TextView
    private lateinit var unlockBtn: TextView
    private lateinit var panel: View
    private lateinit var panelAdapter: EntryAdapter
    private lateinit var panelList: RecyclerView
    private lateinit var audio: AudioManager

    private var player: ExoPlayer? = null
    private var queue: List<Entry> = emptyList()
    private var kind = Kind.LIVE
    private var index = 0
    private var previousIndex = -1
    private var singleUrl: String? = null
    private var singleTitle = ""
    private var resumePos = 0L
    private var locked = false
    private var inPip = false
    private var epgJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        Toast.makeText(this, "Sleep-Timer: Wiedergabe beendet", Toast.LENGTH_LONG).show()
        finish()
    }

    private val isLive get() = singleUrl == null && kind == Kind.LIVE
    private val currentEntry get() = if (singleUrl == null) queue.getOrNull(index) else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current?.finish()
        current = this
        setContentView(R.layout.activity_player)
        store = Store(this)
        api = Xtream.from(store)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        singleUrl = intent.getStringExtra("url")
        singleTitle = intent.getStringExtra("title") ?: ""
        if (singleUrl == null) {
            queue = Play.queue
            kind = Play.queueKind
            index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
            if (queue.isEmpty()) { finish(); return }
        }
        resumePos = savedInstanceState?.getLong("pos") ?: -1L

        view = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.title)
        epgView = findViewById(R.id.epg)
        gestureInfo = findViewById(R.id.gestureInfo)
        unlockBtn = findViewById(R.id.unlock)
        panel = findViewById(R.id.panel)
        panelList = findViewById(R.id.panelList)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        view.resizeMode = RESIZE_MODES[store.resizeMode.coerceIn(0, RESIZE_MODES.size - 1)]
        view.setShowSubtitleButton(true)
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            topBar.visibility = if (inPip || locked) View.GONE else v
        })

        setupButtons()
        setupPanel()

        unlockBtn.setOnClickListener {
            locked = false
            unlockBtn.visibility = View.GONE
            view.useController = true
            view.showController()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel.visibility == View.VISIBLE -> panel.visibility = View.GONE
                    locked -> flashUnlock()
                    else -> finish()
                }
            }
        })
    }

    // ---------------- Schaltflächen ----------------
    private fun setupButtons() {
        val box = findViewById<LinearLayout>(R.id.buttons)
        fun add(label: String, keepOpen: Boolean = true, action: () -> Unit) {
            val b = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle)
            b.text = label
            b.isAllCaps = false
            b.setTextColor(0xFFFFFFFF.toInt())
            b.setOnClickListener { action(); if (keepOpen) view.showController() }
            box.addView(b)
        }
        if (singleUrl == null) {
            add(if (kind == Kind.LIVE) "☰ Sender" else "☰ Liste", keepOpen = false) { togglePanel() }
            add("◀ Zurück") { switchBy(-1) }
            add("Weiter ▶") { switchBy(1) }
            if (kind == Kind.LIVE) add("↺ Letzter Sender") { recall() }
        }
        add("⤢ Bildformat") { cycleResize() }
        add("⏾ Sleep-Timer") { sleepDialog() }
        add("⧉ Bild-in-Bild", keepOpen = false) { enterPip() }
        add("🔒 Sperren", keepOpen = false) { lock() }
        if (isLive) {
            add("📋 Programm") { currentEntry?.let { Dialogs.showEpg(this, it) } }
            add("▦ Multi-View") {
                startActivity(Intent(this, MultiViewActivity::class.java).putExtra("first", currentEntry?.id))
                finish()
            }
        }
        if (kind != Kind.LIVE || singleUrl != null) add("⏩ Tempo") { speedDialog() }
        add("↗ Extern") { currentUrl()?.let { Play.external(this, it, currentTitle()) }; finish() }
    }

    private fun setupPanel() {
        panelList.layoutManager = LinearLayoutManager(this)
        panelAdapter = EntryAdapter(
            onClick = { e -> switchTo(queue.indexOfFirst { it.id == e.id }); panel.visibility = View.GONE },
            onLongClick = {}, onFavToggle = null, onStartDrag = null
        )
        panelAdapter.showLogos = store.showLogos
        panelAdapter.submit(queue)
        panelList.adapter = panelAdapter
        findViewById<EditText>(R.id.panelSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                panelAdapter.submit(if (q.isEmpty()) queue else queue.filter { it.name.lowercase().contains(q) })
            }
        })
    }

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) { panel.visibility = View.GONE; return }
        panel.visibility = View.VISIBLE
        val pos = panelAdapter.items.indexOfFirst { it.id == currentEntry?.id }
        if (pos >= 0) (panelList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
        view.hideController()
    }

    // ---------------- Wiedergabe ----------------
    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(store.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        val load = when (store.buffer) {
            "small" -> DefaultLoadControl.Builder().setBufferDurationsMs(5000, 15000, 500, 1000).build()
            "large" -> DefaultLoadControl.Builder().setBufferDurationsMs(30000, 120000, 2500, 5000).build()
            else -> DefaultLoadControl.Builder().build()
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(load)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition(); p.prepare(); return
                }
                showError(error)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        })
        return p
    }

    private fun currentUrl(): String? = singleUrl ?: currentEntry?.let { Play.url(this, kind, it) }
    private fun currentTitle(): String = singleTitle.ifEmpty { currentEntry?.name ?: "" }

    /** Aktuellen Eintrag laden und abspielen. */
    private fun startCurrent(startPos: Long = -1L) {
        val p = player ?: return
        val url = currentUrl() ?: return
        val e = currentEntry
        titleView.text = buildString {
            append(currentTitle())
            if (singleUrl == null && queue.size > 1) append("   (${index + 1}/${queue.size})")
        }
        val item = MediaItem.Builder().setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle()).build()).build()

        var pos = startPos
        if (pos < 0 && e != null && kind != Kind.LIVE && store.resume) {
            pos = store.position(e.id)
            if (pos > 0) Toast.makeText(this, "Fortgesetzt bei ${fmt(pos)}", Toast.LENGTH_SHORT).show()
        }
        if (isLive || pos <= 0) p.setMediaItem(item) else p.setMediaItem(item, pos)
        p.playWhenReady = true
        p.prepare()

        view.setShowFastForwardButton(!isLive)
        view.setShowRewindButton(!isLive)

        if (e != null) {
            store.addHistory(kind, e.id)
            if (kind == Kind.LIVE) store.lastLive = e.id
        }
        loadEpg()
    }

    private fun loadEpg() {
        epgJob?.cancel()
        epgView.visibility = View.GONE
        val e = currentEntry ?: return
        if (kind != Kind.LIVE) return
        epgJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { try { api.shortEpg(e.id, 2) } catch (x: Exception) { emptyList() } }
            if (list.isEmpty() || currentEntry?.id != e.id) return@launch
            val f = SimpleDateFormat("HH:mm", Locale.GERMANY)
            epgView.text = list.mapIndexed { i, pr ->
                (if (i == 0) "Jetzt: " else "Danach: ") + "${pr.title}  (${f.format(Date(pr.startTs * 1000))}–${f.format(Date(pr.endTs * 1000))})"
            }.joinToString("\n")
            epgView.visibility = View.VISIBLE
        }
    }

    private fun switchTo(i: Int) {
        if (singleUrl != null || i !in queue.indices || i == index) return
        savePosition()
        previousIndex = index
        index = i
        startCurrent()
        view.showController()
    }

    private fun switchBy(d: Int) {
        if (queue.isEmpty()) return
        switchTo((index + d + queue.size) % queue.size)
    }

    private fun recall() {
        if (previousIndex in queue.indices) switchTo(previousIndex)
        else Toast.makeText(this, "Noch kein vorheriger Sender", Toast.LENGTH_SHORT).show()
    }

    private fun onEnded() {
        val e = currentEntry
        if (e != null && kind != Kind.LIVE) store.setPosition(e.id, 0)
        if (kind == Kind.SERIES && store.autoNext && singleUrl == null && index < queue.size - 1) {
            Toast.makeText(this, "Nächste Folge: ${queue[index + 1].name}", Toast.LENGTH_SHORT).show()
            previousIndex = index; index++; startCurrent()
        }
    }

    /** Merkt sich die Position bei Filmen/Folgen (für „Fortsetzen“). */
    private fun savePosition() {
        val p = player ?: return
        val e = currentEntry ?: return
        if (kind == Kind.LIVE) return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        store.setPosition(e.id, if (pos > 10_000 && pos < dur * 0.95) pos else 0)
    }

    private fun showError(e: PlaybackException) {
        if (isFinishing) return
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("Wiedergabe-Fehler")
            .setMessage("${e.errorCodeName}\n\nTipp: Externen Player (z. B. VLC) versuchen oder in den Einstellungen das Live-Format ändern.")
            .setPositiveButton("Extern öffnen") { _, _ -> currentUrl()?.let { Play.external(this, it, currentTitle()) }; finish() }
            .setNegativeButton("Erneut") { _, _ -> player?.prepare(); player?.play() }
            .setCancelable(true)
        if (singleUrl == null && queue.size > 1) b.setNeutralButton("Nächster") { _, _ -> switchBy(1) }
        b.show()
    }

    // ---------------- Bildformat, Tempo, Timer, Sperre, PiP ----------------
    private fun cycleResize() {
        val next = (store.resizeMode + 1) % RESIZE_MODES.size
        store.resizeMode = next
        view.resizeMode = RESIZE_MODES[next]
        showInfo("⤢ ${RESIZE_LABELS[next]}")
    }

    private fun speedDialog() {
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val cur = speeds.indexOfFirst { it == (player?.playbackParameters?.speed ?: 1f) }
        MaterialAlertDialogBuilder(this).setTitle("Wiedergabe-Tempo")
            .setSingleChoiceItems(speeds.map { "${it}x".replace(".0x", "x") }.toTypedArray(), cur) { d, w ->
                player?.setPlaybackSpeed(speeds[w]); d.dismiss()
            }.show()
    }

    private fun sleepDialog() {
        val mins = intArrayOf(0, 15, 30, 45, 60, 90, 120)
        val labels = mins.map { if (it == 0) "Aus" else "$it Minuten" }.toTypedArray()
        val remaining = if (sleepAt > 0) " (noch ${((sleepAt - System.currentTimeMillis()) / 60000) + 1} Min.)" else ""
        MaterialAlertDialogBuilder(this).setTitle("Sleep-Timer$remaining")
            .setItems(labels) { _, w ->
                handler.removeCallbacks(sleepRunnable)
                sleepAt = 0
                if (mins[w] > 0) {
                    sleepAt = System.currentTimeMillis() + mins[w] * 60_000L
                    handler.postDelayed(sleepRunnable, mins[w] * 60_000L)
                    Toast.makeText(this, "Wiedergabe endet in ${mins[w]} Minuten", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Sleep-Timer aus", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun lock() {
        locked = true
        panel.visibility = View.GONE
        view.hideController()
        view.useController = false
        topBar.visibility = View.GONE
        flashUnlock()
    }

    private fun flashUnlock() {
        unlockBtn.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlock)
        handler.postDelayed(hideUnlock, 3000)
    }
    private val hideUnlock = Runnable { if (locked) unlockBtn.visibility = View.GONE }

    private fun enterPip() {
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (e: Exception) {
            Toast.makeText(this, "Bild-in-Bild wird nicht unterstützt", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (store.autoPip && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            view.hideController(); topBar.visibility = View.GONE; panel.visibility = View.GONE
            view.useController = false
        } else if (!locked) view.useController = true
    }

    // ---------------- Wisch-Gesten ----------------
    private enum class G { NONE, VOLUME, BRIGHT, SEEK, ZAP }
    private var g = G.NONE
    private var downX = 0f
    private var downY = 0f
    private var gestureAllowed = false
    private var startVolume = 0
    private var startBright = 0.5f
    private var seekTarget = 0L
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 2 }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (locked) { if (ev.actionMasked == MotionEvent.ACTION_DOWN) flashUnlock(); return super.dispatchTouchEvent(ev) }
        if (inPip || !store.gestures) return super.dispatchTouchEvent(ev)
        val w = window.decorView.width.toFloat()
        val h = window.decorView.height.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; g = G.NONE
                val inPanel = panel.visibility == View.VISIBLE && ev.x > panel.left
                gestureAllowed = !inPanel && ev.y > h * 0.15f && ev.y < h * 0.82f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (g == G.NONE && gestureAllowed) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) {
                        g = if (downX < w / 2) (if (store.gBright) G.BRIGHT else G.NONE)
                            else (if (store.gVolume) G.VOLUME else G.NONE)
                        startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startBright = currentBrightness()
                    } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                        g = if (isLive) (if (store.swipeZap && queue.size > 1) G.ZAP else G.NONE) else (if (store.gSeek) G.SEEK else G.NONE)
                        if (g == G.SEEK && (player?.isCurrentMediaItemSeekable != true)) g = G.NONE
                    }
                    if (g != G.NONE) cancelForChildren(ev)
                }
                when (g) {
                    G.VOLUME -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (startVolume + (-dy / (h * 0.7f)) * max).toInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showInfo("🔊 Lautstärke ${v * 100 / max}%", hide = false)
                    }
                    G.BRIGHT -> {
                        val b = (startBright + (-dy / (h * 0.7f))).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = b }
                        showInfo("☀ Helligkeit ${(b * 100).toInt()}%", hide = false)
                    }
                    G.SEEK -> {
                        val p = player ?: return true
                        val delta = (dx / w * 120_000).toLong()   // ganze Breite = 2 Minuten
                        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                        seekTarget = (p.currentPosition + delta).coerceIn(0, dur)
                        showInfo("${if (delta >= 0) "⏩ +" else "⏪ -"}${fmt(abs(delta))}   ${fmt(seekTarget)}", hide = false)
                    }
                    G.ZAP -> showInfo(if (dx < 0) "Nächster Sender ▶" else "◀ Vorheriger Sender", hide = false)
                    G.NONE -> {}
                }
                if (g != G.NONE) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (g != G.NONE) {
                    val dx = ev.x - downX
                    when (g) {
                        G.SEEK -> if (ev.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                        G.ZAP -> if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dx) > w * 0.2f) switchBy(if (dx < 0) 1 else -1)
                        else -> {}
                    }
                    g = G.NONE
                    hideInfoSoon()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelForChildren(ev: MotionEvent) {
        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(c); c.recycle()
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0) return b
        return try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
    }

    private val hideInfo = Runnable { gestureInfo.visibility = View.GONE }
    private fun showInfo(t: String, hide: Boolean = true) {
        gestureInfo.text = t
        gestureInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (hide) hideInfoSoon()
    }
    private fun hideInfoSoon() { handler.removeCallbacks(hideInfo); handler.postDelayed(hideInfo, 900) }

    // ---------------- Tasten (Fernbedienung / Tastatur) ----------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { switchBy(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { switchBy(-1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { recall(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Lebenszyklus ----------------
    override fun onStart() {
        super.onStart()
        if (player == null) {
            player = buildPlayer()
            view.player = player
            startCurrent(resumePos)
            resumePos = -1L
        }
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player?.let { resumePos = if (isLive) -1L else it.currentPosition; it.release() }
        player = null
        if (inPip) finish()   // Bild-in-Bild-Fenster wurde geschlossen
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("index", index)
        out.putLong("pos", if (isLive) -1L else (player?.currentPosition ?: resumePos))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (current === this) current = null
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
