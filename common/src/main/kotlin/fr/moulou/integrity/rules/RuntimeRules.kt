package fr.moulou.integrity.rules

import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.protocol.ControlWire
import fr.moulou.integrity.protocol.JarHmac
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Les règles du relevé d'exécution : ce qu'un client doit avoir chargé, d'où, et comment il a été lancé. Chaque problème est une phrase pour le
 * joueur. Ce que `allowDevelopmentClients` tolère est ce que les runs Loom font ; le reste se refuse quel que soit le réglage.
 */
object RuntimeRules {
    /** Le package du mod, celui que vise un mixin ou que nomme une classe d'un mod voisin ; les classes renommées le gardent. */
    const val PACKAGE_PREFIX = "fr.moulou.integrity"

    /** Les propriétés qui changent ce que Loader charge, refusées toujours. */
    val REJECTED_PROPERTIES: Set<String> = setOf("fabric.addMods", "fabric.modsFolder", "fabric.skipMcProvider", "fabric.systemLibraries")
    const val REJECTED_PROPERTY_PREFIX = "fabric.debug."

    /** Les propriétés d'un run Loom, tolérées seulement avec `allowDevelopmentClients`. */
    val DEVELOPMENT_PROPERTIES: Set<String> = setOf(
        "fabric.development", "fabric.classPathGroups", "fabric.remapClasspathFile", "fabric.gameJarPath", "fabric.gameJarPath.client", "fabric.gameJarPath.server",
        "fabric.defaultModDistributionNamespace", "fabric.defaultMixinRemapType",
    )

    /** Les mods que Loader fournit lui-même : leur origine n'est pas un JAR de `mods`. */
    val BUILTIN_MODS: Set<String> = setOf("minecraft", "java", "fabricloader")

    private val neutralPrefixes = listOf("fabric.log.")
    private val neutralProperties = setOf("fabric.noGui")

    /**
     * Les problèmes du relevé d'un client ; `nonce` est celui du contrôle, qui lie l'authentification du JAR à ce contrôle, ou `null` hors d'un
     * contrôle, où cette règle ne s'applique pas.
     */
    fun problems(reference: InventoryReport, client: InventoryReport, config: ServerConfig, expectations: ServerExpectations, nonce: ByteArray? = null): List<String> {
        val runtime = client.runtime ?: return listOf("Le client n'a pas transmis son relevé d'exécution.")
        val development = config.allowDevelopmentClients
        val scanned = config.scannedCategories()
        return buildList {
            if (runtime.protocolVersion != ControlWire.VERSION) add("Protocole Integrity ${runtime.protocolVersion} refusé ; ce serveur parle le protocole ${ControlWire.VERSION}.")
            when (val expected = expectations.integrityVersion) {
                null -> add("Le serveur ne connaît pas sa propre version d'Integrity.")
                runtime.integrityVersion -> Unit
                else -> add("Integrity $expected est requis ; le client est en ${runtime.integrityVersion}.")
            }
            if (config.requireSameMinecraft && runtime.minecraftVersion != reference.pack.minecraftVersion) add("Minecraft ${reference.pack.minecraftVersion} est requis ; le client exécute ${runtime.minecraftVersion}.")
            if (runtime.developmentEnvironment && !development) add("Client de développement refusé (fabric.development).")
            runtime.fabricProperties.keys.sorted().forEach { key ->
                when {
                    key in REJECTED_PROPERTIES || key.startsWith(REJECTED_PROPERTY_PREFIX) -> add("Propriété de lancement refusée : $key.")
                    key in DEVELOPMENT_PROPERTIES -> if (!development) add("Propriété de développement refusée : $key.")
                    key in neutralProperties || neutralPrefixes.any(key::startsWith) -> Unit
                    !development -> add("Propriété de lancement inconnue refusée : $key.")
                }
            }
            runtime.jvmArguments.forEach { argument ->
                val property = argument.removePrefix("-D").substringBefore('=')
                val rejectedProperty = argument.startsWith("-D") && (property in REJECTED_PROPERTIES || property.startsWith(REJECTED_PROPERTY_PREFIX))
                if (rejectedProperty || config.rejectedLaunchOptions.any(argument::startsWith)) add("Option de lancement refusée : ${argument.take(80)}.")
            }
            if (!development) {
                val expected = expectations.clientSha1
                if (expected == null) add("Le serveur ne connaît pas l'empreinte attendue du jar du jeu.")
                else if (runtime.gameJars.size != 1 || runtime.gameJars.single().sha1 != expected) add("Jar du jeu inattendu : ${runtime.gameJars.joinToString { it.sha1.take(12) }.ifEmpty { "aucun" }}.")
            }

            // Tout mod chargé depuis un chemin vient de `mods`, sauf en développement, et tout JAR de mod de `mods` est chargé tel quel.
            val modsScanned = Category.MODS in scanned
            val modJars = client.entries.filter { it.category == Category.MODS && it.modId != null }.associateBy { it.path }
            val loadedIds = runtime.mods.map { it.id }.toSet()
            runtime.mods.filter { it.id !in BUILTIN_MODS }.forEach { mod ->
                when (mod.origin) {
                    OriginKind.PATH -> {
                        if (mod.paths.isEmpty() && !development) add("Mod chargé sans chemin : ${mod.id}.")
                        mod.paths.forEach { path ->
                            val inMods = path.startsWith("mods/") && '/' !in path.removePrefix("mods/")
                            when {
                                !inMods -> if (!development) add("Mod chargé hors du dossier mods : ${mod.id} ($path).")
                                !modsScanned -> Unit
                                else -> {
                                    val entry = modJars[path]
                                    if (entry == null) add("Mod chargé absent de l'inventaire : ${mod.id} ($path).")
                                    else if (entry.modId != mod.id || entry.modVersion != mod.version) add("Mod chargé différent du fichier inventorié : ${mod.id} ($path).")
                                }
                            }
                        }
                    }
                    OriginKind.NESTED -> if (mod.parent == null || mod.parent !in loadedIds) add("Mod imbriqué sans parent chargé : ${mod.id}.")
                    OriginKind.UNKNOWN -> if (!development) add("Mod chargé d'origine inconnue : ${mod.id}.")
                }
            }
            if (modsScanned) {
                modJars.keys.sorted().forEach { path -> if (runtime.mods.none { it.origin == OriginKind.PATH && path in it.paths }) add("Mod présent mais non chargé : $path.") }
            }
            val snapshot = runtime.preLaunch
            if (snapshot == null) add("L'instantané du lancement manque ; le journal du client donne la cause.")
            else if (snapshot.mods.toSet() != runtime.mods.toSet()) add("La liste des mods a changé depuis le lancement.")

            // Un voisin qui vise Integrity, par un mixin ou par une référence à son package, refuse : chaque patch de plus coûte.
            runtime.mixinConfigs.forEach { mixin ->
                mixin.targets.filter { it.startsWith(PACKAGE_PREFIX) }.take(3).forEach { target -> add("Mixin visant Integrity : ${mixin.modId} (${mixin.name}, $target).") }
            }
            runtime.references.take(8).forEach { add("Mod faisant référence à Integrity : ${it.modId} (${it.entry}).") }

            // Le JAR Integrity du client est celui du serveur, authentifié avec le nonce de ce contrôle ; un run Loom n'a pas de JAR.
            if (!development && nonce != null) {
                val jar = expectations.integrityJar
                val declared = runtime.integrityJarHmac
                when {
                    jar == null -> add("Le serveur ne peut pas authentifier son JAR Integrity.")
                    declared == null -> add("Le client n'a pas authentifié son JAR Integrity.")
                    !MessageDigest.isEqual(JarHmac.of(jar, nonce).toByteArray(StandardCharsets.US_ASCII), declared.toByteArray(StandardCharsets.US_ASCII)) -> add("Le JAR Integrity du client n'est pas celui du serveur.")
                }
            }

            // Un resourcepack sélectionné depuis un fichier est dans `resourcepacks`, et ce dossier est celui du dossier de jeu.
            if (Category.RESOURCEPACKS in scanned) {
                val present = client.entries.filter { it.category == Category.RESOURCEPACKS }.map { it.path }.toSet()
                runtime.selectedPacks.filter { it.id.startsWith("file/") }.forEach { pack ->
                    if ("${Category.RESOURCEPACKS.directory}/${pack.id.removePrefix("file/")}" !in present) add("Resourcepack sélectionné hors de l'inventaire : ${pack.id}.")
                }
                if (runtime.resourcePackDirectory != Category.RESOURCEPACKS.directory) add("Dossier des resourcepacks déplacé : ${runtime.resourcePackDirectory.take(80)}.")
            }
        }
    }
}
