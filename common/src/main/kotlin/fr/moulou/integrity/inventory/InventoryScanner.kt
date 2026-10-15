package fr.moulou.integrity.inventory

import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.ElementKind
import fr.moulou.integrity.model.InventoryEntry
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** Inventorie les catégories contrôlées d'un dossier de jeu ; un cache facultatif évite de relire les fichiers qui n'ont pas bougé. */
class InventoryScanner(private val maxElements: Int = 10_000, private val maxBytes: Long = 68_719_476_736, private val cache: FileHashCache? = null) {
    private var bytesRead = 0L
    private var filesRead = 0
    private val stamps = LinkedHashMap<Path, BasicFileAttributes>()

    /**
     * Calcule l'inventaire des catégories demandées et refuse tout ce qui change pendant la lecture.
     * `progress` reçoit, depuis le fil du scan, le nombre d'éléments traités et leur total.
     */
    @Synchronized
    fun scan(gameDirectory: Path, identity: PackIdentity, categories: Set<Category> = Category.entries.toSet(), progress: ((done: Int, total: Int) -> Unit)? = null): InventoryReport {
        bytesRead = 0; filesRead = 0; stamps.clear()
        require(Files.isDirectory(gameDirectory)) { "Client game directory is missing" }
        val root = gameDirectory.toRealPath()
        val elements = ArrayList<Pair<Category, Path>>()
        for (category in Category.entries.filter { it in categories }) {
            val directory = root.resolve(category.directory)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) continue
            require(attributes(directory).isDirectory) { "Category is not a directory: ${category.directory}" }
            Files.list(directory).use { children ->
                children.sorted().forEach { element ->
                    require(elements.size < maxElements) { "Inventory element limit exceeded" }
                    elements += category to element
                }
            }
        }
        progress?.invoke(0, elements.size)
        val entries = ArrayList<InventoryEntry>(elements.size)
        elements.forEachIndexed { index, (category, element) ->
            entries += inventory(root, category, element)
            progress?.invoke(index + 1, elements.size)
        }
        stamps.forEach { (path, initial) ->
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(sameFile(initial, current)) {
                "Installation changed while scanning: ${root.relativize(path)} (size ${initial.size()} -> ${current.size()}, modified ${initial.lastModifiedTime()} -> ${current.lastModifiedTime()})"
            }
        }
        return InventoryReport(identity, entries.sortedWith(compareBy({ it.category.ordinal }, { it.path })))
    }

    private fun inventory(root: Path, category: Category, element: Path): InventoryEntry {
        val attrs = attributes(element)
        val path = root.relativize(element).toString().replace('\\', '/')
        if (attrs.isDirectory) {
            val digest = MessageDigest.getInstance("SHA-256")
            val before = bytesRead
            hashDirectory(element, digest)
            return InventoryEntry(category, path, HexFormat.of().formatHex(digest.digest()), bytesRead - before, ElementKind.DIRECTORY)
        }
        // Un fichier déjà lu, de même taille et de même date, garde son empreinte ; le contrôle final des attributs reste appliqué.
        cache?.get(element, attrs)?.let { return InventoryEntry(category, path, it.sha256, attrs.size(), ElementKind.FILE, it.modId, it.modVersion) }
        val digest = MessageDigest.getInstance("SHA-256")
        val before = bytesRead
        hashFile(element, digest)
        val mod = if (category == Category.MODS && element.fileName.toString().endsWith(".jar")) modMetadata(element) else null
        val sha256 = HexFormat.of().formatHex(digest.digest())
        cache?.put(element, attrs, FileHashCache.Value(sha256, mod?.first, mod?.second))
        return InventoryEntry(category, path, sha256, bytesRead - before, ElementKind.FILE, mod?.first, mod?.second)
    }

    private fun attributes(path: Path): BasicFileAttributes {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!attrs.isSymbolicLink && !attrs.isOther && (attrs.isDirectory || attrs.isRegularFile)) { "Unsupported filesystem entry: ${path.fileName}" }
        stamps.putIfAbsent(path, attrs)
        return attrs
    }

    private fun hashDirectory(directory: Path, digest: MessageDigest) {
        digest.update("integrity-directory-v1".toByteArray(StandardCharsets.UTF_8))
        Files.walk(directory).use { paths ->
            paths.filter { it != directory }.sorted(compareBy { directory.relativize(it).toString().replace('\\', '/') }).forEach { path ->
                val attrs = attributes(path)
                val relative = directory.relativize(path).toString().replace('\\', '/').toByteArray(StandardCharsets.UTF_8)
                digest.update(if (attrs.isDirectory) 1.toByte() else 2.toByte())
                digest.update(ByteBuffer.allocate(4).putInt(relative.size).array()); digest.update(relative)
                if (attrs.isRegularFile) {
                    digest.update(ByteBuffer.allocate(8).putLong(attrs.size()).array())
                    hashFile(path, digest)
                }
            }
        }
    }

    private fun hashFile(path: Path, digest: MessageDigest) {
        val before = attributes(path)
        require(before.isRegularFile) { "File expected" }
        require(++filesRead <= 200_000) { "Inventory file limit exceeded" }
        Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Inventory cancelled")
                val count = channel.read(buffer)
                if (count < 0) break
                bytesRead = Math.addExact(bytesRead, count.toLong())
                require(bytesRead <= maxBytes) { "Inventory byte limit exceeded" }
                buffer.flip(); digest.update(buffer); buffer.clear()
            }
        }
        require(sameFile(before, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS))) { "File changed while hashing" }
    }

    private fun modMetadata(path: Path): Pair<String, String>? = ZipFile(path.toFile()).use { zip ->
        val entry = zip.getEntry("fabric.mod.json") ?: return@use null
        val bytes = zip.getInputStream(entry).use { it.readNBytes(1_048_577) }
        require(bytes.size <= 1_048_576) { "Mod metadata is too large" }
        val metadata = Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        val id = metadata["id"]?.jsonPrimitive?.content ?: error("Missing Fabric mod id")
        val version = metadata["version"]?.jsonPrimitive?.content ?: error("Missing Fabric mod version")
        require(Regex("[a-z][a-z0-9_-]{1,63}").matches(id) && version.isNotBlank() && version.length <= 128) { "Invalid Fabric mod metadata" }
        id to version
    }

    // La taille d'un répertoire est propre au système de fichiers ; Windows peut la changer sans modifier ses enfants.
    private fun sameFile(a: BasicFileAttributes, b: BasicFileAttributes): Boolean = a.fileKey() == b.fileKey() && (a.isDirectory || a.size() == b.size()) && a.lastModifiedTime() == b.lastModifiedTime() &&
        a.isDirectory == b.isDirectory && a.isRegularFile == b.isRegularFile && !b.isSymbolicLink && !b.isOther
}
