package fr.moulou.integrity.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject

/** Prépare la copie approuvée depuis la publication, avec le JAR distribué ; conserve les règles et les références actives déjà créées. */
@UntrackedTask(because = "the server and its approved copy are live directories, never disposable build outputs")
abstract class PrepareMdtkReference : DefaultTask() {
    @get:Internal abstract val publication: DirectoryProperty
    @get:Internal abstract val server: DirectoryProperty
    @get:Internal abstract val reference: DirectoryProperty
    @get:Internal abstract val jar: RegularFileProperty
    @get:Internal abstract val minecraftVersion: Property<String>
    @get:Internal abstract val modpackVersion: Property<String>
    @get:Internal abstract val port: Property<Int>
    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = publication.get().asFile.resolve("minecraft")
        val root = server.get().asFile.resolve("server")
        val approved = reference.get().asFile
        require(source.resolve("mods").isDirectory && root.resolve("mods").isDirectory) { "MDTK publications must be installed before preparing the reference" }
        val identity = sourceIdentity()
        if (!approved.exists()) {
            fileSystemOperations.copy {
                from(source)
                into(approved)
                include("mods/**", "resourcepacks/**", "shaderpacks/**")
            }
            approved.resolve("integrity-modpack.json").writeText(identity, Charsets.UTF_8)
        }
        require(approved.resolve("integrity-modpack.json").isFile && approved.resolve("mods").isDirectory) { "Approved MDTK copy is incomplete: $approved" }
        val deployed = approved.resolve("mods/integrity-dev-latest.jar")
        val changed = deployed.isFile && !MessageDigest.isEqual(sha256(deployed), sha256(jar.get().asFile))
        jar.get().asFile.copyTo(deployed, overwrite = true)

        val config = root.resolve("config/integrity/server.json5")
        if (!config.exists()) {
            config.parentFile.mkdirs()
            val instance = approved.parentFile
            val relative = root.toPath().relativize(instance.toPath()).toString().replace('\\', '/')
            val policy = mapOf("ignore" to false, "allowAdded" to false, "allowMissing" to false, "allowModified" to false)
            // Ces copies tournent avec le JAR distribué dans un launcher : les clients de développement y sont refusés, comme en production.
            config.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "referenceInstance" to relative, "activeReference" to "", "requireSameMinecraft" to true, "requireSameModpack" to true,
                "enforceSingleplayer" to false, "allowDevelopmentClients" to false, "timeoutSeconds" to 120, "recheckIntervalSeconds" to 300, "recheckJitterSeconds" to 60,
                "maxElements" to 10_000, "maxScanBytes" to 68_719_476_736L, "rejectedLaunchOptions" to ServerDefaults.REJECTED_LAUNCH_OPTIONS,
                "policies" to mapOf("mods" to policy, "resourcepacks" to policy, "shaderpacks" to policy), "rules" to emptyList<String>(),
            ))) + "\n", Charsets.UTF_8)
        }

        val marker = root.resolve(".integrity-mdtk-prepared.json")
        if (!marker.exists()) {
            // Le kit peut viser un serveur distant ; cette copie est uniquement un banc local, avec un port par version.
            val properties = root.resolve("server.properties")
            val overrides = linkedMapOf("server-ip" to "127.0.0.1", "server-port" to port.get().toString())
            val lines = properties.readLines(Charsets.UTF_8).map { line ->
                val key = line.substringBefore('=')
                overrides.remove(key)?.let { "$key=$it" } ?: line
            }
            properties.writeText((lines + overrides.map { "${it.key}=${it.value}" }).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            marker.writeText(identity, Charsets.UTF_8)
        }
        if (changed) logger.lifecycle("[MDTK] JAR Integrity mis à jour : générer puis activer une nouvelle référence avant de rejoindre le serveur")
        logger.lifecycle("[MDTK] copie approuvée prête : $approved ; génération et activation du manifeste restent explicites")
    }

    private fun sourceIdentity(): String = JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
        "id" to "mdtk", "version" to modpackVersion.get(), "minecraftVersion" to minecraftVersion.get(),
    ))) + "\n"

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest()
    }
}
