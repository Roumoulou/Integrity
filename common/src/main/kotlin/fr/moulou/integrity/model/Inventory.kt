package fr.moulou.integrity.model

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

@Serializable
data class InventoryReport(val pack: PackIdentity, val entries: List<InventoryEntry> = emptyList(), val error: String? = null, val schemaVersion: Int = 1)

@Serializable
data class ReferenceManifest(val id: String, val generatedAt: String, val report: InventoryReport, val schemaVersion: Int = 1)
