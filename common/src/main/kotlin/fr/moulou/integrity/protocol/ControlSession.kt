package fr.moulou.integrity.protocol

import fr.moulou.integrity.model.UploadChunk
import java.io.ByteArrayOutputStream
import java.util.UUID

class ControlSession(val id: UUID, private val startedAt: Long, private val timeoutNanos: Long) {
    private var count: Int? = null
    private var nextIndex = 0
    private var closed = false
    private var verifying = false
    private val bytes = ByteArrayOutputStream()

    fun expired(now: Long): Boolean = now - startedAt >= timeoutNanos

    fun accept(chunk: UploadChunk, now: Long): ByteArray? {
        require(!closed && !verifying && !expired(now)) { "Control is closed, verifying or expired" }
        require(chunk.id == id && chunk.index == nextIndex && chunk.count in 1..ControlWire.MAX_CHUNKS && chunk.index < chunk.count) { "Stale, duplicate or reordered response" }
        require(chunk.data.isNotEmpty() && chunk.data.size <= ControlWire.CHUNK_BYTES) { "Invalid upload chunk size" }
        require(count == null || count == chunk.count) { "Upload chunk count changed" }
        require(bytes.size().toLong() + chunk.data.size <= ControlWire.MAX_ENVELOPE_BYTES) { "Encrypted response is too large" }
        count = chunk.count
        bytes.write(chunk.data); nextIndex++
        return if (nextIndex == count) {
            verifying = true
            bytes.toByteArray().also { bytes.reset() }
        } else null
    }

    fun finish(now: Long) {
        require(!closed && verifying && !expired(now)) { "Control cannot complete" }
        closed = true
    }

    fun close() { closed = true; bytes.reset() }
}
