package fr.moulou.integrity.model

import java.util.UUID

data class ControlChallenge(val id: UUID, val nonce: ByteArray, val serverPublicKey: ByteArray, val maxElements: Int, val maxScanBytes: Long,
    val scannedCategories: Set<Category> = Category.entries.toSet())
data class UploadChunk(val id: UUID, val index: Int, val count: Int, val data: ByteArray)
