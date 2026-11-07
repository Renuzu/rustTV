package de.stu.iptv

/** Hilfen für Kategorien („Bouquets“): Reihenfolge, Länderkürzel, Tresor. */
object Bouquets {
    private val PREFIX = Regex("^[^A-Za-z0-9]*([A-Za-z]{2,4})(?=[^A-Za-z0-9])")

    /** Länderkürzel am Anfang, z. B. „|TR| BEIN SPORTS“ → „TR“. */
    fun prefix(name: String): String? = PREFIX.find(name)?.groupValues?.get(1)?.uppercase()

    /** Alle Kategorien in der vom Nutzer festgelegten Reihenfolge (sonst A–Z). */
    fun ordered(store: Store, kind: Kind): List<Category> {
        val cats = Cache.categories[kind] ?: return emptyList()
        val byName = cats.sortedBy { it.name.lowercase() }
        val saved = store.catOrder(kind)
        if (saved.isEmpty()) return byName
        val byId = cats.associateBy { it.id }
        val first = saved.mapNotNull { byId[it] }
        val seen = first.map { it.id }.toHashSet()
        return first + byName.filter { it.id !in seen }
    }

    fun isVault(e: Entry, vaultCats: Set<String>, vaultItems: Set<String>) =
        e.categoryId in vaultCats || e.id in vaultItems

    /** Was außerhalb des Tresors sichtbar ist (ohne ausgeblendete und ohne gesperrte Kategorien). */
    fun normal(store: Store, kind: Kind): List<Entry> {
        val all = Cache.entries[kind] ?: return emptyList()
        val hidden = store.hiddenCats(kind)
        val locked = if (Cache.unlocked) emptySet() else MainActivity.lockedCategories(store, kind)
        val vc = store.vaultCats(kind)
        val vi = store.vaultItems(kind)
        return all.filter { it.categoryId !in hidden && it.categoryId !in locked && !isVault(it, vc, vi) }
    }
}
