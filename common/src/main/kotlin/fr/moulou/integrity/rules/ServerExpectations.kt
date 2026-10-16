package fr.moulou.integrity.rules

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Properties

/**
 * Ce que le serveur attend d'un client au-delà de la référence : son Minecraft, le SHA-1 du `client.jar` que Mojang publie pour ce Minecraft,
 * sa propre version d'Integrity, et son propre JAR, que le client doit authentifier avec le nonce du contrôle. Le build de chaque module pose
 * les trois premiers dans `integrity-game.properties` ; le serveur ajoute le quatrième au démarrage. Une valeur absente vaut `null`, et les
 * règles refusent alors le client plutôt que de laisser passer.
 */
data class ServerExpectations(val minecraftVersion: String?, val clientSha1: String?, val integrityVersion: String?, val integrityJar: Path? = null) {
    companion object {
        const val RESOURCE = "/integrity-game.properties"
        private val sha1 = Regex("[a-f0-9]{40}")

        /** Aucune attente : celle des tests du cœur, qui en construisent une explicite. */
        val NONE = ServerExpectations(null, null, null)

        /** Les attentes du JAR en cours, lues une fois dans sa ressource, sans le JAR lui-même ; `NONE` quand elle manque. */
        val current: ServerExpectations by lazy { load(ServerExpectations::class.java.getResourceAsStream(RESOURCE)) }

        /** Lit une ressource `integrity-game.properties` ; une valeur vide ou non substituée par le build vaut `null`. */
        fun load(stream: InputStream?): ServerExpectations = stream?.use { input ->
            val properties = Properties().apply { load(InputStreamReader(input, StandardCharsets.UTF_8)) }
            fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("\${") }
            ServerExpectations(value("minecraft"), value("clientSha1")?.lowercase()?.takeIf(sha1::matches), value("integrity"))
        } ?: NONE
    }
}
