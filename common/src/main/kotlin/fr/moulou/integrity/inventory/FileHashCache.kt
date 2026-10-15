package fr.moulou.integrity.inventory

import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Mémorise l'empreinte des fichiers réguliers déjà lus, tant que leur chemin, leur taille et leur date de modification ne bougent pas.
 *
 * Le client se revérifie à chaque connexion et à chaque contrôle périodique : sans cache, il relirait tout le modpack à chaque fois.
 * Le cache vit en mémoire et ne change rien à la confiance accordée au client, qui calcule déjà seul son inventaire.
 */
class FileHashCache(private val maxEntries: Int = 4096) {
    private val entries = object : LinkedHashMap<Key, Value>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Value>): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(path: Path, attributes: BasicFileAttributes): Value? = entries[Key.of(path, attributes)]

    @Synchronized
    fun put(path: Path, attributes: BasicFileAttributes, value: Value) {
        entries[Key.of(path, attributes)] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    private data class Key(val path: String, val fileKey: String?, val size: Long, val modifiedMillis: Long) {
        companion object {
            fun of(path: Path, attributes: BasicFileAttributes): Key =
                Key(path.toString(), attributes.fileKey()?.toString(), attributes.size(), attributes.lastModifiedTime().toMillis())
        }
    }

    /** Ce que le scan aurait recalculé : l'empreinte SHA-256 et, pour un mod, son identifiant et sa version Fabric. */
    data class Value(val sha256: String, val modId: String?, val modVersion: String?)
}
