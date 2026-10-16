package fr.moulou.integrity

import fr.moulou.integrity.model.HistoryEntry
import fr.moulou.integrity.server.HistoryStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class HistoryStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `l historique d un joueur est borne persiste et repart d un fichier illisible`() {
        val store = HistoryStore(directory.resolve("history"))
        val player = UUID.randomUUID()
        repeat(HistoryStore.MAX_ENTRIES + 5) { index ->
            store.append(player, "Alice", HistoryEntry("2026-10-16T10:00:${"%02d".format(index % 60)}Z", index % 2 == 0, "essai $index", channels = listOf("integrity:v2/response")))
        }
        val history = HistoryStore(directory.resolve("history")).read(player)
        assertNotNull(history)
        assertEquals(HistoryStore.MAX_ENTRIES, history!!.entries.size)
        assertEquals("Alice", history.name)
        assertEquals("essai ${HistoryStore.MAX_ENTRIES + 4}", history.entries.last().summary)
        assertEquals(listOf("integrity:v2/response"), history.entries.last().channels)
        assertNull(store.read(UUID.randomUUID()))
        Files.writeString(directory.resolve("history/$player.json"), "{ broken")
        assertNull(store.read(player))
        assertEquals(1, store.append(player, null, HistoryEntry("2026-10-16T11:00:00Z", true, "reprise")).entries.size)
    }
}
