package de.stu.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Account(val id: String, val name: String, val server: String, val user: String, val pass: String)

/** Alle gespeicherten Daten. Favoriten, Reihenfolge usw. gelten je Konto. */
class Store(val ctx: Context) {
    companion object { const val FILE = "iptv" }

    val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init { migrateOldLogin() }

    // ---------- Konten ----------
    fun accounts(): List<Account> {
        val arr = try { JSONArray(p.getString("accounts", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Account(o.optString("id"), o.optString("name"), o.optString("server"),
                o.optString("user"), o.optString("pass"))
        }
    }

    private fun saveAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("server", it.server)
                .put("user", it.user).put("pass", it.pass))
        }
        p.edit().putString("accounts", arr.toString()).apply()
    }

    fun addAccount(name: String, server: String, user: String, pass: String): Account {
        val acc = Account(System.currentTimeMillis().toString(), name, server, user, pass)
        saveAccounts(accounts() + acc)
        activeId = acc.id
        return acc
    }

    fun removeAccount(id: String) {
        val rest = accounts().filter { it.id != id }
        saveAccounts(rest)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("a${id}_") }.forEach { e.remove(it) }
        e.apply()
        if (activeId == id) activeId = rest.firstOrNull()?.id ?: ""
    }

    var activeId: String
        get() = p.getString("active", "") ?: ""
        set(v) = p.edit().putString("active", v).apply()

    val account: Account? get() = accounts().firstOrNull { it.id == activeId } ?: accounts().firstOrNull()
    val loggedIn get() = account != null

    private fun migrateOldLogin() {
        val s = p.getString("server", null) ?: return
        val u = p.getString("user", "") ?: ""
        val pw = p.getString("pass", "") ?: ""
        p.edit().remove("server").remove("user").remove("pass").apply()
        if (accounts().isEmpty()) {
            val acc = Account("1", "Mein Konto", s, u, pw)
            saveAccounts(listOf(acc)); activeId = acc.id
            // alte Favoriten/Reihenfolge übernehmen
            val e = p.edit()
            Kind.entries.forEach { k ->
                p.getStringSet("fav_${k.name}", null)?.let { e.putStringSet("a1_fav_${k.name}", it) }
                p.getString("order_${k.name}", null)?.let { e.putString("a1_order_${k.name}", it) }
            }
            e.apply()
        }
    }

    private fun k(key: String) = "a${account?.id ?: "0"}_$key"

    // ---------- Einstellungen (Einstellungen-Seite) ----------
    /** Aussehen: 0 Chips, 1 Seitenleiste, 2 Kacheln, 3 Kino */
    var design: Int
        get() = p.getInt("design", 0)
        set(v) = p.edit().putInt("design", v).apply()

    var sortMode: Int
        get() = p.getInt("sort", 0)
        set(v) = p.edit().putInt("sort", v).apply()
    var favFirst: Boolean
        get() = p.getBoolean("favfirst", true)
        set(v) = p.edit().putBoolean("favfirst", v).apply()
    var external: Boolean
        get() = p.getBoolean("external", false)
        set(v) = p.edit().putBoolean("external", v).apply()
    val liveFormat get() = p.getString("live_format", "ts") ?: "ts"
    val buffer get() = p.getString("buffer", "normal") ?: "normal"
    val gestures get() = p.getBoolean("gestures", true)
    val swipeZap get() = gestures && p.getBoolean("swipe_zap", true)
    val gVolume get() = gestures && p.getBoolean("g_volume", true)
    val gBright get() = gestures && p.getBoolean("g_bright", true)
    val gSeek get() = gestures && p.getBoolean("g_seek", true)
    val posterGrid get() = p.getBoolean("poster_grid", true)
    val pinchZoom get() = p.getBoolean("pinch_zoom", true)
    // VPN / Proxy (nur für diese App)
    /** Proxy is configured (type + server). */
    val proxyConfigured get() = (p.getString("proxy_kind", "") ?: "").isNotEmpty() && proxyHost.isNotBlank()
    /** Start-screen button: proxy switched on/off (settings stay). */
    var proxyOn: Boolean
        get() = p.getBoolean("proxy_on", true)
        set(v) = p.edit().putBoolean("proxy_on", v).apply()
    /** Effective proxy type ("" when switched off). */
    val proxyKind get() = if (proxyOn) (p.getString("proxy_kind", "") ?: "") else ""
    val proxyHost get() = p.getString("proxy_host", "") ?: ""
    val proxyPort get() = (p.getString("proxy_port", "1080") ?: "1080").trim().toIntOrNull() ?: 1080
    val proxyUser get() = p.getString("proxy_user", "") ?: ""
    val proxyPass get() = p.getString("proxy_pass", "") ?: ""
    val resume get() = p.getBoolean("resume", true)
    val autoNext get() = p.getBoolean("auto_next", true)
    val autoPip get() = p.getBoolean("auto_pip", false)
    val startLast get() = p.getBoolean("start_last", false)
    val showLogos get() = p.getBoolean("show_logos", true)
    val autoLockAdult get() = p.getBoolean("auto_lock_adult", true)
    val userAgent get() = (p.getString("user_agent", "") ?: "").ifBlank { "rustTV-Android" }
    var resizeMode: Int
        get() = p.getInt("resize", 0)
        set(v) = p.edit().putInt("resize", v).apply()

    // ---------- Jugendschutz ----------
    var pin: String
        get() = p.getString("pin", "") ?: ""
        set(v) = p.edit().putString("pin", v).apply()

    fun lockedCats(kind: Kind) = set("locked_${kind.name}")
    fun setLockedCats(kind: Kind, ids: Set<String>) = putSet("locked_${kind.name}", ids)
    fun hiddenCats(kind: Kind) = set("hidden_${kind.name}")
    fun setHiddenCats(kind: Kind, ids: Set<String>) = putSet("hidden_${kind.name}", ids)

    // ---------- Bouquets (Kategorien) ----------
    fun catOrder(kind: Kind): List<String> = list("catorder_${kind.name}")
    fun setCatOrder(kind: Kind, ids: List<String>) = putList("catorder_${kind.name}", ids)
    fun vaultCats(kind: Kind) = set("vault_${kind.name}")
    fun setVaultCats(kind: Kind, ids: Set<String>) = putSet("vault_${kind.name}", ids)
    fun vaultItems(kind: Kind) = set("vaulti_${kind.name}")
    fun setVaultItems(kind: Kind, ids: Set<String>) = putSet("vaulti_${kind.name}", ids)

    // ---------- Favoriten, Reihenfolge, Verlauf ----------
    fun favorites(kind: Kind) = set("fav_${kind.name}")
    fun setFavorites(kind: Kind, ids: Set<String>) = putSet("fav_${kind.name}", ids)

    fun customOrder(kind: Kind): List<String> = list("order_${kind.name}")
    fun setCustomOrder(kind: Kind, ids: List<String>) = putList("order_${kind.name}", ids)

    fun history(kind: Kind): List<String> = list("hist_${kind.name}")
    fun addHistory(kind: Kind, id: String) {
        putList("hist_${kind.name}", (listOf(id) + history(kind).filter { it != id }).take(50))
    }

    var lastLive: String
        get() = p.getString(k("last_live"), "") ?: ""
        set(v) = p.edit().putString(k("last_live"), v).apply()

    fun position(id: String): Long = p.getLong(k("pos_$id"), 0L)
    fun setPosition(id: String, pos: Long) {
        if (pos <= 0) p.edit().remove(k("pos_$id")).apply() else p.edit().putLong(k("pos_$id"), pos).apply()
    }

    private fun set(key: String): MutableSet<String> = HashSet(p.getStringSet(k(key), emptySet()) ?: emptySet())
    private fun putSet(key: String, v: Set<String>) = p.edit().putStringSet(k(key), HashSet(v)).apply()
    private fun list(key: String) = (p.getString(k(key), "") ?: "").split('\n').filter { it.isNotEmpty() }
    private fun putList(key: String, v: List<String>) = p.edit().putString(k(key), v.joinToString("\n")).apply()
}
