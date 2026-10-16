package fr.moulou.integrity

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.CategoryPolicy
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.GameJar
import fr.moulou.integrity.model.InventoryEntry
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.LoadedMod
import fr.moulou.integrity.model.MixinConfigReport
import fr.moulou.integrity.model.ModReference
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.PreLaunchSnapshot
import fr.moulou.integrity.model.RuntimeBounds
import fr.moulou.integrity.model.RuntimeReport
import fr.moulou.integrity.model.SelectedPack
import fr.moulou.integrity.model.ValidationResult
import fr.moulou.integrity.protocol.JarHmac
import fr.moulou.integrity.rules.ServerExpectations
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class RuntimeRulesTest {
    private val player = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val identity = PackIdentity("mdtk", "1", "26.3")
    private val mod = InventoryEntry(Category.MODS, "mods/approved.jar", "a".repeat(64), 4, modId = "approved", modVersion = "1")
    private val pack = InventoryEntry(Category.RESOURCEPACKS, "resourcepacks/approved.zip", "b".repeat(64), 4)
    private val reference = InventoryReport(identity, listOf(mod, pack))
    private val runtime = TestRuntime.runtime(reference)

    @Test
    fun `un releve conforme passe et un releve absent est refuse`() {
        assertTrue(verdict(runtime).accepted, verdict(runtime).toString())
        assertProblem(verdict(null), "relevé d'exécution")
    }

    @Test
    fun `fabric addMods est refuse meme pour un client de developpement`() {
        val tampered = runtime.copy(fabricProperties = mapOf("fabric.addMods" to "C:/cheats/xray.jar"))
        assertProblem(verdict(tampered), "fabric.addMods")
        assertProblem(verdict(tampered, ServerConfig(allowDevelopmentClients = true)), "fabric.addMods")
        assertProblem(verdict(runtime.copy(jvmArguments = listOf("-Dfabric.addMods=C:/cheats/xray.jar"))), "fabric.addMods")
        assertProblem(verdict(runtime.copy(fabricProperties = mapOf("fabric.debug.disableModIds" to "integrity"))), "fabric.debug.disableModIds")
        assertProblem(verdict(runtime.copy(fabricProperties = mapOf("fabric.modsFolder" to "C:/elsewhere"))), "fabric.modsFolder")
    }

    @Test
    fun `un client de developpement est refuse sauf reglage`() {
        val loom = runtime.copy(
            developmentEnvironment = true,
            fabricProperties = mapOf("fabric.development" to "true", "fabric.classPathGroups" to "a;b", "fabric.gameJarPath" to "C:/loom/common.jar", "fabric.log.disableAnsi" to "false"),
            gameJars = listOf(GameJar("C:/loom/client.jar", "c".repeat(40), 1), GameJar("C:/loom/common.jar", "d".repeat(40), 1)),
            mods = runtime.mods + LoadedMod("fabric-api", "0.163.0+26.3", OriginKind.PATH, listOf("C:/gradle/fabric-api.jar")) + LoadedMod("fabric-resource-loader-v0", "4.0.0", OriginKind.NESTED, parent = "fabric-api"),
        ).let { it.copy(preLaunch = PreLaunchSnapshot(it.mods)) }
        val refused = verdict(loom)
        assertProblem(refused, "fabric.development")
        assertProblem(refused, "Jar du jeu inattendu")
        assertProblem(refused, "hors du dossier mods")
        val tolerated = verdict(loom, ServerConfig(allowDevelopmentClients = true))
        assertTrue(tolerated.accepted, tolerated.toString())
    }

    @Test
    fun `un agent Java est refuse par defaut et accepte quand la liste le permet`() {
        val agent = runtime.copy(jvmArguments = listOf("-Xmx4G", "-javaagent:C:/authlib-injector.jar"))
        assertProblem(verdict(agent), "-javaagent")
        assertTrue(verdict(agent, ServerConfig(rejectedLaunchOptions = listOf("-agentlib:"))).accepted)
        assertProblem(verdict(runtime.copy(jvmArguments = listOf("-agentlib:jdwp=transport=dt_socket"))), "-agentlib:jdwp")
    }

    @Test
    fun `un jar du jeu inattendu ou absent est refuse et un serveur sans attente refuse tout`() {
        assertProblem(verdict(runtime.copy(gameJars = listOf(GameJar("versions/26.3.jar", "f".repeat(40), 1)))), "Jar du jeu inattendu")
        assertProblem(verdict(runtime.copy(gameJars = emptyList())), "Jar du jeu inattendu")
        assertProblem(verdict(runtime, expectations = ServerExpectations.NONE), "empreinte attendue")
        assertProblem(verdict(runtime, expectations = ServerExpectations.NONE), "propre version")
    }

    @Test
    fun `un mod charge hors de mods et un mod present non charge sont refuses`() {
        val outside = runtime.mods + LoadedMod("xray", "3.0.1", OriginKind.PATH, listOf("C:/cheats/xray.jar"))
        assertProblem(verdict(runtime.copy(mods = outside, preLaunch = PreLaunchSnapshot(outside))), "hors du dossier mods : xray")
        val missing = runtime.mods.filter { it.id != "approved" }
        assertProblem(verdict(runtime.copy(mods = missing, preLaunch = PreLaunchSnapshot(missing))), "présent mais non chargé : mods/approved.jar")
        val other = runtime.mods.map { if (it.id == "approved") it.copy(version = "2") else it }
        assertProblem(verdict(runtime.copy(mods = other, preLaunch = PreLaunchSnapshot(other))), "différent du fichier inventorié")
        val orphan = runtime.mods + LoadedMod("nested", "1", OriginKind.NESTED, parent = "absent")
        assertProblem(verdict(runtime.copy(mods = orphan, preLaunch = PreLaunchSnapshot(orphan))), "imbriqué sans parent")
    }

    @Test
    fun `une liste de mods qui change depuis le lancement est refusee`() {
        val vanished = LoadedMod("vanished", "1", OriginKind.PATH, listOf("mods/vanished.jar"))
        assertProblem(verdict(runtime.copy(preLaunch = PreLaunchSnapshot(runtime.mods + vanished))), "changé depuis le lancement")
        assertProblem(verdict(runtime.copy(preLaunch = null)), "instantané")
    }

    @Test
    fun `un resourcepack selectionne hors de l inventaire ou un dossier deplace est refuse sauf categorie ignoree`() {
        val elsewhere = runtime.copy(selectedPacks = runtime.selectedPacks + SelectedPack("file/xray.zip", "DEFAULT"))
        assertProblem(verdict(elsewhere), "file/xray.zip")
        assertProblem(verdict(runtime.copy(resourcePackDirectory = "C:/elsewhere/packs")), "déplacé")
        val ignored = ServerConfig(policies = ServerConfig().policies + (Category.RESOURCEPACKS to CategoryPolicy(ignore = true)))
        val withoutPacks = reference.copy(entries = listOf(mod))
        val moved = elsewhere.copy(resourcePackDirectory = "C:/elsewhere/packs")
        assertTrue(IntegrityService.compare(withoutPacks, withoutPacks.copy(runtime = moved), ignored, player, TestRuntime.expectations).accepted)
    }

    @Test
    fun `une version d Integrity ou un protocole differents sont refuses`() {
        assertProblem(verdict(runtime.copy(integrityVersion = "1.0.1+26.3")), "Integrity ${TestRuntime.INTEGRITY_VERSION} est requis")
        assertProblem(verdict(runtime.copy(protocolVersion = 1)), "Protocole Integrity 1")
        assertProblem(verdict(runtime.copy(minecraftVersion = "26.2")), "le client exécute 26.2")
    }

    @Test
    fun `un releve hors bornes est invalide`() {
        assertProblem(verdict(runtime.copy(jvmArguments = List(RuntimeBounds.MAX_ARGUMENTS + 1) { "-Dx=$it" })), "relevé d'exécution est invalide")
        assertProblem(verdict(runtime.copy(gameJars = listOf(GameJar("x", "not a hash", 1)))), "relevé d'exécution est invalide")
        assertProblem(verdict(runtime.copy(fabricProperties = mapOf("other.key" to "x"))), "relevé d'exécution est invalide")
        assertProblem(verdict(runtime.copy(mods = runtime.mods + LoadedMod("bad", "1", OriginKind.UNKNOWN, paths = listOf("x")))), "relevé d'exécution est invalide")
    }

    @Test
    fun `un mixin ou une reference visant Integrity sont refuses meme en developpement`() {
        val mixin = runtime.copy(mixinConfigs = listOf(MixinConfigReport("evil", "evil.mixins.json", listOf("net.minecraft.client.Minecraft", "fr.moulou.integrity.api.IntegrityService"))))
        assertProblem(verdict(mixin), "Mixin visant Integrity : evil (evil.mixins.json, fr.moulou.integrity.api.IntegrityService)")
        assertProblem(verdict(mixin, ServerConfig(allowDevelopmentClients = true)), "Mixin visant Integrity")
        assertTrue(verdict(runtime.copy(mixinConfigs = listOf(MixinConfigReport("fabric-api", "fabric.mixins.json", listOf("net.minecraft.client.Minecraft"))))).accepted)
        val reference = runtime.copy(references = listOf(ModReference("evil", "evil/Hook.class")))
        assertProblem(verdict(reference), "référence à Integrity : evil (evil/Hook.class)")
        assertProblem(verdict(reference, ServerConfig(allowDevelopmentClients = true)), "référence à Integrity")
    }

    @Test
    fun `le jar Integrity du client est authentifie avec le nonce du controle`(@TempDir directory: Path) {
        val jar = directory.resolve("integrity.jar")
        Files.write(jar, ByteArray(4096) { it.toByte() })
        val nonce = ByteArray(32) { 7 }
        val expectations = TestRuntime.expectations.copy(integrityJar = jar)
        val authenticated = runtime.copy(integrityJarHmac = JarHmac.of(jar, nonce))
        assertTrue(verdict(authenticated, expectations = expectations, nonce = nonce).accepted)
        assertProblem(verdict(runtime, expectations = expectations, nonce = nonce), "n'a pas authentifié son JAR")
        assertProblem(verdict(authenticated, expectations = expectations, nonce = ByteArray(32) { 8 }), "n'est pas celui du serveur")
        assertProblem(verdict(authenticated, expectations = TestRuntime.expectations, nonce = nonce), "ne peut pas authentifier son JAR")
        assertTrue(verdict(runtime, ServerConfig(allowDevelopmentClients = true), TestRuntime.expectations, nonce).accepted)
        assertTrue(verdict(runtime, expectations = expectations).accepted, "sans nonce, hors d'un contrôle, la règle ne s'applique pas")
        assertProblem(verdict(runtime.copy(integrityJarHmac = "pas une empreinte")), "relevé d'exécution est invalide")
    }

    private fun verdict(runtime: RuntimeReport?, config: ServerConfig = ServerConfig(), expectations: ServerExpectations = TestRuntime.expectations, nonce: ByteArray? = null): ValidationResult =
        IntegrityService.compare(reference, reference.copy(runtime = runtime), config, player, expectations, nonce)

    private fun assertProblem(result: ValidationResult, fragment: String) {
        assertFalse(result.accepted)
        assertTrue(result.problems.any { it.contains(fragment) }, "Problème attendu contenant « $fragment », reçu : ${result.problems}")
    }
}
