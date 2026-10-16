package fr.moulou.integrity

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.*
import fr.moulou.integrity.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RulesEngineTest {
    private val playerA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val playerB = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val identity = PackIdentity("mdtk", "1", "26.3")
    private val mod = InventoryEntry(Category.MODS, "mods/approved.jar", "a".repeat(64), 4, modId = "approved", modVersion = "1")
    private val pack = InventoryEntry(Category.RESOURCEPACKS, "resourcepacks/approved.zip", "b".repeat(64), 4)
    private val reference = InventoryReport(identity, listOf(mod, pack))

    @Test
    fun `une installation conforme est acceptee mais une version differente est refusee`() {
        assertTrue(compare(reference).accepted)
        assertFalse(compare(reference.copy(pack = identity.copy(version = "2"))).accepted)
        assertFalse(compare(reference.copy(pack = identity.copy(minecraftVersion = "26.2"))).accepted)
        assertFalse(compare(reference.copy(pack = identity.copy(id = "autre"))).accepted)
    }

    @Test
    fun `ajout retrait et modification de mod sont refuses par defaut`() {
        val extra = mod.copy(path = "mods/extra.jar", modId = "extra", sha256 = "c".repeat(64))
        assertEquals(DeviationKind.ADDED, compare(reference.copy(entries = reference.entries + extra)).violations.single().kind)
        assertEquals(DeviationKind.MISSING, compare(reference.copy(entries = listOf(pack))).violations.single().kind)
        assertEquals(DeviationKind.MODIFIED, compare(reference.copy(entries = listOf(mod.copy(sha256 = "d".repeat(64)), pack))).violations.single().kind)
    }

    @Test
    fun `un pack supplementaire meme identique est refuse`() {
        assertFalse(compare(reference.copy(entries = reference.entries + pack.copy(path = "resourcepacks/inactif.zip"))).accepted)
    }

    @Test
    fun `ignorer les shaders ne permet pas ajouter un mod`() {
        val config = ServerConfig(policies = ServerConfig().policies + (Category.SHADERPACKS to CategoryPolicy(ignore = true)))
        val shader = InventoryEntry(Category.SHADERPACKS, "shaderpacks/personnel.zip", "c".repeat(64), 4)
        assertTrue(compare(reference.copy(entries = reference.entries + shader), config).accepted)
        assertFalse(compare(reference.copy(entries = reference.entries + mod.copy(path = "mods/extra.jar", modId = "extra", sha256 = "d".repeat(64))), config).accepted)
    }

    @Test
    fun `une exception par UUID ne contamine jamais les autres joueurs`() {
        val config = ServerConfig(rules = listOf(ComplianceRule(Category.RESOURCEPACKS, path = "resourcepacks/personnel.zip", playerUuid = playerA.toString())))
        val client = reference.copy(entries = reference.entries + pack.copy(path = "resourcepacks/personnel.zip", sha256 = "c".repeat(64)))
        assertTrue(compare(client, config, playerA).accepted)
        assertFalse(compare(client, config, playerB).accepted)
        assertTrue(compare(client, config, playerA).accepted)
        assertFalse(config.policies.getValue(Category.RESOURCEPACKS).allowAdded)
    }

    @Test
    fun `une regle precise prime sur une autorisation generale`() {
        val config = ServerConfig(rules = listOf(ComplianceRule(Category.RESOURCEPACKS, path = "resourcepacks/**"), ComplianceRule(Category.RESOURCEPACKS, path = "resourcepacks/xray.zip", action = RuleAction.DENY)))
        val extra = pack.copy(path = "resourcepacks/xray.zip", sha256 = "c".repeat(64))
        assertFalse(compare(reference.copy(entries = reference.entries + extra), config).accepted)
        assertTrue(compare(reference.copy(entries = reference.entries + extra.copy(path = "resourcepacks/personnel.zip")), config).accepted)
    }

    @Test
    fun `des regles contradictoires ne sont pas appliquees silencieusement`() {
        val allow = ComplianceRule(Category.MODS, modId = "approved", deviations = setOf(DeviationKind.MODIFIED))
        val config = ServerConfig(rules = listOf(allow, allow.copy(action = RuleAction.DENY)))
        assertFalse(compare(reference, config).accepted)
        assertTrue(ConfigValidation.problems(config).isNotEmpty())
    }

    @Test
    fun `un renommage conforme passe sans transferer une exemption de chemin a du contenu modifie`() {
        assertTrue(compare(reference.copy(entries = listOf(mod.copy(path = "mods/renamed.jar"), pack))).accepted)
        val ignored = ServerConfig(rules = listOf(ComplianceRule(Category.MODS, path = "mods/approved.jar", action = RuleAction.IGNORE)))
        assertTrue(compare(reference.copy(entries = listOf(mod.copy(sha256 = "c".repeat(64)), pack)), ignored).accepted)
        assertFalse(compare(reference.copy(entries = listOf(mod.copy(path = "mods/renamed.jar", sha256 = "c".repeat(64)), pack)), ignored).accepted)
    }

    @Test
    fun `un renommage ne contourne pas une interdiction explicite du nouveau chemin`() {
        val config = ServerConfig(rules = listOf(ComplianceRule(Category.MODS, path = "mods/interdit.jar", action = RuleAction.DENY)))
        assertFalse(compare(reference.copy(entries = listOf(mod.copy(path = "mods/interdit.jar"), pack)), config).accepted)
    }

    @Test
    fun `une erreur client des doublons et des chemins invalides empechent la validation`() {
        assertFalse(compare(reference.copy(error = "scan failed")).accepted)
        assertFalse(compare(reference.copy(entries = reference.entries + mod)).accepted)
        assertFalse(compare(reference.copy(entries = listOf(mod.copy(path = "mods/../outside.jar"), pack))).accepted)
        assertFalse(compare(reference.copy(entries = listOf(mod, mod.copy(path = "mods/second.jar")))).accepted)
    }

    private fun compare(client: InventoryReport, config: ServerConfig = ServerConfig(), player: UUID = playerA) = IntegrityService.compare(reference, client.withRuntime(), config, player, TestRuntime.expectations)
}
