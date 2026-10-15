package fr.moulou.integrity.rules

import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.Category

object ReportValidation {
    private val hash = Regex("[a-f0-9]{64}")
    private val modId = Regex("[a-z][a-z0-9_-]{1,63}")

    fun problems(report: InventoryReport, maxElements: Int): List<String> = buildList {
        if (report.schemaVersion != 1) add("Format d'inventaire incompatible.")
        if (report.error != null) add("Le client n'a pas pu terminer la vérification ; son journal latest.log donne la cause.")
        val identity = report.pack
        if (listOf(identity.id, identity.version, identity.minecraftVersion).any { it.isBlank() || it.length > 128 || it.any(Char::isISOControl) }) add("Identité du modpack invalide.")
        if (report.entries.size > maxElements) add("L'inventaire dépasse la limite d'éléments.")
        if (report.entries.map { it.path }.toSet().size != report.entries.size) add("L'inventaire contient des chemins en double.")
        val ids = report.entries.mapNotNull { it.modId }
        if (ids.toSet().size != ids.size) add("L'inventaire contient des identifiants de mods en double.")
        if (report.entries.any { entry ->
                val path = entry.path
                path.length > 512 || !path.startsWith("${entry.category.directory}/") || path.split('/').any { it.isEmpty() || it == ".." || it == "." } ||
                    path.any { it.isISOControl() || it == '\\' || it == ':' } || !hash.matches(entry.sha256) || entry.size < 0 ||
                    (entry.modId != null && (!modId.matches(entry.modId) || entry.category != Category.MODS)) ||
                    (entry.modVersion != null && (entry.modVersion.isBlank() || entry.modVersion.length > 128 || entry.modVersion.any(Char::isISOControl))) ||
                    (entry.modId == null) != (entry.modVersion == null)
            }) add("L'inventaire contient un élément invalide.")
    }
}
