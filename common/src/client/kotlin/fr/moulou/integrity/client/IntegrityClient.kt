package fr.moulou.integrity.client

import com.mojang.brigadier.arguments.StringArgumentType
import fr.moulou.integrity.IntegrityMod
import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.inventory.FileHashCache
import fr.moulou.integrity.inventory.RuntimeInspector
import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.ReferenceManifest
import fr.moulou.integrity.model.SelectedPack
import fr.moulou.integrity.network.ChallengePayload
import fr.moulou.integrity.network.ChunkPayload
import fr.moulou.integrity.protocol.ControlWire
import fr.moulou.integrity.protocol.SecureExchange
import fr.moulou.integrity.reference.ReferenceRepository
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketSender
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.repository.PackSource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/** Calcule l'inventaire hors du fil du jeu et répond uniquement sur la connexion qui a demandé ce contrôle. */
class IntegrityClient : ClientModInitializer {
    companion object {
        private val TOAST = SystemToast.SystemToastId()
        private const val PROGRESS_STEP = 4
    }

    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "integrity-client-scan").apply { isDaemon = true } }
    private val active = AtomicReference<Scan?>()
    // Les empreintes déjà calculées servent au contrôle suivant tant que les fichiers n'ont pas bougé.
    private val cache = FileHashCache()

    override fun onInitializeClient() {
        runCatching { ClientBridge.INSTANCE }.onFailure { IntegrityMod.LOGGER.error("No client bridge for this game version; the verification progress will not be displayed", it) }
        ClientConfigurationNetworking.registerGlobalReceiver(ChallengePayload.TYPE) { payload, context ->
            val listener = context.packetListener()
            handle(listener, payload.challenge, context.responseSender(), { listener.isAcceptingMessages })
        }
        ClientPlayNetworking.registerGlobalReceiver(ChallengePayload.TYPE) { payload, context ->
            val listener = context.player().connection
            handle(listener, payload.challenge, context.responseSender(), { listener.isAcceptingMessages })
        }
        ClientConfigurationConnectionEvents.DISCONNECT.register { listener, _ -> cancel(listener) }
        ClientPlayConnectionEvents.DISCONNECT.register { listener, _ -> cancel(listener) }
        ClientLifecycleEvents.CLIENT_STOPPING.register { _ -> active.getAndSet(null)?.future?.cancel(true); worker.shutdownNow() }
        // Une racine distincte de la commande serveur, pour que /integrity reste traitée par le serveur.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommands.literal("integrityclient")
                .then(ClientCommands.literal("export").then(ClientCommands.argument("id", StringArgumentType.word()).executes { context ->
                    export(context.source, StringArgumentType.getString(context, "id"))
                })))
        }
    }

    private fun handle(owner: Any, challenge: ControlChallenge, sender: PacketSender, connected: () -> Boolean) {
        val client = Minecraft.getInstance()
        // Les packs sélectionnés et leur dossier se lisent sur le fil du client ; le reste du relevé d'exécution se prend sur le worker, avec l'inventaire.
        val selectedPacks = runCatching { selectedPacks(client) }.getOrElse { IntegrityMod.LOGGER.warn("Cannot list the selected resource packs", it); emptyList() }
        val packDirectory = runCatching { client.resourcePackDirectory }.getOrNull()
        // L'écran de connexion de Minecraft traite aussi les déconnexions et l'annulation : le remplacer figerait l'affichage après un refus.
        val future = FutureTask<Unit> {
            try {
                IntegrityMod.LOGGER.info("Verifying client installation for control {}", challenge.id)
                val minecraft = SharedConstants.getCurrentVersion().id()
                val report = try {
                    val directory = FabricLoader.getInstance().gameDir
                    val identity = IntegrityStores.pack(directory, createIfMissing = true)
                    require(identity.minecraftVersion == minecraft) { "integrity-modpack.json differs from the running Minecraft version" }
                    val inventory = IntegrityService.scan(directory, identity, ServerConfig(maxElements = challenge.maxElements, maxScanBytes = challenge.maxScanBytes), challenge.scannedCategories, cache) { done, total ->
                        if (done == total) notify(client, "Installation vérifiée, envoi de la réponse.")
                        else if (done % PROGRESS_STEP == 0) notify(client, "Vérification de l'installation : $done/$total")
                    }
                    inventory.copy(runtime = RuntimeInspector.inspect(directory, selectedPacks, packDirectory, challenge.nonce))
                } catch (error: InterruptedException) {
                    return@FutureTask Unit
                } catch (error: Exception) {
                    IntegrityMod.LOGGER.warn("Client inventory scan failed", error)
                    notify(client, "La vérification locale a échoué ; voir le journal du jeu.")
                    InventoryReport(PackIdentity("unknown", "unknown", minecraft), error = "Local verification failed")
                }
                val chunks = ControlWire.split(challenge.id, SecureExchange.seal(challenge, report))
                client.execute {
                    val current = active.get()
                    if (current?.owner === owner && current.id == challenge.id && connected()) chunks.forEach { sender.sendPacket(ChunkPayload(it)) }
                }
            } catch (error: Exception) {
                IntegrityMod.LOGGER.warn("Cannot answer Integrity control", error)
                client.execute { if (active.get()?.id == challenge.id && connected()) sender.disconnect(Component.literal("Integrity : impossible de protéger la réponse de contrôle.")) }
            }
        }
        active.getAndSet(Scan(owner, challenge.id, future))?.future?.cancel(true)
        worker.execute(future)
    }

    private fun cancel(owner: Any) {
        val current = active.get()
        if (current?.owner === owner && active.compareAndSet(current, null)) current.future.cancel(true)
    }

    /** Les resourcepacks sélectionnés, lus sur le fil du client ; les sources du jeu n'ont pas de nom, ses constantes se reconnaissent une à une. */
    private fun selectedPacks(client: Minecraft): List<SelectedPack> {
        fun read(): List<SelectedPack> = client.resourcePackRepository.selectedPacks.map { pack ->
            val source = when (pack.packSource) {
                PackSource.DEFAULT -> "DEFAULT"
                PackSource.BUILT_IN -> "BUILT_IN"
                PackSource.FEATURE -> "FEATURE"
                PackSource.WORLD -> "WORLD"
                PackSource.SERVER -> "SERVER"
                else -> "OTHER"
            }
            SelectedPack(pack.id, source)
        }
        return if (client.isSameThread) read() else client.submit(Supplier { read() }).get(10, TimeUnit.SECONDS)
    }

    /** Affiche ou met à jour le toast Integrity, depuis n'importe quel fil. */
    private fun notify(client: Minecraft, text: String) {
        client.execute {
            try {
                SystemToast.addOrUpdate(ClientBridge.INSTANCE.toastManager(client), TOAST, Component.literal("Integrity"), Component.literal(text))
            } catch (error: Exception) {
                IntegrityMod.LOGGER.warn("Cannot display the verification progress", error)
            }
        }
    }

    /** Exporte l'inventaire local en manifeste, prêt à être copié dans les références d'un serveur. */
    private fun export(source: FabricClientCommandSource, id: String): Int {
        val client = source.client
        source.sendFeedback(Component.literal("Integrity : export de l'inventaire $id en cours..."))
        worker.execute {
            try {
                val directory = FabricLoader.getInstance().gameDir
                val identity = IntegrityStores.pack(directory, createIfMissing = false)
                val report = IntegrityService.scan(directory, identity, cache = cache)
                val repository = ReferenceRepository(directory, ReferenceRepository.EXPORTS)
                val manifest = repository.save(ReferenceManifest(id, Instant.now().toString(), report))
                client.execute {
                    source.sendFeedback(Component.literal("Integrity : manifeste $id exporté, ${manifest.report.entries.size} élément(s) : ${repository.file(id)}. " +
                        "Copie-le dans ${ReferenceRepository.REFERENCES} du serveur, puis active-le."))
                }
            } catch (error: Exception) {
                IntegrityMod.LOGGER.warn("Cannot export the local inventory", error)
                client.execute { source.sendError(Component.literal("Integrity : export impossible (${error.message}). Vérifie integrity-modpack.json et le journal du jeu.")) }
            }
        }
        return 1
    }

    private data class Scan(val owner: Any, val id: UUID, val future: FutureTask<Unit>)
}
