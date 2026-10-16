package fr.moulou.integrity.buildlogic

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import javax.inject.Inject

/** Fige le client de développement approuvé ; aucun lancement ni nouvel essai ne remplace ensuite cette copie. */
@UntrackedTask(because = "the approved development copy and server configuration must survive manual tests")
abstract class PrepareDevReference : DefaultTask() {
    @get:Internal abstract val client: DirectoryProperty
    @get:Internal abstract val server: DirectoryProperty
    @get:Internal abstract val reference: DirectoryProperty
    @get:Internal abstract val minecraftVersion: Property<String>
    @get:Internal abstract val modpackId: Property<String>
    @get:Internal abstract val modpackVersion: Property<String>
    @get:Internal abstract val port: Property<Int>
    @get:Inject abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        val source = client.get().asFile
        val root = server.get().asFile
        val approved = reference.get().asFile
        require(source.isDirectory && root.resolve("server.properties").isFile) { "Prepare the client and server environments before creating the development reference" }
        val identity = source.resolve("integrity-modpack.json")
        if (!identity.exists()) {
            identity.writeText(json(mapOf("id" to modpackId.get(), "version" to modpackVersion.get(), "minecraftVersion" to minecraftVersion.get())), Charsets.UTF_8)
        }
        if (!approved.exists()) {
            fileSystemOperations.copy {
                from(source)
                into(approved)
                include("mods/**", "resourcepacks/**", "shaderpacks/**", "integrity-modpack.json")
            }
        }
        require(approved.resolve("integrity-modpack.json").isFile) { "Approved development copy is incomplete: $approved" }

        val config = root.resolve("config/integrity/server.json5")
        if (!config.exists()) {
            config.parentFile.mkdirs()
            val relative = root.toPath().relativize(approved.parentFile.toPath()).toString().replace('\\', '/')
            val policy = mapOf("ignore" to false, "allowAdded" to false, "allowMissing" to false, "allowModified" to false)
            // Un couple Loom charge Integrity, Fabric API et FLK depuis le classpath : ses clients sont des clients de développement.
            config.writeText(json(mapOf(
                "referenceInstance" to relative, "activeReference" to "", "requireSameMinecraft" to true, "requireSameModpack" to true,
                "enforceSingleplayer" to false, "allowDevelopmentClients" to true, "timeoutSeconds" to 120, "recheckIntervalSeconds" to 300, "recheckJitterSeconds" to 60,
                "maxElements" to 10_000, "maxScanBytes" to 68_719_476_736L, "rejectedLaunchOptions" to ServerDefaults.REJECTED_LAUNCH_OPTIONS,
                "policies" to mapOf("mods" to policy, "resourcepacks" to policy, "shaderpacks" to policy), "rules" to emptyList<String>(),
            )), Charsets.UTF_8)
        }
        val marker = root.resolve(".integrity-dev-prepared")
        if (!marker.exists()) {
            val properties = root.resolve("server.properties")
            val overrides = linkedMapOf("server-ip" to "127.0.0.1", "server-port" to port.get().toString(), "enable-rcon" to "false")
            val lines = properties.readLines(Charsets.UTF_8).map { line ->
                val key = line.substringBefore('=')
                overrides.remove(key)?.let { "$key=$it" } ?: line
            }
            properties.writeText((lines + overrides.map { "${it.key}=${it.value}" }).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            marker.writeText("${minecraftVersion.get()} ${modpackId.get()} ${modpackVersion.get()}\n", Charsets.UTF_8)
        }
        logger.lifecycle("[Integrity dev] copie approuvée : $approved ; serveur local : 127.0.0.1:${port.get()}")
        logger.lifecycle("[Integrity dev] générer puis activer le manifeste en console ; une copie approuvée présente reste intacte")
    }

    private fun json(value: Any): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"
}
