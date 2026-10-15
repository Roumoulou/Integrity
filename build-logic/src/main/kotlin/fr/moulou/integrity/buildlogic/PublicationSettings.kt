package fr.moulou.integrity.buildlogic

import me.modmuss50.mpp.ReleaseType
import org.gradle.api.GradleException
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * Ce que la publication lit du projet, pour la racine comme pour chaque module de version, en une seule lecture.
 *
 * - `live` : `-Ppublish_live=true` ; sans lui, tout envoi est à blanc.
 * - `type` : `-Ppublish_type=beta` pour une bêta, release sans lui ; toute autre valeur refuse.
 * - `modVersion` et `tag` : `mod_version` de `gradle.properties`, et le tag `v<version>` que vise la release GitHub.
 * - `changelog` : `04-releases\<version>\changelog - a publier.md` du classeur, hors dépôt, le même texte pour les trois plateformes.
 * - `modrinthProjectId`, `curseforgeProjectId` : les clés de `gradle.properties`, absentes quand elles sont vides.
 * - `missingForLive` : ce qui manquerait à un envoi réel ; des chemins, des clés et des noms de variables, jamais la valeur d'un jeton.
 */
class PublicationSettings(providers: ProviderFactory, layout: ProjectLayout) {
    companion object {
        val TOKEN_VARIABLES: List<String> = listOf("MODRINTH_TOKEN", "CURSEFORGE_TOKEN", "GITHUB_TOKEN")
    }

    val live: Boolean = providers.gradleProperty("publish_live").map(String::toBoolean).getOrElse(false)
    val type: ReleaseType = when (val declared = providers.gradleProperty("publish_type").getOrElse("release")) {
        "release" -> ReleaseType.STABLE
        "beta" -> ReleaseType.BETA
        else -> throw GradleException("publish_type must be 'release' or 'beta', not '$declared'")
    }
    val modVersion: String = providers.gradleProperty("mod_version").get()
    val tag: String = "v$modVersion"
    val changelog: RegularFile = layout.settingsDirectory.file("../../04-releases/$modVersion/changelog - a publier.md")
    val modrinthProjectId: Provider<String> = providers.gradleProperty("modrinth_project_id").filter(String::isNotBlank)
    val curseforgeProjectId: Provider<String> = providers.gradleProperty("curseforge_project_id").filter(String::isNotBlank)
    val missingForLive: List<String> = buildList {
        if (!changelog.asFile.isFile) add(changelog.asFile.toString())
        if (!modrinthProjectId.isPresent) add("modrinth_project_id (gradle.properties)")
        if (!curseforgeProjectId.isPresent) add("curseforge_project_id (gradle.properties)")
        TOKEN_VARIABLES.filter { !providers.environmentVariable(it).isPresent }.forEach { add(it) }
    }
}
