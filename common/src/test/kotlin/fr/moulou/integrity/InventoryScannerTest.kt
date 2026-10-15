package fr.moulou.integrity

import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.inventory.FileHashCache
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.PackIdentity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InventoryScannerTest {
    @TempDir lateinit var directory: Path
    private val identity = PackIdentity("mdtk", "1", "26.3")

    @Test
    fun `les resourcepacks presents sont controles et les dossiers hors perimetre sont ignores`() {
        write("resourcepacks/inactif.zip", "pack")
        write("shaderpacks/shader.zip", "shader")
        write("logs/latest.log", "log")
        write("datapacks/conserve.zip", "datapack")
        write("config/preferences.json", "preferences")
        val report = IntegrityService.scan(directory, identity)
        assertEquals(setOf("resourcepacks/inactif.zip", "shaderpacks/shader.zip"), report.entries.map { it.path }.toSet())
    }

    @Test
    fun `une categorie exclue ne consomme pas le budget de lecture`() {
        write("resourcepacks/pack.zip", "pack")
        write("shaderpacks/huge.zip", "x".repeat(1000))
        val report = IntegrityService.scan(directory, identity, ServerConfig(maxScanBytes = 4), setOf(Category.RESOURCEPACKS))
        assertEquals(listOf("resourcepacks/pack.zip"), report.entries.map { it.path })
    }

    @Test
    fun `un renommage exterieur conserve empreinte mais un chemin interne la change`() {
        write("resourcepacks/alpha/assets/a.txt", "abc")
        val initial = IntegrityService.scan(directory, identity).entries.single().sha256
        Files.move(directory.resolve("resourcepacks/alpha"), directory.resolve("resourcepacks/beta"))
        assertEquals(initial, IntegrityService.scan(directory, identity).entries.single().sha256)
        Files.move(directory.resolve("resourcepacks/beta/assets/a.txt"), directory.resolve("resourcepacks/beta/assets/b.txt"))
        assertNotEquals(initial, IntegrityService.scan(directory, identity).entries.single().sha256)
    }

    @Test
    fun `les frontieres entre fichiers font partie de empreinte du dossier`() {
        write("resourcepacks/pack/a", "a"); write("resourcepacks/pack/b", "bc")
        val initial = IntegrityService.scan(directory, identity).entries.single().sha256
        write("resourcepacks/pack/a", "ab"); write("resourcepacks/pack/b", "c")
        assertNotEquals(initial, IntegrityService.scan(directory, identity).entries.single().sha256)
    }

    @Test
    fun `le mod expose son identifiant sa version et le hash du jar entier`() {
        val jar = directory.resolve("mods/example.jar")
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json")); zip.write("""{"schemaVersion":1,"id":"example","version":"1.2.3"}""".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/jars/embedded.jar")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
        }
        val entry = IntegrityService.scan(directory, identity).entries.single()
        assertEquals(Category.MODS, entry.category)
        assertEquals("example", entry.modId); assertEquals("1.2.3", entry.modVersion)
        assertEquals(Files.size(jar), entry.size)
    }

    @Test
    fun `un jar illisible et un budget de lecture depasse arretent la verification`() {
        write("mods/broken.jar", "not a jar")
        assertThrows(Exception::class.java) { IntegrityService.scan(directory, identity) }
        Files.delete(directory.resolve("mods/broken.jar"))
        write("resourcepacks/large.zip", "12345")
        assertThrows(IllegalArgumentException::class.java) { IntegrityService.scan(directory, identity, ServerConfig(maxScanBytes = 4)) }
    }

    @Test
    fun `un lien symbolique ne permet pas de lire hors du pack`() {
        val outside = directory.resolve("outside.txt"); Files.writeString(outside, "outside")
        val packs = Files.createDirectories(directory.resolve("resourcepacks"))
        val link = packs.resolve("linked.txt")
        val supported = runCatching { Files.createSymbolicLink(link, outside); true }.getOrDefault(false)
        assumeTrue(supported, "Le poste ne permet pas de créer une fixture de lien symbolique.")
        assertThrows(IllegalArgumentException::class.java) { IntegrityService.scan(directory, identity) }
    }

    @Test
    fun `le cache rend la meme empreinte sans relire et voit un fichier remplace`() {
        write("resourcepacks/pack.zip", "approved")
        write("shaderpacks/folder/shader.fsh", "shader")
        val cache = FileHashCache()
        val first = IntegrityService.scan(directory, identity, cache = cache)
        assertEquals(1, cache.size(), "Seuls les fichiers réguliers entrent au cache")
        assertEquals(first.entries, IntegrityService.scan(directory, identity, cache = cache).entries)
        write("resourcepacks/pack.zip", "approved then replaced")
        val replaced = IntegrityService.scan(directory, identity, cache = cache)
        assertNotEquals(first.entries.first().sha256, replaced.entries.first().sha256)
        assertEquals(Files.size(directory.resolve("resourcepacks/pack.zip")), replaced.entries.first().size)
        assertEquals(2, cache.size())
    }

    @Test
    fun `la progression compte les elements du premier au dernier`() {
        write("resourcepacks/a.zip", "a"); write("resourcepacks/b.zip", "b"); write("shaderpacks/c.zip", "c")
        val steps = ArrayList<Pair<Int, Int>>()
        IntegrityService.scan(directory, identity, progress = { done, total -> steps += done to total })
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), steps)
    }

    private fun write(relative: String, text: String) {
        val file = directory.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, text)
    }
}
