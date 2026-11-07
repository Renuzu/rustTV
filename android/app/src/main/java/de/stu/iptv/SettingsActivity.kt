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
