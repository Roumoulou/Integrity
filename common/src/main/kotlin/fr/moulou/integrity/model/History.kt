package fr.moulou.integrity.model

import kotlinx.serialization.Serializable

/** Un contrôle d'un joueur tel que le serveur l'a conclu : le verdict, son résumé, les versions vues et les canaux que le client annonçait. */
@Serializable
data class HistoryEntry(
    val at: String,
    val accepted: Boolean,
    val summary: String,
    val reference: String? = null,
    val integrityVersion: String? = null,
    val loaderVersion: String? = null,
    val minecraftVersion: String? = null,
    val channels: List<String> = emptyList(),
)

/** Les derniers contrôles d'un joueur, du plus ancien au plus récent, bornés par le serveur. */
@Serializable
data class PlayerHistory(val player: String, val name: String? = null, val entries: List<HistoryEntry> = emptyList(), val schemaVersion: Int = 1)
