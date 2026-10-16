package fr.moulou.integrity.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Category(val directory: String) {
    @SerialName("mods") MODS("mods"),
    @SerialName("resourcepacks") RESOURCEPACKS("resourcepacks"),
    @SerialName("shaderpacks") SHADERPACKS("shaderpacks"),
}

@Serializable
enum class ElementKind { FILE, DIRECTORY }

@Serializable
data class PackIdentity(val id: String = "", val version: String = "", val minecraftVersion: String = "")

@Serializable
data class InventoryEntry(
    val category: Category,
    val path: String,
    val sha256: String,
    val size: Long,
    val kind: ElementKind = ElementKind.FILE,
    val modId: String? = null,
    val modVersion: String? = null,
)

/** L'inventaire d'un dossier de jeu ; `runtime` n'existe que dans le rapport d'un client, jamais dans un manifeste, et ne s'écrit pas quand il manque. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InventoryReport(
    val pack: PackIdentity,
    val entries: List<InventoryEntry> = emptyList(),
    val error: String? = null,
    val schemaVersion: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val runtime: RuntimeReport? = null,
)

@Serializable
data class ReferenceManifest(val id: String, val generatedAt: String, val report: InventoryReport, val schemaVersion: Int = 1)
