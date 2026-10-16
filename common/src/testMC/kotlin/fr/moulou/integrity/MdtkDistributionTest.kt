package fr.moulou.integrity

import fr.moulou.integrity.config.CategoryPolicy
import fr.moulou.integrity.config.DeviationKind
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.ValidationResult
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.UUID

/** Exerce le vrai JAR distribué et le client MDTK préparé, dans Loom ou hors Loom ; les écarts restent dans une copie temporaire. */
class MdtkDistributionTest {
    companion object {
        @JvmStatic @BeforeAll
        fun bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() }
    }

    @TempDir lateinit var directory: Path
    private val client = Path.of(System.getProperty("integrity.mdtk.client"))
    private val approved = Path.of(System.getProperty("integrity.mdtk.reference"))
    private val fixtures = Path.of(System.getProperty("integrity.mdtk.fixtures"))
    private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `le client MDTK prepare est conforme au vrai jar distribue`() {
        assertEquals(Path.of(System.getProperty("integrity.distributionJar")).toRealPath(), Path.of(Obfuscated.type("fr.moulou.integrity.api.IntegrityService").protectionDomain.codeSource.location.toURI()).toRealPath())
        val development = System.getProperty("integrity.mdtk.development", "false").toBoolean()
        if (development) {
            // Loom charge Integrity depuis les classes compilées ; un second JAR dans mods provoquerait un doublon.
            assertFalse(Files.exists(client.resolve("mods/integrity-dev-latest.jar")))
            assertFalse(Files.exists(approved.resolve("mods/integrity-dev-latest.jar")))
        } else {
            val expectedJar = Files.readAllBytes(Path.of(System.getProperty("integrity.distributionJar")))
            assertArrayEquals(expectedJar, Files.readAllBytes(client.resolve("mods/integrity-dev-latest.jar")))
            assertArrayEquals(expectedJar, Files.readAllBytes(approved.resolve("mods/integrity-dev-latest.jar")))
        }
        val reference = scan(approved)
        val started = System.nanoTime()
        val report = scan(client)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("mdtk", report.pack.id)
        assertEquals(System.getProperty("integrity.expectedMinecraft"), report.pack.minecraftVersion)
        assertEquals(SharedConstants.getCurrentVersion().id(), report.pack.minecraftVersion)
        if (development) assertTrue(report.entries.none { it.modId in setOf("integrity", "fabric-api", "fabric-language-kotlin") })
        Category.entries.forEach { assertTrue(report.entries.any { entry -> entry.category == it }, "MDTK category missing: $it") }
        assertTrue(compare(reference, report).accepted, compare(reference, report).toString())
        println("MDTK ${report.pack.version} + ${report.pack.minecraftVersion} : ${report.entries.size} éléments, scan disque de ${elapsedMs} ms ; connexion graphique encore à vérifier.")
    }

    @Test
    fun `MDTK refuse les ajouts retraits et modifications et respecte une exception de shaderpacks`() {
        val reference = scan(approved)
        val sandbox = directory.resolve("minecraft")
        Files.createDirectories(sandbox)
        Files.copy(client.resolve("integrity-modpack.json"), sandbox.resolve("integrity-modpack.json"))
        Category.entries.forEach { category ->
            val source = client.resolve(category.directory)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = sandbox.resolve(category.directory).resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
        }
        assertTrue(compare(reference, scan(sandbox)).accepted)

        fixtures("mods", ".jar").forEach { modFixture ->
            val extraMod = sandbox.resolve("mods").resolve(modFixture.fileName)
            Files.copy(modFixture, extraMod)
            assertDeviation(compare(reference, scan(sandbox)), Category.MODS, DeviationKind.ADDED)
            Files.move(extraMod, directory.resolve(modFixture.fileName))
        }

        val removedEntry = reference.entries.first { it.category == Category.MODS && it.modId !in setOf("integrity", "fabric-api", "fabric-language-kotlin") }
        val removed = sandbox.resolve(removedEntry.path)
        val held = directory.resolve("removed-mod.jar")
        Files.move(removed, held)
        assertDeviation(compare(reference, scan(sandbox)), Category.MODS, DeviationKind.MISSING)
        Files.move(held, removed)

        val resource = sandbox.resolve(reference.entries.first { it.category == Category.RESOURCEPACKS }.path)
        fixtures("resourcepacks", ".zip").forEach { resourceFixture ->
            val extraResource = sandbox.resolve("resourcepacks").resolve(resourceFixture.fileName)
            Files.copy(resourceFixture, extraResource)
            assertDeviation(compare(reference, scan(sandbox)), Category.RESOURCEPACKS, DeviationKind.ADDED)
            Files.move(extraResource, directory.resolve(resourceFixture.fileName))
        }

        val original = Files.readAllBytes(resource)
        try {
            Files.write(resource, byteArrayOf(0), APPEND)
            assertDeviation(compare(reference, scan(sandbox)), Category.RESOURCEPACKS, DeviationKind.MODIFIED)
        } finally { Files.write(resource, original) }

        val shader = sandbox.resolve(reference.entries.first { it.category == Category.SHADERPACKS }.path)
        Files.copy(shader, sandbox.resolve("shaderpacks/integrity-test-extra.zip"))
        val report = scan(sandbox)
        assertDeviation(compare(reference, report), Category.SHADERPACKS, DeviationKind.ADDED)
        val exception = ServerConfig(policies = Category.entries.associateWith { CategoryPolicy(ignore = it == Category.SHADERPACKS) })
        assertTrue(Obfuscated.compare(reference, report.withRuntime(), exception, player).accepted)
        assertFalse(compare(reference, report.copy(pack = report.pack.copy(version = "0.0.0"))).accepted)
        println("MDTK : mod ajouté, mod retiré, resourcepack ajouté ou modifié, shaderpack ajouté et exception vérifiés dans une copie temporaire.")
    }

    private fun scan(path: Path): InventoryReport = Obfuscated.scan(path, IntegrityStores.pack(path, createIfMissing = false))

    private fun fixtures(category: String, extension: String): List<Path> = Files.list(fixtures.resolve(category)).use { files ->
        files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(extension) }.sorted().toList().also { require(it.isNotEmpty()) { "No $category test fixtures" } }
    }

    private fun compare(reference: InventoryReport, report: InventoryReport): ValidationResult = Obfuscated.compare(reference, report.withRuntime(), ServerConfig(), player)

    private fun assertDeviation(result: ValidationResult, category: Category, kind: DeviationKind) {
        assertFalse(result.accepted)
        assertTrue(result.violations.any { it.category == category && it.kind == kind }, result.toString())
    }
}
