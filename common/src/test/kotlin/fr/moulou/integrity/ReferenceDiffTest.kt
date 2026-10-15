package fr.moulou.integrity

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryEntry
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReferenceDiffTest {
    private val identity = PackIdentity("mdtk", "1", "26.3")
    private val mod = InventoryEntry(Category.MODS, "mods/approved.jar", "a".repeat(64), 4, modId = "approved", modVersion = "1")
    private val pack = InventoryEntry(Category.RESOURCEPACKS, "resourcepacks/approved.zip", "b".repeat(64), 4)
    private val before = InventoryReport(identity, listOf(mod, pack))

    @Test
    fun `deux inventaires identiques ne different pas`() {
        val diff = IntegrityService.diff(before, before)
        assertTrue(diff.unchanged)
        assertEquals("Aucune différence.", diff.describe())
    }

    @Test
    fun `les ajouts modifications renommages et versions sont distingues`() {
        val updated = mod.copy(path = "mods/approved-2.jar", sha256 = "c".repeat(64), modVersion = "2")
        val extra = InventoryEntry(Category.SHADERPACKS, "shaderpacks/new.zip", "d".repeat(64), 4)
        val after = InventoryReport(identity.copy(version = "2"), listOf(updated, pack.copy(path = "resourcepacks/renamed.zip"), extra))
        val diff = IntegrityService.diff(before, after)
        assertEquals(listOf(extra), diff.added)
        assertTrue(diff.missing.isEmpty())
        assertEquals(listOf(mod to updated), diff.modified)
        assertEquals(listOf(pack to pack.copy(path = "resourcepacks/renamed.zip")), diff.renamed)
        assertEquals(listOf("version du modpack : 1 -> 2"), diff.identityChanges)
        val text = diff.describe()
        assertTrue(text.startsWith("1 ajout(s), 0 retrait(s), 1 modification(s), 1 renommage(s)."), text)
        assertTrue(text.contains("~ mods/approved-2.jar (1 -> 2)"), text)
        assertTrue(text.contains("> resourcepacks/approved.zip -> resourcepacks/renamed.zip"), text)
    }

    @Test
    fun `un retrait est signale et le detail se borne`() {
        val diff = IntegrityService.diff(before, InventoryReport(identity, listOf(pack)))
        assertEquals(listOf(mod), diff.missing)
        assertEquals(listOf("0 ajout(s), 1 retrait(s), 0 modification(s), 0 renommage(s).", "... 1 ligne(s) de plus."), diff.describe(maxLines = 0).lines())
    }
}
