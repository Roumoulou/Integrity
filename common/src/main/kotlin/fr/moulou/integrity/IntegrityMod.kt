package fr.moulou.integrity

import fr.moulou.integrity.command.IntegrityCommands
import fr.moulou.integrity.network.ChallengePayload
import fr.moulou.integrity.network.ChunkPayload
import fr.moulou.integrity.network.IntegrityPayloads
import fr.moulou.integrity.server.ServerStates
import fr.moulou.integrity.server.VerificationTask
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/** Enregistre les canaux de contrôle et ferme l'accès au monde jusqu'à la validation de la configuration client. */
class IntegrityMod : ModInitializer {
    companion object {
        const val MOD_ID = "integrity"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
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
                listener.disconnect(Component.literal("Integrity est requis côté client pour rejoindre ce serveur."))
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
    }
}
