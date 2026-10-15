package fr.moulou.integrity.config

import fr.moulou.integrity.model.PackIdentity
import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object IntegrityStores {
    /** L'identifiant de `defaults/modpack.json`, celui d'une identité que l'auteur du modpack n'a pas remplacée. */
    const val DEFAULT_PACK_ID = "mon-modpack"

    val json: Json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }

    fun server(path: Path): BaseStore<ServerConfig> = StoreFactory.createFromConstructor(path.toString(), Json5Format(), options(), ConfigValidation)

    fun pack(gameDirectory: Path, createIfMissing: Boolean): PackIdentity {
        val path = gameDirectory.resolve("integrity-modpack.json")
        require(createIfMissing || Files.isRegularFile(path)) { "Missing integrity-modpack.json in $gameDirectory" }
        return if (createIfMissing) {
            StoreFactory.createFromResource<PackIdentity>(path.toString(), "defaults/modpack.json", JsonFormat(json), options(readOnly = true), PackIdentityValidator).use { it.data }
        } else {
            StoreFactory.createFromConstructor<PackIdentity>(path.toString(), JsonFormat(json), options(readOnly = true, createIfMissing = false), PackIdentityValidator).use { it.data }
        }
    }

    private fun options(readOnly: Boolean = false, createIfMissing: Boolean = true): StoreConfig = StoreConfig(
        withAutoSave = false, withShutdownHook = false, withValidation = true, readOnly = readOnly, createIfMissing = createIfMissing, loggerName = "integrity",
    )
}
