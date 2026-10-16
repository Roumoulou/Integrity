package fr.moulou.integrity.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * L'empreinte d'un JAR liée à un contrôle : HMAC-SHA256 du fichier entier, le nonce du challenge pour clé. Le client la calcule sur son JAR
 * Integrity, le serveur sur le sien, le même fichier : un JAR patché ne répond pas juste, et une réponse ne se précalcule pas sans le nonce.
 */
object JarHmac {
    private var cached: Cached? = null

    fun of(jar: Path, nonce: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(nonce, "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(bytes(jar)))
    }

    /** Les octets du JAR, relus seulement quand le fichier a changé ; un seul JAR est gardé, celui du mod lui-même. */
    @Synchronized
    private fun bytes(jar: Path): ByteArray {
        val attributes = Files.readAttributes(jar, BasicFileAttributes::class.java)
        val stamp = Stamp(jar.toAbsolutePath().normalize().toString(), attributes.size(), attributes.lastModifiedTime().toMillis())
        cached?.let { if (it.stamp == stamp) return it.bytes }
        return Files.readAllBytes(jar).also { cached = Cached(stamp, it) }
    }

    private data class Stamp(val path: String, val size: Long, val modifiedMillis: Long)
    private class Cached(val stamp: Stamp, val bytes: ByteArray)
}
