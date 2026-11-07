package de.stu.iptv

import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Dialogs {

    private fun pinField(a: AppCompatActivity, hint: String) = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun wrap(a: AppCompatActivity, vararg views: android.view.View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    /** PIN abfragen. Ist noch keine PIN gesetzt, wird erst eine angelegt. */
    fun askPin(a: AppCompatActivity, unlock: Boolean = true, onOk: () -> Unit) {
        val store = Store(a)
        if (store.pin.isEmpty()) { setPin(a) { if (unlock) Cache.unlocked = true; onOk() }; return }
        val f = pinField(a, "PIN")
        MaterialAlertDialogBuilder(a).setTitle("Jugendschutz-PIN").setView(wrap(a, f))
            .setPositiveButton("OK") { _, _ ->
                if (f.text.toString() == store.pin) { if (unlock) Cache.unlocked = true; onOk() }
                else Toast.makeText(a, "Falsche PIN", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Abbrechen", null).show()
    }

    fun setPin(a: AppCompatActivity, onDone: (() -> Unit)? = null) {
        val f1 = pinField(a, "Neue PIN (mind. 4 Ziffern)")
        val f2 = pinField(a, "PIN wiederholen")
        MaterialAlertDialogBuilder(a).setTitle("PIN festlegen").setView(wrap(a, f1, f2))
            .setPositiveButton("Speichern") { _, _ ->
                val p = f1.text.toString()
                when {
                    p.length < 4 -> Toast.makeText(a, "Mindestens 4 Ziffern", Toast.LENGTH_SHORT).show()
                    p != f2.text.toString() -> Toast.makeText(a, "PINs stimmen nicht überein", Toast.LENGTH_SHORT).show()
                    else -> { Store(a).pin = p
                        Toast.makeText(a, "PIN gespeichert", Toast.LENGTH_SHORT).show(); onDone?.invoke() }
                }
            }.setNegativeButton("Abbrechen", null).show()
    }

    /** Durchsuchbare Auswahlliste (z. B. Sender für Multi-View). */
    fun pickEntry(a: AppCompatActivity, title: String, list: List<Entry>, onPick: (Entry) -> Unit) {
        val search = EditText(a).apply { hint = "Suchen…"; isSingleLine = true }
        val rv = RecyclerView(a)
        rv.layoutManager = LinearLayoutManager(a)
        var dialog: AlertDialog? = null
        val adapter = EntryAdapter(onClick = { dialog?.dismiss(); onPick(it) }, onLongClick = {},
            onFavToggle = null, onStartDrag = null)
        adapter.showLogos = Store(a).showLogos
        adapter.submit(list)
        rv.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                adapter.submit(if (q.isEmpty()) list else list.filter { it.name.lowercase().contains(q) })
            }
        })
        val box = wrap(a, search)
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (a.resources.displayMetrics.heightPixels * 0.55).toInt()))
        dialog = MaterialAlertDialogBuilder(a).setTitle(title).setView(box)
            .setNegativeButton("Abbrechen", null).show()
    }

    private val timeFmt = SimpleDateFormat("EE HH:mm", Locale.GERMANY)

    /** TV-Programm eines Senders; Sendungen mit Archiv lassen sich nachträglich abspielen. */
    fun showEpg(a: AppCompatActivity, e: Entry) {
        val api = Xtream.from(Store(a))
        val loading = MaterialAlertDialogBuilder(a).setTitle(e.name).setMessage("Programm wird geladen …").show()
        a.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(api.fullEpg(e.id).ifEmpty { api.shortEpg(e.id, 20) }) }
                catch (ex: Exception) { Result.failure(ex) }
            }
            loading.dismiss()
            val list = res.getOrNull()
            if (list.isNullOrEmpty()) {
                MaterialAlertDialogBuilder(a).setTitle(e.name)
                    .setMessage("Kein Programm verfügbar." + (res.exceptionOrNull()?.let { "\n${it.message}" } ?: ""))
                    .setPositiveButton("OK", null).show(); return@launch
            }
            val now = System.currentTimeMillis() / 1000
            // aktuelle Sendung nach oben scrollen: vergangene ohne Archiv weglassen
            val shown = list.filter { it.endTs > now || it.hasArchive }
            val labels = shown.map {
                val mark = when {
                    it.startTs <= now && it.endTs > now -> "▶ JETZT  "
                    it.endTs <= now -> "⏪ "
                    else -> ""
                }
                "$mark${timeFmt.format(Date(it.startTs * 1000))}  ${it.title}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(a).setTitle("${e.name} – Programm")
                .setItems(labels) { _, i ->
                    val p = shown[i]
                    val desc = p.description.ifBlank { "Keine Beschreibung." }
                    val b = MaterialAlertDialogBuilder(a).setTitle(p.title).setMessage(desc)
                        .setNegativeButton("Schließen", null)
                    if (p.endTs <= now && p.hasArchive) {
                        b.setPositiveButton("Aus Archiv abspielen") { _, _ ->
                            Play.single(a, api.timeshiftUrl(e.id, p), "${e.name}: ${p.title}")
                        }
                    } else if (p.startTs <= now && p.endTs > now && e.archive) {
                        b.setPositiveButton("Von Anfang an") { _, _ ->
                            Play.single(a, api.timeshiftUrl(e.id, p), "${e.name}: ${p.title}")
                        }
                    }
                    b.show()
                }.setNegativeButton("Schließen", null).show()
        }
    }

    /** Konten anzeigen, wechseln, hinzufügen, löschen. */
    fun accounts(a: AppCompatActivity, onChanged: () -> Unit) {
        val store = Store(a)
        val accs = store.accounts()
        val labels = accs.map { (if (it.id == store.account?.id) "✓ " else "    ") + it.name + "  (${it.user})" } +
            listOf("+ Konto hinzufügen")
        MaterialAlertDialogBuilder(a).setTitle("Konten")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == accs.size) {
                    a.startActivity(Intent(a, LoginActivity::class.java).putExtra("add", true))
                    return@setItems
                }
                val acc = accs[i]
                MaterialAlertDialogBuilder(a).setTitle(acc.name)
                    .setItems(arrayOf("Zu diesem Konto wechseln", "Konto löschen")) { _, w ->
                        if (w == 0) { store.activeId = acc.id; Cache.clear(); onChanged() }
                        else MaterialAlertDialogBuilder(a).setMessage("Konto „${acc.name}“ löschen?")
                            .setPositiveButton("Löschen") { _, _ ->
                                store.removeAccount(acc.id); Cache.clear()
                                if (!store.loggedIn) {
                                    a.startActivity(Intent(a, LoginActivity::class.java)); a.finish()
                                } else onChanged()
                            }.setNegativeButton("Abbrechen", null).show()
                    }.show()
            }.setNegativeButton("Schließen", null).show()
    }

    fun accountInfo(a: AppCompatActivity) {
        val api = Xtream.from(Store(a))
        a.lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(api.accountInfo()) } catch (e: Exception) { Result.failure(e) }
            }
            val msg = res.getOrNull()?.joinToString("\n") { "${it.first}: ${it.second}" }
                ?: "Fehler: ${res.exceptionOrNull()?.message}"
            MaterialAlertDialogBuilder(a).setTitle("Kontoinfo").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }
}
