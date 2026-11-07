package de.stu.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer APK and opens the download (no install permission needed).
 * version.json: {"versionCode": 7, "versionName": "3.4", "apk": "https://…/rustv-android.apk", "notes": "…"}
 */
object Updater {
    private const val VERSION_URL = "https://raw.githubusercontent.com/Renuzu/rustv/main/android/version.json"
    private const val PREFS = "updater"

    private fun currentCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    private fun get(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "ruSTv-Android")
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect() }
            else return c
        }
        throw Exception("too many redirects")
    }

    /** [manual] = user tapped „Nach Updates suchen“ (also reports „aktuell“). */
    fun check(a: AppCompatActivity, manual: Boolean = false) {
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - p.getLong("last", 0) < 12 * 3600_000L) return
        p.edit().putLong("last", now).apply()
        a.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try { JSONObject(get(VERSION_URL).inputStream.bufferedReader().use { it.readText() }) } catch (e: Exception) { null }
            }
            if (info == null) {
                if (manual) Toast.makeText(a, "Update-Prüfung fehlgeschlagen", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val code = info.optLong("versionCode", 0)
            if (code <= currentCode(a)) {
                if (manual) Toast.makeText(a, "Du hast die neueste Version.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!manual && p.getLong("skipped", 0) == code) return@launch
            MaterialAlertDialogBuilder(a)
                .setTitle("Update ${info.optString("versionName")} verfügbar")
                .setMessage(info.optString("notes").ifBlank { "Eine neue Version von ruSTv ist verfügbar." })
                .setPositiveButton("Jetzt installieren") { _, _ -> download(a, info.optString("apk")) }
                .setNeutralButton("Diese überspringen") { _, _ -> p.edit().putLong("skipped", code).apply() }
                .setNegativeButton("Später", null)
                .show()
        }
    }

    /** Opens the APK link in the browser – Android downloads it and offers to install. */
    private fun download(a: AppCompatActivity, url: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            Toast.makeText(a, "Download startet – danach die Datei antippen und „Aktualisieren“ wählen.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(a, "Kein Browser gefunden", Toast.LENGTH_LONG).show()
        }
    }
}
