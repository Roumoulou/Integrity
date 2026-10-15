package fr.moulou.integrity.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/** Télécharge les fichiers épinglés du catalogue de tests ; un fichier présent est vérifié, jamais remplacé silencieusement. */
@UntrackedTask(because = "the fixture store persists outside build and is verified against its explicit catalog")
abstract class DownloadMdtkFixtures : DefaultTask() {
    @get:Internal abstract val catalog: RegularFileProperty
    @get:Internal abstract val directory: DirectoryProperty

    @TaskAction
    fun download() {
        val file = catalog.get().asFile
        require(file.isFile) { "Fixture catalog missing: $file. Copy the project's 02-data/00-test/mdtk-fixtures.json before running MDTK tests." }
        val data = JsonSlurper().parse(file, "UTF-8") as Map<*, *>
        require((data["schemaVersion"] as Number).toInt() == 1) { "Unsupported MDTK fixture catalog schema" }
        val entries = data["fixtures"] as List<*>
        val root = directory.get().asFile.toPath().toAbsolutePath().normalize()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
            for (raw in entries) {
                val entry = raw as Map<*, *>
                val minecraft = entry["minecraftVersion"] as String
                val category = entry["category"] as String
                val filename = entry["filename"] as String
                val expected = entry["sha512"] as String
                val size = (entry["sizeBytes"] as Number).toLong()
                val uri = URI(entry["url"] as String)
                require(Regex("[0-9]{2}\\.[0-9]+(?:\\.[0-9]+)?").matches(minecraft)) { "Invalid fixture Minecraft version" }
                require(category in setOf("mods", "resourcepacks", "shaderpacks")) { "Invalid fixture category" }
                require(filename.isNotBlank() && filename.none { it in "\\/:*?\"<>|" || it.isISOControl() } && filename !in setOf(".", "..")) { "Invalid fixture filename" }
                require(Regex("[a-f0-9]{128}").matches(expected) && size in 1..536_870_912) { "Invalid fixture checksum or size" }
                require(uri.scheme == "https" && uri.host == "cdn.modrinth.com" && uri.userInfo == null && uri.port == -1) { "Fixture URL must be an official HTTPS Modrinth CDN URL" }
                val target = root.resolve(minecraft).resolve(category).resolve(filename).normalize()
                require(target.startsWith(root)) { "Fixture path escaped the data directory" }
                if (Files.exists(target)) {
                    require(Files.isRegularFile(target) && Files.size(target) == size && checksum(target) == expected) { "Fixture checksum mismatch: $target. Preserve the file and correct the catalog or choose a new fixture filename." }
                    logger.lifecycle("[MDTK] fichier d'essai vérifié : $target")
                    continue
                }
                Files.createDirectories(target.parent)
                val temporary = target.resolveSibling("$filename.part")
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "Integrity-test-fixtures/1.0 (local development)").GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { input ->
                    if (response.statusCode() != 200) throw GradleException("Fixture download failed with HTTP ${response.statusCode()}: $uri")
                    Files.newOutputStream(temporary).use { output ->
                        var written = 0L
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= size) { "Fixture download exceeds its declared size: $filename" }
                            output.write(buffer, 0, count)
                        }
                        require(written == size) { "Incomplete fixture download: $filename ($written/$size bytes)" }
                    }
                }
                require(checksum(temporary) == expected) { "Downloaded fixture checksum mismatch: $temporary" }
                Files.move(temporary, target)
                logger.lifecycle("[MDTK] fichier d'essai téléchargé et vérifié : $target")
            }
        }
    }

    private fun checksum(path: java.nio.file.Path): String {
        val digest = MessageDigest.getInstance("SHA-512")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
