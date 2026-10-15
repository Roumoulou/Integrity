package fr.moulou.integrity.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Copie une publication client Prism dans une instance de test neuve ; une instance présente conserve toutes ses modifications. */
@UntrackedTask(because = "the client is a live instance whose deliberate deviations must survive preparation")
abstract class InstallMdtkClient : DefaultTask() {
    @get:Internal abstract val publication: DirectoryProperty
    @get:Internal abstract val target: DirectoryProperty
    @get:Internal abstract val minecraftVersion: Property<String>
    @get:Internal abstract val modpackVersion: Property<String>
    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun install() {
        val destination = target.get().asFile
        if (destination.exists()) {
            require(File(destination, "mmc-pack.json").isFile && File(destination, "minecraft/mods").isDirectory) { "Existing MDTK test client is incomplete; use a new test directory: $destination" }
            logger.lifecycle("[MDTK] client présent, ses modifications sont conservées : $destination")
            return
        }
        val source = publication.get().asFile
        require(File(source, "minecraft/mods").isDirectory) { "MDTK client publication not found: $source. Set integrity.mdtk.releases_dir in machine.properties." }
        val metadata = JsonSlurper().parse(File(source, "mmc-pack.json"), "UTF-8") as Map<*, *>
        val components = metadata["components"] as List<*>
        val version = components.map { it as Map<*, *> }.single { it["uid"] == "net.minecraft" }["version"]
        if (version != minecraftVersion.get()) throw GradleException("MDTK client uses Minecraft $version, expected ${minecraftVersion.get()}")
        fileSystemOperations.copy { from(source); into(destination) }
        val config = File(destination, "instance.cfg")
        val lines = config.readLines(Charsets.UTF_8).filterNot { it.startsWith("uuid=") || it.startsWith("name=") }
        config.writeText((lines + "name=Integrity - MDTK ${minecraftVersion.get()}" + "uuid=${UUID.randomUUID().toString().replace("-", "")}").joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        File(destination, "minecraft/integrity-modpack.json").writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("id" to "mdtk", "version" to modpackVersion.get(), "minecraftVersion" to minecraftVersion.get()))) + "\n", Charsets.UTF_8,
        )
        logger.lifecycle("[MDTK] publication client copiée dans $destination")
    }
}
