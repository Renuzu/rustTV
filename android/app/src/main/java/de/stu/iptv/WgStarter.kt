package de.stu.iptv

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultCaller
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks for the VPN permission (once) and starts the rustTV-only WireGuard tunnel. */
class WgStarter(caller: ActivityResultCaller, private val act: () -> AppCompatActivity, private val onResult: (String?) -> Unit = {}) {
    private val launcher: ActivityResultLauncher<android.content.Intent> =
        caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) connect()
            else { Toast.makeText(act(), "Ohne VPN-Erlaubnis kann WireGuard nicht starten.", Toast.LENGTH_LONG).show(); onResult("permission") }
        }

    fun start() {
        val a = act()
        val intent = VpnService.prepare(a)
        if (intent != null) launcher.launch(intent) else connect()
    }

    private fun connect() {
        val a = act()
        a.lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try { Wg.up(a); null } catch (e: Exception) { e.message ?: e.toString() }
            }
            if (err == null) {
                Cache.clear()
                Toast.makeText(a, "🔒 WireGuard verbunden (nur rustTV)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(a, "WireGuard-Fehler: $err", Toast.LENGTH_LONG).show()
            }
            onResult(err)
        }
    }
}
