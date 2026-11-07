package de.stu.iptv

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
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
