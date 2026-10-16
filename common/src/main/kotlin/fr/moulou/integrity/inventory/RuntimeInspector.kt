package fr.moulou.integrity.inventory

import fr.moulou.integrity.IntegrityMod
import fr.moulou.integrity.IntegrityPreLaunch
import fr.moulou.integrity.model.GameJar
import fr.moulou.integrity.model.LoadedMod
import fr.moulou.integrity.model.MixinConfigReport
import fr.moulou.integrity.model.ModReference
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.model.PreLaunchSnapshot
import fr.moulou.integrity.model.RuntimeBounds
import fr.moulou.integrity.model.RuntimeReport
import fr.moulou.integrity.model.SelectedPack
import fr.moulou.integrity.protocol.ControlWire
import fr.moulou.integrity.protocol.JarHmac
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import net.fabricmc.loader.api.metadata.ModOrigin
import java.lang.classfile.AnnotationValue
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Relève ce que Fabric Loader a chargé et comment la JVM a été lancée ; l'inventaire des dossiers reste à `InventoryScanner`.
 * Tout est coupé aux bornes de `RuntimeBounds`, parce que le rapport est borné et que le serveur refuse ce qui dépasse.
 */
object RuntimeInspector {
    /** La propriété d'un run Loom qui fait déclarer une autre version d'Integrity, pour l'essai du refus d'un client trop ancien. */
    const val DECLARED_VERSION_PROPERTY = "integrity.dev.declaredVersion"

    private const val MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;"

    /** Les mods que Loader fournit lui-même : rien à y chercher. */
    private val BUILTIN_MODS = setOf("minecraft", "java", "fabricloader")
    private const val UNKNOWN = "unknown"
    private val hashes = HashMap<Path, Pair<Stamp, GameJar>>()
    private val packagePatterns = listOf("fr/moulou/integrity", "fr.moulou.integrity").map { it.toByteArray(StandardCharsets.ISO_8859_1) }

    // Les mods ne changent pas pendant une partie : leurs mixins et leurs références à Integrity se lisent une fois, au premier contrôle.
    private val neighbours: Neighbours by lazy {
        val loader = FabricLoader.getInstance()
        Neighbours(declaredMixins(loader), scanReferences(loader)).also { IntegrityMod.LOGGER.info("Neighbour mods read: {} mixin configs, {} references to Integrity", it.mixinConfigs.size, it.references.size) }
    }

    /** L'instantané du lancement : les mods chargés, avant les entrypoints `main` et `client` des autres mods. */
    fun snapshot(): PreLaunchSnapshot {
        val loader = FabricLoader.getInstance()
        return PreLaunchSnapshot(mods(loader, canonical(loader.gameDir)))
    }

    /**
     * Le relevé complet, pris sur le worker du contrôle ; `selectedPacks` et `resourcePackDirectory` viennent du fil du client, `nonce` du
     * challenge, pour authentifier le JAR du mod.
     */
    fun inspect(gameDirectory: Path, selectedPacks: List<SelectedPack>, resourcePackDirectory: Path?, nonce: ByteArray, preLaunch: PreLaunchSnapshot? = IntegrityPreLaunch.snapshot): RuntimeReport {
        val loader = FabricLoader.getInstance()
        val root = canonical(gameDirectory)
        val development = loader.isDevelopmentEnvironment
        val version = version(loader, IntegrityMod.MOD_ID)
        return RuntimeReport(
            // En développement seulement, un run peut déclarer une autre version, pour éprouver le refus d'un client trop ancien.
            integrityVersion = if (development) System.getProperty(DECLARED_VERSION_PROPERTY)?.take(RuntimeBounds.MAX_NAME) ?: version else version,
            protocolVersion = ControlWire.VERSION,
            loaderVersion = version(loader, "fabricloader"),
            minecraftVersion = version(loader, "minecraft"),
            developmentEnvironment = development,
            jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments.take(RuntimeBounds.MAX_ARGUMENTS).map { it.take(RuntimeBounds.MAX_STRING) },
            fabricProperties = fabricProperties(),
            gameJars = gameJars(loader, root),
            mods = mods(loader, root),
            selectedPacks = selectedPacks.take(RuntimeBounds.MAX_PACKS).map { SelectedPack(it.id.take(RuntimeBounds.MAX_NAME), it.source.take(RuntimeBounds.MAX_NAME)) },
            resourcePackDirectory = resourcePackDirectory?.let { relativize(root, it) }.orEmpty(),
            preLaunch = preLaunch,
            mixinConfigs = neighbours.mixinConfigs,
            references = neighbours.references,
            integrityJarHmac = ownJar(loader)?.let { JarHmac.of(it, nonce) },
        )
    }

    /** Le JAR du mod, quand Loader l'a chargé depuis un fichier ; `null` dans un run Loom, où le mod vient de dossiers de classes. */
    fun ownJar(loader: FabricLoader): Path? {
        val origin = loader.getModContainer(IntegrityMod.MOD_ID).map { it.origin }.orElse(null) ?: return null
        if (origin.kind != ModOrigin.Kind.PATH) return null
        return origin.paths.singleOrNull()?.takeIf { it.toString().endsWith(".jar") && Files.isRegularFile(it) }
    }

    private fun version(loader: FabricLoader, id: String): String = loader.getModContainer(id).map { it.metadata.version.friendlyString }.orElse(UNKNOWN).take(RuntimeBounds.MAX_NAME)

    private fun fabricProperties(): Map<String, String> = System.getProperties().stringPropertyNames().filter { it.startsWith("fabric.") }.sorted()
        .take(RuntimeBounds.MAX_PROPERTIES).associateWith { (System.getProperty(it) ?: "").take(RuntimeBounds.MAX_STRING) }

    private fun mods(loader: FabricLoader, root: Path): List<LoadedMod> = loader.allMods.map { container ->
        val metadata = container.metadata
        val origin = container.origin
        val id = metadata.id.take(RuntimeBounds.MAX_NAME)
        val version = metadata.version.friendlyString.take(RuntimeBounds.MAX_NAME)
        when (origin.kind) {
            ModOrigin.Kind.PATH -> LoadedMod(id, version, OriginKind.PATH, origin.paths.take(RuntimeBounds.MAX_PATHS).map { relativize(root, it) })
            ModOrigin.Kind.NESTED -> LoadedMod(id, version, OriginKind.NESTED, parent = origin.parentModId.take(RuntimeBounds.MAX_NAME))
            else -> LoadedMod(id, version, OriginKind.UNKNOWN)
        }
    }.sortedWith(compareBy({ it.id }, { it.version })).take(RuntimeBounds.MAX_MODS)

    /** Les jars du jeu, ceux que l'origine du mod interne `minecraft` désigne ; une empreinte déjà calculée sert tant que le fichier n'a pas bougé. */
    private fun gameJars(loader: FabricLoader, root: Path): List<GameJar> {
        val origin = loader.getModContainer("minecraft").map { it.origin }.orElse(null) ?: return emptyList()
        if (origin.kind != ModOrigin.Kind.PATH) return emptyList()
        return origin.paths.take(RuntimeBounds.MAX_GAME_JARS).mapNotNull { path -> hash(path, root) }
    }

    private fun hash(path: Path, root: Path): GameJar? {
        val attributes = try { Files.readAttributes(path, BasicFileAttributes::class.java) } catch (error: Exception) { return null }
        if (!attributes.isRegularFile) return null
        val stamp = Stamp(attributes.size(), attributes.lastModifiedTime().toMillis())
        synchronized(hashes) { hashes[path]?.let { (known, jar) -> if (known == stamp) return jar } }
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val jar = GameJar(relativize(root, path), HexFormat.of().formatHex(digest.digest()), attributes.size())
        synchronized(hashes) { hashes[path] = stamp to jar }
        return jar
    }

    /**
     * Les configurations Mixin que les autres mods déclarent dans leur `fabric.mod.json`, et les classes que leurs mixins visent, lues dans
     * l'annotation `@Mixin` de chaque classe. Mixin ne liste pas ce qu'il a appliqué (`Mixins.getConfigs()` ne tient que l'attente) : la
     * déclaration des mods fait foi, et un mod illisible est signalé, pas deviné.
     */
    private fun declaredMixins(loader: FabricLoader): List<MixinConfigReport> {
        val found = ArrayList<MixinConfigReport>()
        var budget = RuntimeBounds.MAX_TARGETS
        for (container in loader.allMods) {
            val id = container.metadata.id
            if (id == IntegrityMod.MOD_ID || id in BUILTIN_MODS) continue
            try {
                for (configName in mixinConfigNames(container)) {
                    if (found.size >= RuntimeBounds.MAX_MIXIN_CONFIGS) return found
                    val targets = mixinTargets(container, configName).take(budget).map { it.take(RuntimeBounds.MAX_NAME) }
                    budget -= targets.size
                    found += MixinConfigReport(id.take(RuntimeBounds.MAX_NAME), configName.take(RuntimeBounds.MAX_NAME), targets)
                }
            } catch (error: Exception) {
                IntegrityMod.LOGGER.warn("Cannot read the mixin configurations of mod {}", id, error)
            }
        }
        return found
    }

    /** Les fichiers de configuration Mixin de `fabric.mod.json`, déclarés par leur nom ou par un objet `{ config, environment }`. */
    private fun mixinConfigNames(container: ModContainer): List<String> {
        val file = container.findPath("fabric.mod.json").orElse(null) ?: return emptyList()
        val metadata = Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
        return (metadata["mixins"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> element["config"]?.jsonPrimitive?.content
                else -> null
            }
        }.orEmpty()
    }

    /** Les classes que les mixins d'une configuration visent : les classes de `value` et les chaînes de `targets` de leurs annotations `@Mixin`. */
    private fun mixinTargets(container: ModContainer, configName: String): List<String> {
        val file = container.findPath(configName).orElse(null) ?: return emptyList()
        val config = Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject
        val packageName = config["package"]?.jsonPrimitive?.content ?: return emptyList()
        val classes = listOf("mixins", "client", "server").flatMap { key -> (config[key] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty() }
        val targets = sortedSetOf<String>()
        for (name in classes) {
            val classFile = container.findPath("${packageName.replace('.', '/')}/${name.replace('.', '/')}.class").orElse(null) ?: continue
            val model = ClassFile.of().parse(Files.readAllBytes(classFile))
            // @Mixin est de rétention CLASS : l'annotation vit parmi les invisibles, mais rien n'interdit à un mod de la rendre visible.
            val annotations = model.findAttribute(Attributes.runtimeInvisibleAnnotations()).map { it.annotations() }.orElse(emptyList()) +
                model.findAttribute(Attributes.runtimeVisibleAnnotations()).map { it.annotations() }.orElse(emptyList())
            for (annotation in annotations) {
                if (annotation.className().stringValue() != MIXIN_ANNOTATION) continue
                for (element in annotation.elements()) {
                    val values = (element.value() as? AnnotationValue.OfArray)?.values() ?: continue
                    for (value in values) {
                        when (value) {
                            is AnnotationValue.OfClass -> targets += value.className().stringValue().removePrefix("L").removeSuffix(";").replace('/', '.')
                            is AnnotationValue.OfString -> targets += value.stringValue().replace('/', '.')
                            else -> Unit
                        }
                    }
                }
            }
        }
        return targets.toList()
    }

    /**
     * Les classes des autres mods qui nomment le package d'Integrity, par un nom interne (`fr/moulou/integrity`) ou une chaîne
     * (`fr.moulou.integrity`). Chaque mod est parcouru depuis ses racines, JAR imbriqués compris ; un mod illisible est signalé, pas deviné.
     */
    private fun scanReferences(loader: FabricLoader): List<ModReference> {
        val found = ArrayList<ModReference>()
        for (container in loader.allMods) {
            val id = container.metadata.id
            if (id == IntegrityMod.MOD_ID || id in BUILTIN_MODS) continue
            try {
                scanReferences(container, found)
            } catch (error: Exception) {
                IntegrityMod.LOGGER.warn("Cannot scan mod {} for references to Integrity", id, error)
            }
            if (found.size >= RuntimeBounds.MAX_REFERENCES) break
        }
        return found
    }

    private fun scanReferences(container: ModContainer, found: MutableList<ModReference>) {
        for (root in container.rootPaths) {
            Files.walk(root).use { paths ->
                for (file in paths) {
                    if (found.size >= RuntimeBounds.MAX_REFERENCES) return
                    if (!file.toString().endsWith(".class") || !Files.isRegularFile(file)) continue
                    val bytes = Files.readAllBytes(file)
                    if (packagePatterns.any { contains(bytes, it) }) found += ModReference(container.metadata.id.take(RuntimeBounds.MAX_NAME), root.relativize(file).toString().replace('\\', '/').take(RuntimeBounds.MAX_STRING))
                }
            }
        }
    }

    private fun contains(bytes: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || bytes.size < pattern.size) return false
        val first = pattern[0]
        var index = 0
        val last = bytes.size - pattern.size
        while (index <= last) {
            if (bytes[index] == first) {
                var offset = 1
                while (offset < pattern.size && bytes[index + offset] == pattern[offset]) offset++
                if (offset == pattern.size) return true
            }
            index++
        }
        return false
    }

    private fun canonical(path: Path): Path = try { path.toRealPath() } catch (error: Exception) { path.toAbsolutePath().normalize() }

    /** Le chemin relatif au dossier de jeu, avec des `/`, quand il y est, absolu sinon : le serveur voit d'où vient ce qui est chargé. */
    private fun relativize(root: Path, path: Path): String {
        val absolute = canonical(path)
        return (if (absolute.startsWith(root)) root.relativize(absolute).toString() else absolute.toString()).replace('\\', '/').take(RuntimeBounds.MAX_STRING)
    }

    private data class Stamp(val size: Long, val modifiedMillis: Long)
    private class Neighbours(val mixinConfigs: List<MixinConfigReport>, val references: List<ModReference>)
}
