package fr.moulou.integrity.model

import kotlinx.serialization.Serializable

/** Le genre d'origine d'un mod chargé, celui de `ModOrigin.Kind` de Fabric Loader. */
@Serializable
enum class OriginKind { PATH, NESTED, UNKNOWN }

/**
 * Un mod que Fabric Loader a chargé. `paths` ne vaut que pour une origine `PATH` : relatifs au dossier de jeu, avec des `/`, quand ils y sont,
 * absolus sinon ; `parent` ne vaut que pour une origine `NESTED`.
 */
@Serializable
data class LoadedMod(val id: String, val version: String, val origin: OriginKind, val paths: List<String> = emptyList(), val parent: String? = null)

/** Un jar du jeu que Loader a chargé ; son SHA-1 se compare à celui que Mojang publie pour le `client.jar`. */
@Serializable
data class GameJar(val path: String, val sha1: String, val size: Long)

/** Un resourcepack sélectionné par le client ; `source` est le nom de la constante `PackSource` du jeu, ou `OTHER`. */
@Serializable
data class SelectedPack(val id: String, val source: String)

/** Une configuration Mixin qu'un mod déclare, et les classes que ses mixins visent, lues dans leurs annotations. */
@Serializable
data class MixinConfigReport(val modId: String, val name: String, val targets: List<String> = emptyList())

/** Une classe d'un mod voisin qui nomme le package d'Integrity, par un nom de classe ou une chaîne. */
@Serializable
data class ModReference(val modId: String, val entry: String)

/** Ce que le client a vu à l'entrypoint `preLaunch`, avant les entrypoints `main` et `client` des autres mods. */
@Serializable
data class PreLaunchSnapshot(val mods: List<LoadedMod> = emptyList())

/**
 * Le relevé d'exécution du client : ce qui est chargé, d'où, et comment le jeu a été lancé. Déclaré par le client, comme l'inventaire.
 * `integrityJarHmac` authentifie le JAR Integrity du client avec le nonce du contrôle ; `null` quand le mod ne vient pas d'un JAR.
 */
@Serializable
data class RuntimeReport(
    val integrityVersion: String,
    val protocolVersion: Int,
    val loaderVersion: String,
    val minecraftVersion: String,
    val developmentEnvironment: Boolean,
    val jvmArguments: List<String> = emptyList(),
    val fabricProperties: Map<String, String> = emptyMap(),
    val gameJars: List<GameJar> = emptyList(),
    val mods: List<LoadedMod> = emptyList(),
    val selectedPacks: List<SelectedPack> = emptyList(),
    val resourcePackDirectory: String = "",
    val preLaunch: PreLaunchSnapshot? = null,
    val mixinConfigs: List<MixinConfigReport> = emptyList(),
    val references: List<ModReference> = emptyList(),
    val integrityJarHmac: String? = null,
)

/** Les bornes du relevé d'exécution : le client coupe ce qui dépasse, le serveur refuse ce qui dépasse. */
object RuntimeBounds {
    const val MAX_ARGUMENTS = 512
    const val MAX_PROPERTIES = 64
    const val MAX_MODS = 2000
    const val MAX_PATHS = 32
    const val MAX_GAME_JARS = 8
    const val MAX_PACKS = 512
    const val MAX_MIXIN_CONFIGS = 2000
    const val MAX_TARGETS = 50_000
    const val MAX_REFERENCES = 64
    const val MAX_NAME = 512
    const val MAX_STRING = 4096
}
