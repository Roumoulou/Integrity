package fr.moulou.integrity

import fr.moulou.integrity.command.IntegrityCommands
import fr.moulou.integrity.network.ChallengePayload
import fr.moulou.integrity.network.ChunkPayload
import fr.moulou.integrity.network.IntegrityPayloads
import fr.moulou.integrity.server.ServerStates
import fr.moulou.integrity.server.VerificationTask
import fr.moulou.integrity.inventory.RuntimeInspector
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Enregistre les canaux de contrôle et ferme l'accès au monde jusqu'à la validation de la configuration client. */
class IntegrityMod : ModInitializer {
    companion object {
        const val MOD_ID = "integrity"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)

        /** La version du mod telle que Loader la lit, `1.1.0+26.3` ; `?` hors de Loader. */
        val VERSION: String by lazy { FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("?") }

        /** Le JAR du mod, quand Loader l'a chargé depuis un fichier ; `null` dans un run Loom, où le mod vient de dossiers de classes. */
        val JAR: Path? by lazy { RuntimeInspector.ownJar(FabricLoader.getInstance()) }
    }

    override fun onInitialize() {
        IntegrityPayloads.register()
        IntegrityCommands.register()
        ServerLifecycleEvents.SERVER_STARTING.register(ServerStates::start)
        ServerLifecycleEvents.SERVER_STOPPED.register(ServerStates::stop)
        ServerTickEvents.END_SERVER_TICK.register { server -> ServerStates.find(server)?.tick() }
        ServerConfigurationConnectionEvents.CONFIGURE.register { listener, server ->
            if (!ServerStates.get(server).enabled) return@register
            if (!ServerConfigurationNetworking.canSend(listener, ChallengePayload.TYPE)) {
                // Un client 1.0.x n'annonce que le canal de l'ancien protocole : il apprend quelle version installer.
                val outdated = ServerConfigurationNetworking.canSend(listener, IntegrityPayloads.LEGACY_CHALLENGE)
                val reason = if (outdated) "Integrity 1.0.x est trop ancien pour ce serveur ; installe Integrity ${VERSION.substringBefore('+')}." else "Integrity est requis côté client pour rejoindre ce serveur."
                listener.disconnect(Component.literal(reason))
            } else {
                val fabric = listener as FabricServerConfigurationPacketListenerImpl
                fabric.addTask(VerificationTask { sender ->
                    ServerStates.get(server).begin(listener, { sender.accept(ServerConfigurationNetworking.createClientboundPacket(it)) }, { fabric.completeTask(VerificationTask.TYPE) })
                })
            }
        }
        ServerConfigurationNetworking.registerGlobalReceiver(ChunkPayload.TYPE) { payload, context ->
            context.server().execute { ServerStates.find(context.server())?.receive(context.packetListener(), payload.chunk) }
        }
        ServerPlayNetworking.registerGlobalReceiver(ChunkPayload.TYPE) { payload, context -> ServerStates.find(context.server())?.receive(context.player().connection, payload.chunk) }
        ServerConfigurationConnectionEvents.DISCONNECT.register { listener, server -> server.execute { ServerStates.find(server)?.disconnected(listener) } }
        ServerPlayConnectionEvents.JOIN.register { listener, _, server -> ServerStates.get(server).joined(listener) }
        ServerPlayConnectionEvents.DISCONNECT.register { listener, server -> ServerStates.find(server)?.disconnected(listener) }
        // Un fichier échangé entre deux contrôles périodiques se rattrape aux moments où le joueur change de monde ou de vie.
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, _ -> ServerStates.find(player.level().server)?.recheckOnEvent(player.connection) }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ -> ServerStates.find(player.level().server)?.recheckOnEvent(player.connection) }
    }
}
