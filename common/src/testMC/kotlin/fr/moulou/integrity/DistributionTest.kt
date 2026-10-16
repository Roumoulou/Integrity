package fr.moulou.integrity

import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.UploadChunk
import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile

/** Exerce le vrai JAR distribué : ce qu'il garde en clair, ce qu'il renomme, et son protocole atteint à travers le fichier de correspondances. */
class DistributionTest {
    companion object {
        @JvmStatic @BeforeAll
        fun bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
    }

    @TempDir lateinit var directory: Path

    @Test
    fun `le code teste vient du jar distribue et le jeu correspond au module`() {
        val expected = Path.of(System.getProperty("integrity.distributionJar")).toRealPath()
        val loaded = Path.of(Obfuscated.type("fr.moulou.integrity.api.IntegrityService").protectionDomain.codeSource.location.toURI()).toRealPath()
        assertEquals(expected, loaded)
        assertEquals(System.getProperty("integrity.expectedMinecraft"), SharedConstants.getCurrentVersion().id())
        ZipFile(expected.toFile()).use { jar ->
            val metadata = jar.getInputStream(jar.getEntry("fabric.mod.json")).use { it.readAllBytes().toString(Charsets.UTF_8) }
            assertFalse(metadata.contains('$'))
            assertTrue(metadata.contains(System.getProperty("integrity.expectedMinecraft")))
            assertTrue(metadata.contains("\"preLaunch\""), "L'entrypoint preLaunch doit être déclaré")
            assertEquals(3, jar.entries().asSequence().count { it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") })
            listOf("fr/moulou/integrity/IntegrityMod.class", "fr/moulou/integrity/IntegrityPreLaunch.class", "fr/moulou/integrity/client/IntegrityClient.class",
                "fr/moulou/integrity/client/ClientBridge.class", "fr/moulou/integrity/bridge/GameClientBridge.class", "META-INF/services/fr.moulou.integrity.client.ClientBridge",
                "fr/moulou/integrity/model/InventoryReport.class", "fr/moulou/integrity/model/RuntimeReport.class", "fr/moulou/integrity/config/ServerConfig.class")
                .forEach { assertNotNull(jar.getEntry(it), "$it doit garder son nom") }
            listOf("fr/moulou/integrity/api/IntegrityService.class", "fr/moulou/integrity/protocol/SecureExchange.class", "fr/moulou/integrity/protocol/ControlWire.class",
                "fr/moulou/integrity/network/ChallengePayload.class", "fr/moulou/integrity/rules/RulesEngine.class", "fr/moulou/integrity/rules/RuntimeRules.class",
                "fr/moulou/integrity/inventory/InventoryScanner.class", "fr/moulou/integrity/server/ServerState.class")
                .forEach { assertNull(jar.getEntry(it), "$it doit être renommé") }
            val game = jar.getInputStream(jar.getEntry("integrity-game.properties")).use { it.readAllBytes().toString(Charsets.UTF_8) }
            assertFalse(game.contains('$'), "Les attentes du serveur doivent être remplies par le build")
        }
        assertEquals(System.getProperty("integrity.expectedMinecraft"), Obfuscated.expectation("minecraftVersion"))
        assertNotNull(Obfuscated.expectation("clientSha1"), "Le SHA-1 du client.jar doit être posé par le build")
        assertNotNull(Obfuscated.expectation("integrityVersion"), "La version d'Integrity doit être posée par le build")
    }

    @Test
    fun `les codecs Minecraft transportent une reponse chiffree intacte`() {
        val server = Obfuscated.server()
        val challengeCodec = Obfuscated.challengeCodec()
        val challengeBuffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            challengeCodec.encode(challengeBuffer, Obfuscated.challengePayload(server.challenge))
            val decoded = Obfuscated.challengeOf(challengeCodec.decode(challengeBuffer))
            val report = InventoryReport(PackIdentity("mdtk", "1", SharedConstants.getCurrentVersion().id()))
            val encrypted = Obfuscated.seal(decoded, report)
            val chunkCodec = Obfuscated.chunkCodec()
            val responseBuffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                chunkCodec.encode(responseBuffer, Obfuscated.chunkPayload(UploadChunk(decoded.id, 0, 1, encrypted)))
                assertEquals(report, server.open(Obfuscated.chunkOf(chunkCodec.decode(responseBuffer)).data))
            } finally { responseBuffer.release() }
        } finally { challengeBuffer.release() }
    }

    @Test
    fun `le jar obfusque calcule les empreintes et applique les regles`() {
        Files.createDirectories(directory.resolve("resourcepacks"))
        Files.writeString(directory.resolve("resourcepacks/pack.zip"), "approved")
        val identity = PackIdentity("mdtk", "1", SharedConstants.getCurrentVersion().id())
        val reference = Obfuscated.scan(directory, identity)
        val verdict = Obfuscated.compare(reference, reference.withRuntime(), ServerConfig(), UUID.randomUUID())
        assertTrue(verdict.accepted, verdict.toString())
        Files.writeString(directory.resolve("resourcepacks/pack.zip"), "changed")
        val modified = Obfuscated.scan(directory, identity)
        assertFalse(Obfuscated.compare(reference, modified.withRuntime(), ServerConfig(), UUID.randomUUID()).accepted)
    }

    @Test
    fun `la reflexion Storify et les serializers fonctionnent dans le jar obfusque`() {
        IntegrityStores.server(directory.resolve("server.json5")).use { store -> assertTrue(store.data.requireSameMinecraft); assertTrue(store.data.requireSameModpack) }
        val identity = IntegrityStores.pack(directory, createIfMissing = true)
        assertEquals(SharedConstants.getCurrentVersion().id(), identity.minecraftVersion)
    }
}
