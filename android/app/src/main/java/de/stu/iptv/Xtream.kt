package de.stu.iptv

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class Kind(val title: String, val catAction: String, val listAction: String) {
    LIVE("Live-TV", "get_live_categories", "get_live_streams"),
    MOVIE("Filme", "get_vod_categories", "get_vod_streams"),
    SERIES("Serien", "get_series_categories", "get_series")
}

data class Category(val id: String, val name: String)

data class Entry(
    val id: String,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val ext: String?,
    val subtitle: String? = null,
    val archive: Boolean = false
)

data class Programme(
    val title: String,
    val description: String,
    val startText: String,   // Server-Zeit "yyyy-MM-dd HH:mm:ss"
    val startTs: Long,
    val endTs: Long,
    val hasArchive: Boolean
)

class Xtream(server: String, private val user: String, private val pass: String,
             private val userAgent: String = "RusTV-Android") {

    val base: String = normalize(server)

    companion object {
        fun normalize(s: String): String {
            var u = s.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            u = u.removeSuffix("/")
            u = u.removeSuffix("/player_api.php").removeSuffix("/get.php")
            return u.removeSuffix("/")
        }

        fun from(store: Store): Xtream {
            val a = store.account ?: return Xtream("http://localhost", "", "")
            return Xtream(a.server, a.user, a.pass, store.userAgent)
        }
    }

    private fun api(action: String? = null, extra: String = ""): String {
        var url = "$base/player_api.php?username=${Uri.encode(user)}&password=${Uri.encode(pass)}"
        if (action != null) url += "&action=$action"
        return get(url + extra)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw Exception("Server antwortet mit Fehler $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Prüft die Zugangsdaten. Gibt eine Fehlermeldung zurück oder null bei Erfolg. */
    fun login(): String? {
        val json = try { JSONObject(api()) } catch (e: Exception) {
            return "Unerwartete Antwort vom Server. Stimmt die Adresse?"
        }
        val info = json.optJSONObject("user_info") ?: return "Anmeldung fehlgeschlagen."
        if (info.optInt("auth", 0) != 1) return "Benutzername oder Passwort falsch."
        val status = info.optString("status", "")
        if (status.isNotEmpty() && !status.equals("Active", true)) return "Konto-Status: $status"
        return null
    }

    /** Konto-Infos als lesbare Zeilen. */
    fun accountInfo(): List<Pair<String, String>> {
        val json = JSONObject(api())
        val u = json.optJSONObject("user_info") ?: JSONObject()
        val s = json.optJSONObject("server_info") ?: JSONObject()
        val exp = u.str("exp_date")?.toLongOrNull()
        val expText = if (exp == null) "unbegrenzt / unbekannt"
        else java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date(exp * 1000))
        return listOf(
            "Status" to (u.str("status") ?: "?"),
            "Gültig bis" to expText,
            "Aktive Verbindungen" to "${u.str("active_cons") ?: "?"} von ${u.str("max_connections") ?: "?"}",
            "Testkonto" to (if (u.str("is_trial") == "1") "ja" else "nein"),
            "Server-Zeitzone" to (s.str("timezone") ?: "?"),
            "Server" to base
        )
    }

    fun categories(kind: Kind): List<Category> {
        val arr = parseArray(api(kind.catAction))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Category(o.str("category_id") ?: "", o.str("category_name") ?: "?")
        }
    }

    fun entries(kind: Kind): List<Entry> {
        val arr = parseArray(api(kind.listAction))
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val e = when (kind) {
                Kind.LIVE -> Entry(o.str("stream_id") ?: continue, o.str("name") ?: "?",
                    o.str("category_id") ?: "", o.str("stream_icon"), null,
                    archive = o.str("tv_archive") == "1")
                Kind.MOVIE -> Entry(o.str("stream_id") ?: continue, o.str("name") ?: "?",
                    o.str("category_id") ?: "", o.str("stream_icon"),
                    o.str("container_extension") ?: "mp4")
                Kind.SERIES -> Entry(o.str("series_id") ?: continue, o.str("name") ?: "?",
                    o.str("category_id") ?: "", o.str("cover"), null)
            }
            out.add(e)
        }
        return out
    }

    /** Episoden einer Serie, sortiert nach Staffel und Folge. */
    fun episodes(seriesId: String): List<Entry> {
        val json = JSONObject(api("get_series_info", "&series_id=${Uri.encode(seriesId)}"))
        val out = ArrayList<Pair<Pair<Int, Int>, Entry>>()
        fun addEp(season: Int, o: JSONObject) {
            val id = o.str("id") ?: return
            val num = o.optString("episode_num", "0").toIntOrNull() ?: 0
            val title = o.str("title") ?: "Folge $num"
            val icon = o.optJSONObject("info")?.str("movie_image")
            out.add((season to num) to Entry(id, title, season.toString(), icon,
                o.str("container_extension") ?: "mp4", "Staffel $season · Folge $num"))
        }
        when (val eps = json.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val season = k.toIntOrNull() ?: 0
                val list = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { addEp(season, it) }
            }
            is JSONArray -> for (s in 0 until eps.length()) {
                val list = eps.optJSONArray(s) ?: continue
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    addEp(o.optString("season", "${s + 1}").toIntOrNull() ?: (s + 1), o)
                }
            }
        }
        return out.sortedWith(compareBy({ it.first.first }, { it.first.second })).map { it.second }
    }

    /** Kurzes EPG (jetzt + nächste Sendungen). */
    fun shortEpg(streamId: String, limit: Int = 3): List<Programme> =
        parseEpg(api("get_short_epg", "&stream_id=${Uri.encode(streamId)}&limit=$limit"))

    /** Komplettes EPG inkl. Archiv-Kennzeichen (für Catch-up). */
    fun fullEpg(streamId: String): List<Programme> =
        parseEpg(api("get_simple_data_table", "&stream_id=${Uri.encode(streamId)}"))

    private fun parseEpg(txt: String): List<Programme> {
        val arr = try { JSONObject(txt).optJSONArray("epg_listings") } catch (e: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Programme(
                title = b64(o.str("title")) ?: "?",
                description = b64(o.str("description")) ?: "",
                startText = o.str("start") ?: "",
                startTs = o.str("start_timestamp")?.toLongOrNull() ?: 0,
                endTs = (o.str("stop_timestamp") ?: o.str("end_timestamp"))?.toLongOrNull() ?: 0,
                hasArchive = o.str("has_archive") == "1"
            )
        }.sortedBy { it.startTs }
    }

    private fun b64(s: String?): String? {
        if (s == null) return null
        return try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { s }
    }

    fun streamUrl(kind: Kind, e: Entry, liveFormat: String = "ts"): String {
        val u = Uri.encode(user)
        val p = Uri.encode(pass)
        return when (kind) {
            Kind.LIVE -> "$base/live/$u/$p/${e.id}.$liveFormat"
            Kind.MOVIE -> "$base/movie/$u/$p/${e.id}.${e.ext ?: "mp4"}"
            Kind.SERIES -> "$base/series/$u/$p/${e.id}.${e.ext ?: "mp4"}"
        }
    }

    /** Archiv-Adresse (Catch-up) für eine vergangene Sendung. */
    fun timeshiftUrl(streamId: String, prog: Programme): String {
        val minutes = ((prog.endTs - prog.startTs) / 60).coerceAtLeast(1)
        // "2026-11-07 20:15:00" -> "2026-11-07:20-15"
        val t = prog.startText
        val start = if (t.length >= 16) t.substring(0, 10) + ":" + t.substring(11, 13) + "-" + t.substring(14, 16) else t
        return "$base/timeshift/${Uri.encode(user)}/${Uri.encode(pass)}/$minutes/$start/$streamId.ts"
    }

    private fun parseArray(txt: String): JSONArray =
        try { JSONArray(txt) } catch (e: Exception) { JSONArray() }
}

/** Liefert einen String-Wert oder null (auch wenn der Server "null" oder Zahlen liefert). */
fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)?.toString()?.trim() ?: return null
    return if (v.isEmpty() || v == "null") null else v
}
