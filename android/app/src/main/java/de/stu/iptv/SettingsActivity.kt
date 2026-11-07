package de.stu.iptv

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            title = "Einstellungen"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        val container = FrameLayout(this).apply { id = R.id.settings_container }
        root.addView(toolbar)
        root.addView(container, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction().replace(R.id.settings_container, Fragment()).commit()
    }

    class Fragment : PreferenceFragmentCompat() {
        private val wg = WgStarter(this, { requireActivity() as AppCompatActivity }) { refreshWg() }
        private val pickConf = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val a = requireActivity()
            try {
                val text = a.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val server = Wg.importConfig(a, text)
                Toast.makeText(a, "WireGuard-Konfiguration übernommen: $server", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setTitle("Ungültige Datei")
                    .setMessage("Das ist keine gültige WireGuard-Konfiguration (.conf).\n\n${e.message}")
                    .setPositiveButton("OK", null).show()
            }
            refreshWg()
        }

        private fun refreshWg() {
            val a = activity ?: return
            val desc = Wg.describe(a)
            findPreference<androidx.preference.Preference>("wg_import")?.summary =
                desc?.let { "Server: $it – antippen zum Ersetzen" } ?: "Keine Datei – .conf von deinem VPN-Anbieter wählen"
            findPreference<androidx.preference.SwitchPreferenceCompat>("wg_on")?.apply {
                isEnabled = desc != null
                isChecked = Wg.enabled(a)
                summary = when {
                    !Wg.enabled(a) -> "Aus"
                    Wg.isUp(a) -> "🔒 Verbunden – nur rustTV nutzt den Tunnel"
                    else -> "Eingeschaltet, aber nicht verbunden – rustTV lädt solange nichts"
                }
            }
            findPreference<androidx.preference.Preference>("wg_remove")?.isEnabled = desc != null
        }

        override fun onResume() { super.onResume(); refreshWg() }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = Store.FILE
            setPreferencesFromResource(R.xml.prefs, rootKey)
            val act = requireActivity() as AppCompatActivity
            findPreference<androidx.preference.Preference>("set_pin")?.setOnPreferenceClickListener {
                if (Store(act).pin.isEmpty()) Dialogs.setPin(act) else Dialogs.askPin(act) { Dialogs.setPin(act) }
                true
            }
            findPreference<androidx.preference.EditTextPreference>("proxy_pass")?.setOnBindEditTextListener {
                it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            findPreference<androidx.preference.EditTextPreference>("proxy_port")?.setOnBindEditTextListener {
                it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            listOf("proxy_kind", "proxy_host", "proxy_port", "proxy_user", "proxy_pass").forEach { k ->
                findPreference<androidx.preference.Preference>(k)?.setOnPreferenceChangeListener { _, _ -> Cache.clear(); true }
            }
            findPreference<androidx.preference.Preference>("proxy_test")?.setOnPreferenceClickListener {
                Toast.makeText(act, "Teste Verbindung …", Toast.LENGTH_SHORT).show()
                act.lifecycleScope.launch {
                    val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val s = Store(act)
                        try {
                            "✅ Dein Anbieter sieht die IP-Adresse:\n${Net.publicIp(act)}\n\n" +
                                (if (s.proxyKind.isEmpty()) "Ohne VPN/Proxy (direkt)." else "Über ${s.proxyKind.uppercase()} ${s.proxyHost}:${s.proxyPort}")
                        } catch (e: Exception) {
                            "❌ Keine Verbindung über den Proxy:\n${e.message}\n\nrustTV spielt ohne funktionierende Verbindung nichts ab."
                        }
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                        .setTitle("VPN / Proxy").setMessage(msg).setPositiveButton("OK", null).show()
                }
                true
            }
            findPreference<androidx.preference.Preference>("wg_import")?.setOnPreferenceClickListener {
                pickConf.launch(arrayOf("*/*")); true
            }
            findPreference<androidx.preference.SwitchPreferenceCompat>("wg_on")?.setOnPreferenceChangeListener { _, v ->
                val on = v as Boolean
                Wg.setEnabled(act, on)
                Cache.clear()
                if (on) wg.start() else { Thread { Wg.down(act) }.start() }
                view?.postDelayed({ refreshWg() }, 800)
                true
            }
            findPreference<androidx.preference.Preference>("wg_remove")?.setOnPreferenceClickListener {
                Thread { Wg.down(act) }.start()
                Wg.removeConfig(act); Cache.clear(); refreshWg(); true
            }
            findPreference<androidx.preference.Preference>("check_update")?.setOnPreferenceClickListener {
                Updater.check(act, manual = true); true
            }
            findPreference<androidx.preference.Preference>("clear_cache")?.setOnPreferenceClickListener {
                Cache.clear(); Toast.makeText(act, "Listen werden beim nächsten Öffnen neu geladen", Toast.LENGTH_SHORT).show(); true
            }
            // Jugendschutz-Schalter nur mit PIN abschaltbar
            findPreference<androidx.preference.SwitchPreferenceCompat>("auto_lock_adult")?.setOnPreferenceChangeListener { pref, value ->
                if (value == false && Store(act).pin.isNotEmpty() && !Cache.unlocked) {
                    Dialogs.askPin(act) { (pref as androidx.preference.SwitchPreferenceCompat).isChecked = false }
                    false
                } else true
            }
        }
    }
}
