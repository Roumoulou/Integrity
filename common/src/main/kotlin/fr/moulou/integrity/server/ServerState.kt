package fr.moulou.integrity.server

import fr.moulou.integrity.IntegrityMod
import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.ReferenceManifest
import fr.moulou.integrity.model.UploadChunk
import fr.moulou.integrity.network.ChallengePayload
import fr.moulou.integrity.protocol.ControlSession
import fr.moulou.integrity.protocol.SecureExchange
import fr.moulou.integrity.reference.ReferenceDiff
import fr.moulou.integrity.reference.ReferenceRepository
import fr.moulou.storify.core.set
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.SharedConstants
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ServerState(val server: MinecraftServer) : AutoCloseable {
    val configPath = server.serverDirectory.resolve("config/integrity/server.json5")
    var config: ServerConfig
        private set
    var reference: ReferenceManifest?
        private set
    val enabled: Boolean get() = !server.isSingleplayer || config.enforceSingleplayer

    private val store = IntegrityStores.server(configPath)
    private val repository = ReferenceRepository(server.serverDirectory)
    private val workers = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "integrity-server-worker").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "integrity-server-timeout").apply { isDaemon = true } }
    private val pending = HashMap<ServerCommonPacketListenerImpl, Pending>()
    private val lastCheck = HashMap<ServerGamePacketListenerImpl, Long>()
    private val results = LinkedHashMap<UUID, String>()
    @Volatile private var closed = false
    private var ticks = 0

    init {
        config = store.data.copy()
        reference = if (enabled) loadActive(config) else null
        if (enabled && reference == null) IntegrityMod.LOGGER.warn("No active client reference. Connections are refused until /integrity reference generate <id> and activate <id> succeed.")
    }

    fun begin(listener: ServerCommonPacketListenerImpl, send: (ChallengePayload) -> Unit, onAccepted: () -> Unit) {
        if (closed || pending.containsKey(listener)) return
        if (!enabled) { onAccepted(); return }
        val approved = reference
        if (approved == null) {
            listener.disconnect(Component.literal("Integrity : aucune référence client n'est activée. Contacte l'administrateur."))
            return
        }
        if (pending.size >= 128) {
            listener.disconnect(Component.literal("Integrity : trop de vérifications en cours. Réessaie dans un instant."))
            return
        }
        try {
            val snapshot = config.copy()
            val categories = fr.moulou.integrity.model.Category.entries.filter { !snapshot.policies.getValue(it).ignore || snapshot.rules.any { rule -> rule.category == it } }.toSet()
            val exchange = SecureExchange.server(snapshot.maxElements, snapshot.maxScanBytes, categories)
            val session = ControlSession(exchange.challenge.id, System.nanoTime(), TimeUnit.SECONDS.toNanos(snapshot.timeoutSeconds.toLong()))
            val control = Pending(exchange, session, approved, snapshot, onAccepted)
            pending[listener] = control
            control.timeout = timer.schedule({
                if (!closed) server.execute {
                    if (pending[listener] === control) reject(listener, "Le délai de vérification est dépassé.")
                }
            }, snapshot.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            send(ChallengePayload(exchange.challenge))
        } catch (error: Exception) {
            IntegrityMod.LOGGER.error("Cannot start client verification", error)
            reject(listener, "Impossible de démarrer la vérification.")
        }
    }

    fun receive(listener: ServerCommonPacketListenerImpl, chunk: UploadChunk) {
        val control = pending[listener]
        if (control == null) {
            reject(listener, "Réponse reçue sans contrôle en cours.")
            return
        }
        val encrypted = try { control.session.accept(chunk, System.nanoTime()) } catch (error: Exception) {
            IntegrityMod.LOGGER.warn("Invalid verification response for {}: {}", listener.owner.id(), error.message)
            reject(listener, "Réponse invalide, ancienne ou incomplète.")
            return
        } ?: return
        val player = listener.owner.id()
        control.verification = CompletableFuture.supplyAsync({
            val report = control.exchange.open(encrypted)
            IntegrityService.compare(control.reference.report, report, control.config, player)
        }, workers).whenComplete { verdict, error ->
            if (!closed) server.execute {
                if (pending[listener] !== control) return@execute
                val completedAt = System.nanoTime()
                when {
                    control.session.expired(completedAt) -> reject(listener, "Le délai de vérification est dépassé.")
                    error != null -> {
                        IntegrityMod.LOGGER.warn("Cannot decrypt or validate client report for {}", player, error)
                        reject(listener, "La réponse de contrôle ne peut pas être vérifiée.")
                    }
                    !verdict.accepted -> {
                        remember(player, verdict.playerMessage())
                        IntegrityMod.LOGGER.warn("Client {} rejected: problems={}, violations={}", player, verdict.problems, verdict.violations)
                        reject(listener, verdict.playerMessage().removePrefix("Integrity : "))
                    }
                    else -> {
                        control.session.finish(completedAt)
                        pending.remove(listener)
                        control.timeout?.cancel(false)
                        if (listener is ServerGamePacketListenerImpl) lastCheck[listener] = System.nanoTime()
                        remember(player, "conforme à ${control.reference.id}")
                        IntegrityMod.LOGGER.info("Client {} verified against reference {}", player, control.reference.id)
                        control.onAccepted()
                    }
                }
            }
        }
    }

    fun joined(listener: ServerGamePacketListenerImpl) { if (enabled) lastCheck[listener] = System.nanoTime() }

    fun tick() {
        // Les intervalles se comptent en dizaines de secondes : une passe par seconde suffit.
        if (closed || !enabled || config.recheckIntervalSeconds == 0 || ++ticks % 20 != 0) return
        val now = System.nanoTime()
        val interval = TimeUnit.SECONDS.toNanos(config.recheckIntervalSeconds.toLong())
        lastCheck.toMap().forEach { (listener, checked) ->
            if (listener.isAcceptingMessages && now - checked >= interval && listener !in pending) recheck(listener)
        }
    }

    fun recheckAll(): Int {
        if (!enabled) return 0
        server.playerList.players.toList().forEach { recheck(it.connection) }
        return server.playerList.players.size
    }

    private fun recheck(listener: ServerGamePacketListenerImpl) {
        if (!listener.isAcceptingMessages || listener in pending) return
        if (!ServerPlayNetworking.canSend(listener, ChallengePayload.TYPE)) {
            reject(listener, "Le client ne prend pas en charge les contrôles pendant la partie.")
            return
        }
        begin(listener, { ServerPlayNetworking.send(listener.player, it) }, {})
    }

    fun disconnected(listener: ServerCommonPacketListenerImpl) {
        pending.remove(listener)?.close()
        if (listener is ServerGamePacketListenerImpl) lastCheck.remove(listener)
    }

    fun generate(id: String): CompletableFuture<ReferenceManifest> {
        val snapshot = config.copy()
        return CompletableFuture.supplyAsync({ repository.generate(id, snapshot) }, workers)
    }

    fun activate(id: String) {
        val candidate = repository.load(id, config.maxElements)
        checkMinecraft(candidate, config)
        val previous = store.data.activeReference
        store.set(ServerConfig::activeReference, id)
        try { store.saveImmediate() } catch (error: Exception) { store.set(ServerConfig::activeReference, previous); throw error }
        config = store.data.copy()
        reference = candidate
        invalidateControls()
        recheckAll()
    }

    fun reload() {
        val candidate = IntegrityStores.server(configPath).use { it.data.copy() }
        val approved = if (!server.isSingleplayer || candidate.enforceSingleplayer) loadActive(candidate) else null
        store.reloadFromFile()
        require(store.data == candidate) { "Configuration changed while reloading" }
        config = candidate
        reference = approved
        invalidateControls()
        recheckAll()
    }

    fun referenceIds(): List<String> = repository.list()

    /** Ce que la référence `after` ajoute, retire, change ou renomme par rapport à `before`, les deux déjà générées. */
    fun diff(before: String, after: String): ReferenceDiff = IntegrityService.diff(repository.load(before, config.maxElements).report, repository.load(after, config.maxElements).report)
    fun status(): String = "Contrôle : ${if (enabled) "actif" else "désactivé en solo ou LAN"}. Référence : ${reference?.id ?: "aucune"}. " +
        "Minecraft : ${SharedConstants.getCurrentVersion().id()}. Contrôles en cours : ${pending.size}.\n" +
        results.entries.joinToString("\n") { "${it.key} : ${it.value}" }

    private fun invalidateControls() { pending.keys.toList().forEach { reject(it, "La référence ou les règles ont changé. Reconnecte-toi pour un nouveau contrôle.") } }

    private fun loadActive(settings: ServerConfig): ReferenceManifest? = settings.activeReference.takeIf(String::isNotBlank)?.let {
        repository.load(it, settings.maxElements).also { manifest -> checkMinecraft(manifest, settings) }
    }

    private fun checkMinecraft(manifest: ReferenceManifest, settings: ServerConfig) {
        require(!settings.requireSameMinecraft || manifest.report.pack.minecraftVersion == SharedConstants.getCurrentVersion().id()) { "Client reference Minecraft version differs from the server" }
    }

    private fun reject(listener: ServerCommonPacketListenerImpl, reason: String) {
        disconnected(listener)
        listener.disconnect(Component.literal("Integrity : $reason"))
    }

    private fun remember(player: UUID, result: String) {
        results.remove(player); results[player] = result
        while (results.size > 256) results.remove(results.keys.first())
    }

    override fun close() {
        closed = true
        pending.values.forEach(Pending::close); pending.clear(); lastCheck.clear()
        timer.shutdownNow(); workers.shutdownNow(); store.close()
    }

    private class Pending(
        val exchange: SecureExchange.ServerSide, val session: ControlSession, val reference: ReferenceManifest, val config: ServerConfig, val onAccepted: () -> Unit,
    ) : AutoCloseable {
        var timeout: ScheduledFuture<*>? = null
        var verification: CompletableFuture<*>? = null
        override fun close() { session.close(); timeout?.cancel(false); verification?.cancel(true) }
    }
}
