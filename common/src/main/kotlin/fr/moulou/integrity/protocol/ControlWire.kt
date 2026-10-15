package fr.moulou.integrity.protocol

import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.UploadChunk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

object ControlWire {
    const val VERSION = 1
    const val CHUNK_BYTES = 24 * 1024
    const val MAX_REPORT_BYTES = 4 * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = MAX_REPORT_BYTES + 512
    const val MAX_CHUNKS = (MAX_ENVELOPE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES

    fun encodeChallenge(challenge: ControlChallenge): ByteArray = output {
        writeInt(VERSION); uuid(challenge.id); bytes(challenge.nonce, 32); bytes(challenge.serverPublicKey, 128)
        writeInt(challenge.maxElements); writeLong(challenge.maxScanBytes)
        writeInt(challenge.scannedCategories.fold(0) { bits, category -> bits or (1 shl category.ordinal) })
    }

    fun decodeChallenge(bytes: ByteArray): ControlChallenge = input(bytes, 4096) {
        require(readInt() == VERSION) { "Unsupported Integrity protocol" }
        val id = uuid(); val nonce = bytes(32); val key = bytes(128); val elements = readInt(); val scanBytes = readLong(); val categoryBits = readInt()
        require(categoryBits in 0 until (1 shl Category.entries.size)) { "Invalid category selection" }
        val result = ControlChallenge(id, nonce, key, elements, scanBytes, Category.entries.filter { categoryBits and (1 shl it.ordinal) != 0 }.toSet())
        require(result.nonce.size == 32 && result.maxElements in 1..10_000 && result.maxScanBytes in 1..1_099_511_627_776) { "Invalid control challenge" }
        result
    }

    fun encodeChunk(chunk: UploadChunk): ByteArray = output {
        require(chunk.count in 1..MAX_CHUNKS && chunk.index in 0 until chunk.count && chunk.data.isNotEmpty()) { "Invalid upload chunk" }
        uuid(chunk.id); writeInt(chunk.index); writeInt(chunk.count); bytes(chunk.data, CHUNK_BYTES)
    }

    fun decodeChunk(bytes: ByteArray): UploadChunk = input(bytes, CHUNK_BYTES + 32) {
        val result = UploadChunk(uuid(), readInt(), readInt(), bytes(CHUNK_BYTES))
        require(result.count in 1..MAX_CHUNKS && result.index in 0 until result.count && result.data.isNotEmpty()) { "Invalid upload chunk" }
        result
    }

    fun split(id: UUID, bytes: ByteArray): List<UploadChunk> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENVELOPE_BYTES) { "Encrypted response is too large" }
        val count = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return (0 until count).map { index -> UploadChunk(id, index, count, bytes.copyOfRange(index * CHUNK_BYTES, minOf(bytes.size, (index + 1) * CHUNK_BYTES))) }
    }

    internal fun encodeEnvelope(id: UUID, key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray = output {
        writeInt(VERSION); uuid(id); bytes(key, 128); bytes(iv, 12); bytes(ciphertext, MAX_REPORT_BYTES + 16)
    }

    internal fun decodeEnvelope(bytes: ByteArray): Envelope = input(bytes, MAX_ENVELOPE_BYTES) {
        require(readInt() == VERSION) { "Unsupported Integrity protocol" }
        val result = Envelope(uuid(), bytes(128), bytes(12), bytes(MAX_REPORT_BYTES + 16))
        require(result.iv.size == 12 && result.ciphertext.size >= 16) { "Invalid encrypted response" }
        result
    }

    private fun output(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { it.block() }
        buffer.toByteArray()
    }

    private fun <T> input(bytes: ByteArray, limit: Int, block: DataInputStream.() -> T): T {
        require(bytes.size <= limit) { "Message size limit exceeded" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val result = stream.block()
            require(stream.available() == 0) { "Unexpected trailing bytes" }
            result
        }
    }

    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.uuid(): UUID = UUID(readLong(), readLong())
    private fun DataOutputStream.bytes(value: ByteArray, limit: Int) { require(value.size <= limit); writeInt(value.size); write(value) }
    private fun DataInputStream.bytes(limit: Int): ByteArray {
        val size = readInt()
        require(size in 0..limit && size <= available()) { "Invalid byte array length" }
        return ByteArray(size).also(::readFully)
    }

    internal data class Envelope(val id: UUID, val key: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)
}
