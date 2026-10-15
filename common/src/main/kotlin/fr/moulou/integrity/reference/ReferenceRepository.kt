package fr.moulou.integrity.reference

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.ConfigValidation
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.ReferenceManifest
import fr.moulou.integrity.rules.ReportValidation
import fr.moulou.storify.utils.AtomicFiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Les manifestes JSON d'un dossier, nommés par leur identifiant ; aucun n'est jamais écrasé. */
class ReferenceRepository(private val gameDirectory: Path, subdirectory: String = REFERENCES) {
    companion object {
        /** Les références du serveur, candidates à l'activation. */
        const val REFERENCES = "config/integrity/references"

        /** Les manifestes qu'un client exporte, à copier dans les références d'un serveur. */
        const val EXPORTS = "config/integrity/export"

        private const val MAX_MANIFEST_BYTES = 4_194_304L
    }

    private val directory = gameDirectory.resolve(subdirectory)

    /** Génère le manifeste de la copie client approuvée que désigne `referenceInstance`, sans l'activer. */
    @Synchronized
    fun generate(id: String, config: ServerConfig): ReferenceManifest {
        require(!Files.exists(path(id))) { "Reference already exists; use a new identifier: $id" }
        val configured = Path.of(config.referenceInstance)
        val instance = (if (configured.isAbsolute) configured else gameDirectory.resolve(configured)).normalize()
        val clientGame = instance.resolve(".minecraft")
        require(Files.isDirectory(clientGame)) { "Reference instance must contain .minecraft: $instance" }
        val identity = IntegrityStores.pack(clientGame, createIfMissing = false)
        val report = IntegrityService.scan(clientGame, identity, config)
        return save(ReferenceManifest(id, Instant.now().toString(), report), config.maxElements)
    }

    /** Écrit un manifeste valide sous son identifiant ; un manifeste présent sous ce nom est refusé, jamais remplacé. */
    @Synchronized
    fun save(manifest: ReferenceManifest, maxElements: Int = 10_000): ReferenceManifest {
        val target = path(manifest.id)
        require(!Files.exists(target)) { "Reference already exists; use a new identifier: ${manifest.id}" }
        require(manifest.schemaVersion == 1 && ReportValidation.problems(manifest.report, maxElements).isEmpty()) { "Invalid reference inventory" }
        val encoded = IntegrityStores.json.encodeToString(manifest) + "\n"
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Reference manifest is too large" }
        Files.createDirectories(directory)
        AtomicFiles.write(target) { temporary -> Files.writeString(temporary, encoded, StandardCharsets.UTF_8) }
        return manifest
    }

    fun load(id: String, maxElements: Int): ReferenceManifest {
        val file = path(id)
        require(Files.isRegularFile(file)) { "Reference not found: $id" }
        require(Files.size(file) <= MAX_MANIFEST_BYTES) { "Reference manifest is too large" }
        val manifest = IntegrityStores.json.decodeFromString<ReferenceManifest>(Files.readString(file, StandardCharsets.UTF_8))
        require(manifest.id == id && manifest.schemaVersion == 1 && ReportValidation.problems(manifest.report, maxElements).isEmpty()) { "Invalid reference manifest" }
        return manifest
    }

    fun list(): List<String> = if (!Files.isDirectory(directory)) emptyList() else Files.list(directory).use { files ->
        files.filter { it.fileName.toString().endsWith(".json") }.map { it.fileName.toString().removeSuffix(".json") }.sorted().toList()
    }

    /** Le fichier d'un manifeste, présent ou non. */
    fun file(id: String): Path = path(id)

    private fun path(id: String): Path {
        require(ConfigValidation.validReferenceId(id)) { "Invalid reference identifier: $id" }
        return directory.resolve("$id.json")
    }
}
