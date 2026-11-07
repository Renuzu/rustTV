package de.stu.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer APK and installs it with one tap.
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
            c.setRequestProperty("User-Agent", "RusTV-Android")
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
                .setMessage(info.optString("notes").ifBlank { "Eine neue Version von RusTV ist verfügbar." })
                .setPositiveButton("Jetzt installieren") { _, _ -> download(a, info.optString("apk")) }
                .setNeutralButton("Diese überspringen") { _, _ -> p.edit().putLong("skipped", code).apply() }
                .setNegativeButton("Später", null)
                .show()
        }
    }

    private fun download(a: AppCompatActivity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Bitte „Unbekannte Apps installieren“ für RusTV erlauben und dann erneut tippen.", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", 0).apply()
            return
        }
        val progress = MaterialAlertDialogBuilder(a).setTitle("Update wird geladen …").setMessage("Bitte kurz warten.").setCancelable(false).show()
        a.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val dir = File(a.cacheDir, "updates").apply { mkdirs() }
                    val f = File(dir, "rustv-update.apk")
                    get(url).inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f
                } catch (e: Exception) { null }
            }
            progress.dismiss()
            if (file == null || file.length() < 100_000) {
                Toast.makeText(a, "Download fehlgeschlagen", Toast.LENGTH_LONG).show(); return@launch
            }
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
            a.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
