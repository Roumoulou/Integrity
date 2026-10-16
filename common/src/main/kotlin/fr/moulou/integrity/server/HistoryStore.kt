package fr.moulou.integrity.server

import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.model.HistoryEntry
import fr.moulou.integrity.model.PlayerHistory
import fr.moulou.storify.utils.AtomicFiles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** L'historique des contrôles par joueur, un fichier JSON par UUID, borné aux derniers contrôles et écrit atomiquement. */
class HistoryStore(private val directory: Path) {
    companion object {
        /** Le sous-dossier du dossier du serveur. */
        const val DIRECTORY = "config/integrity/history"
        const val MAX_ENTRIES = 50
        private val logger = LoggerFactory.getLogger("integrity")
    }

    /** Ajoute un contrôle à l'historique du joueur et rend l'historique borné ; `name` remplace le nom connu quand il est donné. */
    @Synchronized
    fun append(player: UUID, name: String?, entry: HistoryEntry): PlayerHistory {
        val previous = read(player)
        val history = PlayerHistory(player.toString(), name ?: previous?.name, (previous?.entries.orEmpty() + entry).takeLast(MAX_ENTRIES))
        Files.createDirectories(directory)
        AtomicFiles.write(file(player)) { temporary -> Files.writeString(temporary, IntegrityStores.json.encodeToString(history) + "\n", StandardCharsets.UTF_8) }
        return history
    }

    /** L'historique d'un joueur, ou `null` sans fichier ; un fichier illisible est signalé et compte pour rien. */
    @Synchronized
    fun read(player: UUID): PlayerHistory? {
        val file = file(player)
        if (!Files.isRegularFile(file)) return null
        return try {
            IntegrityStores.json.decodeFromString<PlayerHistory>(Files.readString(file, StandardCharsets.UTF_8))
        } catch (error: Exception) {
            logger.warn("Unreadable control history {}; it starts over", file, error)
            null
        }
    }

    private fun file(player: UUID): Path = directory.resolve("$player.json")
}
