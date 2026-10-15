package fr.moulou.integrity

import fr.moulou.integrity.model.*
import fr.moulou.integrity.protocol.ControlSession
import fr.moulou.integrity.protocol.ControlWire
import fr.moulou.integrity.protocol.SecureExchange
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SecureProtocolTest {
    private val report = InventoryReport(PackIdentity("mdtk", "1", "26.3"), listOf(InventoryEntry(Category.MODS, "mods/example.jar", "a".repeat(64), 4)))

    @Test
    fun `un inventaire chiffre revient intact avec des cles ephemeres`() {
        val server = SecureExchange.server()
        val first = SecureExchange.seal(server.challenge, report)
        val second = SecureExchange.seal(server.challenge, report)
        assertEquals(report, server.open(first))
        assertEquals(report, server.open(second))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("a".repeat(64)))
    }

    @Test
    fun `un message altere ou reutilise sur un autre controle est refuse`() {
        val server = SecureExchange.server()
        val encrypted = SecureExchange.seal(server.challenge, report)
        val damaged = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { server.open(damaged) }
        val other = SecureExchange.server()
        assertThrows(IllegalArgumentException::class.java) { other.open(encrypted) }
        val envelope = ControlWire.decodeEnvelope(encrypted)
        val forgedId = ControlWire.encodeEnvelope(other.challenge.id, envelope.key, envelope.iv, envelope.ciphertext)
        assertThrows(Exception::class.java) { other.open(forgedId) }
        val forgedCategories = server.challenge.copy(scannedCategories = emptySet())
        assertThrows(Exception::class.java) { server.open(SecureExchange.seal(forgedCategories, report)) }
    }

    @Test
    fun `le protocole refuse les longueurs les versions et les octets supplementaires invalides`() {
        val challenge = SecureExchange.server().challenge
        val encoded = ControlWire.encodeChallenge(challenge)
        assertThrows(IllegalArgumentException::class.java) { ControlWire.decodeChallenge(encoded + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { ControlWire.decodeChallenge(encoded.copyOf().also { it[3] = 99 }) }
        assertThrows(Exception::class.java) { ControlWire.decodeChallenge(encoded.copyOf(4)) }
        assertThrows(IllegalArgumentException::class.java) { ControlWire.decodeChunk(ByteArray(ControlWire.CHUNK_BYTES + 33)) }
    }

    @Test
    fun `un inventaire volumineux est reassemble une fois sans depasser la limite par paquet`() {
        val id = UUID.randomUUID()
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val chunks = ControlWire.split(id, bytes)
        assertTrue(chunks.all { it.data.size <= ControlWire.CHUNK_BYTES })
        val session = ControlSession(id, 1000, 100)
        chunks.dropLast(1).forEach { assertNull(session.accept(it, 1001)) }
        assertArrayEquals(bytes, session.accept(chunks.last(), 1002))
        assertThrows(IllegalArgumentException::class.java) { session.accept(chunks.last(), 1003) }
        session.finish(1004)
        assertThrows(IllegalArgumentException::class.java) { session.accept(chunks.first(), 1005) }
    }

    @Test
    fun `un doublon un mauvais ordre et un changement de nombre de fragments sont refuses`() {
        val id = UUID.randomUUID()
        val chunks = ControlWire.split(id, ByteArray(50_000))
        val duplicate = ControlSession(id, 0, 100)
        duplicate.accept(chunks.first(), 1)
        assertThrows(IllegalArgumentException::class.java) { duplicate.accept(chunks.first(), 2) }
        assertThrows(IllegalArgumentException::class.java) { ControlSession(id, 0, 100).accept(chunks[1], 1) }
        val changedCount = ControlSession(id, 0, 100)
        changedCount.accept(chunks.first(), 1)
        assertThrows(IllegalArgumentException::class.java) { changedCount.accept(chunks[1].copy(count = chunks.size + 1), 2) }
        assertThrows(IllegalArgumentException::class.java) { ControlSession(UUID.randomUUID(), 0, 100).accept(chunks.first(), 1) }
    }

    @Test
    fun `la fin de controle ne peut pas valider une reponse apres le delai`() {
        val id = UUID.randomUUID()
        val chunk = ControlWire.split(id, byteArrayOf(1)).single()
        val expired = ControlSession(id, 1000, 100)
        assertTrue(expired.expired(1100))
        assertThrows(IllegalArgumentException::class.java) { expired.accept(chunk, 1100) }
        val validating = ControlSession(id, 1000, 100)
        validating.accept(chunk, 1099)
        assertThrows(IllegalArgumentException::class.java) { validating.finish(1100) }
        val closed = ControlSession(id, 1000, 100)
        closed.close()
        assertThrows(IllegalArgumentException::class.java) { closed.accept(chunk, 1001) }
    }
}
