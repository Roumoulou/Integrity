package fr.moulou.integrity.server

import net.minecraft.server.MinecraftServer
import java.util.IdentityHashMap

object ServerStates {
    private val states = IdentityHashMap<MinecraftServer, ServerState>()
    fun start(server: MinecraftServer) { check(server !in states); states[server] = ServerState(server) }
    fun get(server: MinecraftServer): ServerState = states[server] ?: error("Integrity server state is unavailable")
    fun find(server: MinecraftServer): ServerState? = states[server]
    fun stop(server: MinecraftServer) { states.remove(server)?.close() }
}
