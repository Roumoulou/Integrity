package fr.moulou.integrity

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.*
import fr.moulou.integrity.network.ChallengePayload
import fr.moulou.integrity.network.ChunkPayload
import fr.moulou.integrity.protocol.SecureExchange
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

class DistributionTest {
    companion object {
        @JvmStatic @BeforeAll
        fun bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
    }

    @TempDir lateinit var directory: Path

    @Test
    fun `le code teste vient du jar distribue et le jeu correspond au module`() {
        val expected = Path.of(System.getProperty("integrity.distributionJar")).toRealPath()
        val loaded = Path.of(IntegrityService::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
        assertEquals(expected, loaded)
        assertEquals(System.getProperty("integrity.expectedMinecraft"), SharedConstants.getCurrentVersion().id())
        ZipFile(expected.toFile()).use { jar ->
            val metadata = jar.getInputStream(jar.getEntry("fabric.mod.json")).use { it.readAllBytes().toString(Charsets.UTF_8) }
            assertFalse(metadata.contains("\u0024{"))
            assertTrue(metadata.contains(System.getProperty("integrity.expectedMinecraft")))
            assertEquals(3, jar.entries().asSequence().count { it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") })
            assertNull(jar.getEntry("fr/moulou/integrity/rules/RulesEngine.class"))
            assertNotNull(jar.getEntry("META-INF/services/fr.moulou.integrity.client.ClientBridge"), "Le pont client doit être déclaré dans le JAR")
            assertNotNull(jar.getEntry("fr/moulou/integrity/bridge/GameClientBridge.class"), "Le pont client doit garder son nom")
        }
    }

    @Test
    fun `les codecs Minecraft transportent une reponse chiffree intacte`() {
        val server = SecureExchange.server()
        val challengeBuffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ChallengePayload.CODEC.encode(challengeBuffer, ChallengePayload(server.challenge))
            val decoded = ChallengePayload.CODEC.decode(challengeBuffer).challenge
            val report = InventoryReport(PackIdentity("mdtk", "1", SharedConstants.getCurrentVersion().id()))
            val encrypted = SecureExchange.seal(decoded, report)
            val payload = ChunkPayload(UploadChunk(decoded.id, 0, 1, encrypted))
            val responseBuffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                ChunkPayload.CODEC.encode(responseBuffer, payload)
                assertEquals(report, server.open(ChunkPayload.CODEC.decode(responseBuffer).chunk.data))
            } finally { responseBuffer.release() }
        } finally { challengeBuffer.release() }
    }

    @Test
    fun `le jar obfusque calcule les empreintes et applique les regles`() {
        Files.createDirectories(directory.resolve("resourcepacks"))
        Files.writeString(directory.resolve("resourcepacks/pack.zip"), "approved")
        val identity = PackIdentity("mdtk", "1", SharedConstants.getCurrentVersion().id())
        val reference = IntegrityService.scan(directory, identity)
        assertTrue(IntegrityService.compare(reference, reference, ServerConfig(), UUID.randomUUID()).accepted)
        Files.writeString(directory.resolve("resourcepacks/pack.zip"), "changed")
        val modified = IntegrityService.scan(directory, identity)
        assertFalse(IntegrityService.compare(reference, modified, ServerConfig(), UUID.randomUUID()).accepted)
    }

    @Test
    fun `la reflexion Storify et les serializers fonctionnent dans le jar obfusque`() {
        IntegrityStores.server(directory.resolve("server.json5")).use { store -> assertTrue(store.data.requireSameMinecraft); assertTrue(store.data.requireSameModpack) }
        val identity = IntegrityStores.pack(directory, createIfMissing = true)
        assertEquals(SharedConstants.getCurrentVersion().id(), identity.minecraftVersion)
    }
}
