package fr.moulou.integrity.rules

import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.LoadedMod
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.model.RuntimeBounds
import fr.moulou.integrity.model.RuntimeReport

object ReportValidation {
    private val hash = Regex("[a-f0-9]{64}")
    private val sha1 = Regex("[a-f0-9]{40}")
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
        if (report.runtime?.let(::invalid) == true) add("Le relevé d'exécution est invalide.")
    }

    /** Vrai quand le relevé sort des bornes de `RuntimeBounds` ou contredit sa propre forme (un mod imbriqué sans parent, des chemins hors d'une origine `PATH`). */
    private fun invalid(runtime: RuntimeReport): Boolean {
        fun bad(value: String, max: Int = RuntimeBounds.MAX_NAME): Boolean = value.length > max || value.any(Char::isISOControl)
        fun badMods(mods: List<LoadedMod>): Boolean = mods.size > RuntimeBounds.MAX_MODS || mods.map { it.id }.toSet().size != mods.size || mods.any { mod ->
            !modId.matches(mod.id) || mod.version.isBlank() || bad(mod.version) || mod.paths.size > RuntimeBounds.MAX_PATHS || mod.paths.any { bad(it, RuntimeBounds.MAX_STRING) } ||
                (mod.parent != null && !modId.matches(mod.parent)) || (mod.origin == OriginKind.NESTED) != (mod.parent != null) || (mod.origin != OriginKind.PATH && mod.paths.isNotEmpty())
        }
        val snapshot = runtime.preLaunch
        return runtime.integrityVersion.isBlank() || bad(runtime.integrityVersion) || bad(runtime.loaderVersion) || runtime.minecraftVersion.isBlank() || bad(runtime.minecraftVersion) ||
            runtime.jvmArguments.size > RuntimeBounds.MAX_ARGUMENTS || runtime.jvmArguments.any { bad(it, RuntimeBounds.MAX_STRING) } ||
            runtime.fabricProperties.size > RuntimeBounds.MAX_PROPERTIES || runtime.fabricProperties.any { (key, value) -> !key.startsWith("fabric.") || bad(key) || bad(value, RuntimeBounds.MAX_STRING) } ||
            runtime.gameJars.size > RuntimeBounds.MAX_GAME_JARS || runtime.gameJars.any { !sha1.matches(it.sha1) || it.size < 0 || bad(it.path, RuntimeBounds.MAX_STRING) } ||
            badMods(runtime.mods) ||
            runtime.selectedPacks.size > RuntimeBounds.MAX_PACKS || runtime.selectedPacks.any { bad(it.id) || bad(it.source) } || bad(runtime.resourcePackDirectory, RuntimeBounds.MAX_STRING) ||
            runtime.references.size > RuntimeBounds.MAX_REFERENCES || runtime.references.any { !modId.matches(it.modId) || bad(it.entry, RuntimeBounds.MAX_STRING) } ||
            runtime.mixinConfigs.size > RuntimeBounds.MAX_MIXIN_CONFIGS || runtime.mixinConfigs.sumOf { it.targets.size } > RuntimeBounds.MAX_TARGETS ||
            runtime.mixinConfigs.any { config -> !modId.matches(config.modId) || bad(config.name) || config.targets.any { bad(it) } } ||
            (runtime.integrityJarHmac != null && !hash.matches(runtime.integrityJarHmac)) ||
            (snapshot != null && badMods(snapshot.mods))
    }
}
