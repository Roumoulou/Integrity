package fr.moulou.integrity

import fr.moulou.integrity.config.*
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.reference.ReferenceRepository
import fr.moulou.storify.validation.ValidationException
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationReferenceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `Storify cree une configuration stricte et conserve la derniere configuration valide apres un mauvais rechargement`() {
        val file = directory.resolve("server.json5")
        IntegrityStores.server(file).use { store ->
            assertTrue(Files.isRegularFile(file))
            val initial = store.data.copy()
            assertFalse(initial.enforceSingleplayer)
            Files.writeString(file, "{ timeoutSeconds: -1 }")
            assertThrows(ValidationException::class.java) { store.reloadFromFile() }
            assertEquals(initial, store.data)
            Files.writeString(file, "{ unknownOption: true }")
            assertThrows(Exception::class.java) { store.reloadFromFile() }
            assertEquals(initial, store.data)
        }
    }

    @Test
    fun `la reference provient du client et reste figee quand la copie change`() {
        val game = directory.resolve("client-pack/.minecraft")
        Files.createDirectories(game.resolve("resourcepacks"))
        Files.writeString(game.resolve("integrity-modpack.json"), IntegrityStores.json.encodeToString(PackIdentity("mdtk", "1", "26.3")))
        Files.writeString(game.resolve("resourcepacks/pack.zip"), "approved")
        val config = ServerConfig(referenceInstance = "client-pack")
        val repository = ReferenceRepository(directory)
        val generated = repository.generate("approved", config)
        Files.writeString(game.resolve("resourcepacks/pack.zip"), "changed")
        assertEquals(generated, repository.load("approved", 10_000))
        assertEquals("", config.activeReference)
        assertThrows(IllegalArgumentException::class.java) { repository.generate("approved", config) }
        assertThrows(IllegalArgumentException::class.java) { repository.load("../outside", 10_000) }
    }

    @Test
    fun `une copie sans minecraft ou sans identite du modpack ne devient pas une reference`() {
        val repository = ReferenceRepository(directory)
        assertThrows(IllegalArgumentException::class.java) { repository.generate("missing", ServerConfig(referenceInstance = "missing")) }
        Files.createDirectories(directory.resolve("empty/.minecraft"))
        assertThrows(IllegalArgumentException::class.java) { repository.generate("empty", ServerConfig(referenceInstance = "empty")) }
        assertTrue(repository.list().isEmpty())
    }
}
