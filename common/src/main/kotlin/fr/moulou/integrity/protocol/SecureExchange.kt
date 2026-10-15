package fr.moulou.integrity.protocol

import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.InventoryReport
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec

/** Échange éphémère X25519, dérivation HKDF-SHA256 et chiffrement authentifié AES-256-GCM fournis par le JDK. */
object SecureExchange {
    private val random = SecureRandom()

    fun server(maxElements: Int = 10_000, maxScanBytes: Long = 68_719_476_736, scannedCategories: Set<Category> = Category.entries.toSet()): ServerSide {
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val nonce = ByteArray(32).also(random::nextBytes)
        return ServerSide(ControlChallenge(UUID.randomUUID(), nonce, pair.public.encoded, maxElements, maxScanBytes, scannedCategories), pair)
    }

    fun seal(challenge: ControlChallenge, report: InventoryReport): ByteArray {
        ControlWire.decodeChallenge(ControlWire.encodeChallenge(challenge))
        val pair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
        val plaintext = IntegrityStores.json.encodeToString(report).toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= ControlWire.MAX_REPORT_BYTES) { "Inventory report is too large" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pair.private, challenge.serverPublicKey, challenge), GCMParameterSpec(128, iv))
        cipher.updateAAD(transcript(challenge, pair.public.encoded))
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        return ControlWire.encodeEnvelope(challenge.id, pair.public.encoded, iv, ciphertext)
    }

    private fun key(privateKey: PrivateKey, peerKey: ByteArray, challenge: ControlChallenge): SecretKey {
        val publicKey = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(peerKey))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey); agreement.doPhase(publicKey, true)
        val shared = agreement.generateSecret()
        return try {
            val parameters = HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(challenge.nonce).thenExpand(ControlWire.encodeChallenge(challenge), 32)
            KDF.getInstance("HKDF-SHA256").deriveKey("AES", parameters)
        } finally { shared.fill(0) }
    }

    private fun transcript(challenge: ControlChallenge, clientKey: ByteArray): ByteArray = ControlWire.encodeChallenge(challenge) + clientKey

    class ServerSide internal constructor(val challenge: ControlChallenge, private val pair: KeyPair) {
        fun open(encrypted: ByteArray): InventoryReport {
            val envelope = ControlWire.decodeEnvelope(encrypted)
            require(envelope.id == challenge.id) { "Response belongs to another control" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(pair.private, envelope.key, challenge), GCMParameterSpec(128, envelope.iv))
            cipher.updateAAD(transcript(challenge, envelope.key))
            val plaintext = cipher.doFinal(envelope.ciphertext)
            return try { IntegrityStores.json.decodeFromString<InventoryReport>(plaintext.toString(StandardCharsets.UTF_8)) } finally { plaintext.fill(0) }
        }
    }
}
