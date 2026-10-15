package fr.moulou.integrity.reference

import fr.moulou.integrity.model.InventoryEntry
import fr.moulou.integrity.model.InventoryReport

/** Ce qui change d'un inventaire à l'autre, pour relire une nouvelle référence avant de l'activer. */
data class ReferenceDiff(
    val identityChanges: List<String>,
    val added: List<InventoryEntry>,
    val missing: List<InventoryEntry>,
    val modified: List<Pair<InventoryEntry, InventoryEntry>>,
    val renamed: List<Pair<InventoryEntry, InventoryEntry>>,
) {
    companion object {
        fun between(before: InventoryReport, after: InventoryReport): ReferenceDiff {
            val identityChanges = buildList {
                if (before.pack.id != after.pack.id) add("identifiant du modpack : ${before.pack.id} -> ${after.pack.id}")
                if (before.pack.version != after.pack.version) add("version du modpack : ${before.pack.version} -> ${after.pack.version}")
                if (before.pack.minecraftVersion != after.pack.minecraftVersion) add("Minecraft : ${before.pack.minecraftVersion} -> ${after.pack.minecraftVersion}")
            }
            val expected = before.entries.toMutableList()
            val remaining = ArrayList<InventoryEntry>()
            val renamed = ArrayList<Pair<InventoryEntry, InventoryEntry>>()
            // Un contenu identique s'apparie d'abord, comme dans la comparaison des règles : un renommage n'est pas une modification.
            for (actual in after.entries) {
                val index = expected.indexOfFirst { it.category == actual.category && it.kind == actual.kind && it.sha256 == actual.sha256 && it.size == actual.size }
                if (index < 0) { remaining += actual; continue }
                val previous = expected.removeAt(index)
                if (previous.path != actual.path) renamed += previous to actual
            }
            val added = ArrayList<InventoryEntry>()
            val modified = ArrayList<Pair<InventoryEntry, InventoryEntry>>()
            for (actual in remaining) {
                val index = expected.indexOfFirst { it.category == actual.category && ((actual.modId != null && actual.modId == it.modId) || actual.path == it.path) }
                if (index < 0) added += actual else modified += expected.removeAt(index) to actual
            }
            return ReferenceDiff(identityChanges, added, expected, modified, renamed)
        }
    }

    val unchanged: Boolean get() = identityChanges.isEmpty() && added.isEmpty() && missing.isEmpty() && modified.isEmpty() && renamed.isEmpty()

    /** Le compte rendu lisible en console : un résumé, puis au plus `maxLines` lignes de détail. */
    fun describe(maxLines: Int = 40): String {
        if (unchanged) return "Aucune différence."
        val lines = identityChanges + added.map { "+ ${it.path}" } + missing.map { "- ${it.path}" } +
            modified.map { (previous, current) -> "~ ${current.path}${versionNote(previous, current)}" } + renamed.map { (previous, current) -> "> ${previous.path} -> ${current.path}" }
        val summary = "${added.size} ajout(s), ${missing.size} retrait(s), ${modified.size} modification(s), ${renamed.size} renommage(s)."
        val overflow = if (lines.size > maxLines) listOf("... ${lines.size - maxLines} ligne(s) de plus.") else emptyList()
        return (listOf(summary) + lines.take(maxLines) + overflow).joinToString("\n")
    }

    private fun versionNote(previous: InventoryEntry, current: InventoryEntry): String =
        if (previous.modVersion != null && current.modVersion != null && previous.modVersion != current.modVersion) " (${previous.modVersion} -> ${current.modVersion})" else ""
}
