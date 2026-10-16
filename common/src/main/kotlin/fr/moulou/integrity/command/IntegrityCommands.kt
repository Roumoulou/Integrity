package fr.moulou.integrity.command

import com.mojang.brigadier.arguments.StringArgumentType
import fr.moulou.integrity.IntegrityMod
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.server.ServerState
import fr.moulou.integrity.server.ServerStates
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import java.util.UUID
import java.util.concurrent.CompletionException

object IntegrityCommands {
    private const val HISTORY_LINES = 10

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(literal("integrity")
                .requires { it.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                .executes { context -> success(context.source, ServerStates.get(context.source.server).status()) }
                .then(literal("status").executes { context -> success(context.source, ServerStates.get(context.source.server).status()) })
                .then(literal("reload").executes { context -> operation(context.source) { state -> state.reload(); "Règles et référence rechargées. Les joueurs sont revérifiés." } })
                .then(literal("check").executes { context -> operation(context.source) { state -> "Vérification demandée pour ${state.recheckAll()} joueur(s)." } })
                .then(literal("history").then(argument("player", StringArgumentType.word()).executes { context ->
                    operation(context.source) { state -> history(state, StringArgumentType.getString(context, "player")) }
                }))
                .then(literal("reference")
                    .then(literal("list").executes { context -> operation(context.source) { state -> state.referenceIds().joinToString(", ").ifEmpty { "Aucune référence générée." } } })
                    .then(literal("generate").then(argument("id", StringArgumentType.word()).executes { context ->
                        generate(context.source, StringArgumentType.getString(context, "id"))
                    }))
                    .then(literal("activate").then(argument("id", StringArgumentType.word()).executes { context ->
                        operation(context.source) { state ->
                            val id = StringArgumentType.getString(context, "id")
                            state.activate(id)
                            "Référence $id activée. Les joueurs sont revérifiés."
                        }
                    }))
                    .then(literal("diff").then(argument("before", StringArgumentType.word()).then(argument("after", StringArgumentType.word()).executes { context ->
                        operation(context.source) { state ->
                            val before = StringArgumentType.getString(context, "before")
                            val after = StringArgumentType.getString(context, "after")
                            "De $before vers $after : ${state.diff(before, after).describe()}"
                        }
                    }))),
                ),
            )
        }
    }

    /** Les derniers contrôles d'un joueur, désigné par son nom s'il est connecté, sinon par son UUID. */
    private fun history(state: ServerState, player: String): String {
        val id = state.server.playerList.getPlayerByName(player)?.uuid ?: runCatching { UUID.fromString(player) }.getOrNull()
            ?: throw IllegalArgumentException("Unknown player $player; give the name of a connected player or a UUID")
        val history = state.history(id) ?: return "Aucun contrôle noté pour $player ($id)."
        val lines = history.entries.asReversed().take(HISTORY_LINES).map { entry ->
            val versions = listOfNotNull(entry.integrityVersion?.let { "Integrity $it" }, entry.loaderVersion?.let { "Loader $it" }, entry.minecraftVersion?.let { "Minecraft $it" })
            "${entry.at} ${if (entry.accepted) "accepté" else "refusé"} : ${entry.summary}" + (versions + "${entry.channels.size} canaux").joinToString(", ", " (", ")")
        }
        return "${history.name ?: player} ($id) : ${history.entries.size} contrôle(s) noté(s), du plus récent au plus ancien.\n" + lines.joinToString("\n")
    }

    private fun generate(source: CommandSourceStack, id: String): Int {
        val state = ServerStates.get(source.server)
        success(source, "Génération de la référence $id en cours...")
        state.generate(id).whenComplete { manifest, error ->
            if (!source.server.isStopped) source.server.execute {
                if (ServerStates.find(source.server) !== state) return@execute
                if (error != null) {
                    IntegrityMod.LOGGER.error("Reference generation failed for {}", id, error)
                    source.sendFailure(Component.literal("La génération a échoué${reason(error)}. Vérifie la copie client et integrity-modpack.json, puis consulte le journal du serveur."))
                } else {
                    val warning = if (manifest.report.pack.id == IntegrityStores.DEFAULT_PACK_ID) {
                        " Attention : l'identité du modpack est celle par défaut (${IntegrityStores.DEFAULT_PACK_ID}) ; remplace integrity-modpack.json dans la copie approuvée avant d'activer."
                    } else ""
                    success(source, "Référence ${manifest.id} générée : ${manifest.report.entries.size} élément(s). Active-la avec /integrity reference activate ${manifest.id}.$warning")
                }
            }
        }
        return 1
    }

    private fun operation(source: CommandSourceStack, action: (ServerState) -> String): Int = try {
        success(source, action(ServerStates.get(source.server)))
    } catch (error: Exception) {
        IntegrityMod.LOGGER.error("Integrity administration operation failed", error)
        source.sendFailure(Component.literal("Integrity : opération refusée${reason(error)}. Consulte le journal du serveur pour le détail."))
        0
    }

    /** Le message d'une erreur attendue (identifiant pris, référence introuvable, copie incomplète) ; les autres renvoient au journal. */
    private fun reason(error: Throwable): String {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return if ((cause is IllegalArgumentException || cause is IllegalStateException) && cause.message != null) " (${cause.message})" else ""
    }

    private fun success(source: CommandSourceStack, text: String): Int {
        source.sendSuccess({ Component.literal(text) }, false)
        return 1
    }
}
