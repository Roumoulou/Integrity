package fr.moulou.integrity.gametest

import com.mojang.authlib.GameProfile
import fr.moulou.integrity.api.IntegrityService
import fr.moulou.integrity.config.IntegrityStores
import fr.moulou.integrity.config.ServerConfig
import fr.moulou.integrity.model.Category
import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.GameJar
import fr.moulou.integrity.model.InventoryReport
import fr.moulou.integrity.model.LoadedMod
import fr.moulou.integrity.model.OriginKind
import fr.moulou.integrity.model.PackIdentity
import fr.moulou.integrity.model.PreLaunchSnapshot
import fr.moulou.integrity.model.RuntimeReport
import fr.moulou.integrity.network.ChunkPayload
import fr.moulou.integrity.protocol.ControlWire
import fr.moulou.integrity.protocol.SecureExchange
import fr.moulou.integrity.rules.ServerExpectations
import fr.moulou.integrity.server.ServerStates
import io.netty.channel.ChannelFutureListener
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl
import net.minecraft.SharedConstants
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Éprouve le chargement Fabric et le contrôle asynchrone sur un vrai serveur, avec une connexion de test en mémoire. */
class IntegrityGameTests {
    @GameTest
    @JvmName("le_serveur_enregistre_les_commandes_et_le_controle_avant_connexion")
    fun `le serveur enregistre les commandes et le controle avant connexion`(helper: GameTestHelper) {
        val server = helper.level.server
        helper.assertTrue(ServerStates.get(server).enabled, "Le contrôle serveur doit rester actif par défaut")
        helper.assertTrue(Files.isRegularFile(ServerStates.get(server).configPath), "La configuration Storify doit exister")
        helper.assertTrue(server.commands.dispatcher.root.getChild("integrity") != null, "Les commandes doivent être enregistrées")
        helper.assertTrue(ServerConfigurationNetworking.getGlobalReceivers().contains(ChunkPayload.TYPE.id()), "La réponse de configuration doit être enregistrée")
        helper.assertTrue(ServerPlayNetworking.getGlobalReceivers().contains(ChunkPayload.TYPE.id()), "La réponse en partie doit être enregistrée")
        helper.assertTrue(FabricServerConfigurationPacketListenerImpl::class.java.isAssignableFrom(ServerConfigurationPacketListenerImpl::class.java),
            "Le mixin Fabric des tâches de configuration doit être appliqué")
        helper.succeed()
    }

    @GameTest(maxTicks = 1200)
    @JvmName("la_reference_active_accepte_le_client_conforme_et_refuse_sa_reconnexion_modifiee")
    fun `la reference active accepte le client conforme et refuse sa reconnexion modifiee`(helper: GameTestHelper) {
        val server = helper.level.server
        val state = ServerStates.get(server)
        val game = server.serverDirectory.resolve(state.config.referenceInstance).resolve(".minecraft")
        Files.createDirectories(game.resolve("resourcepacks"))
        val identity = PackIdentity("integrity-test", "1", SharedConstants.getCurrentVersion().id())
        Files.writeString(game.resolve("integrity-modpack.json"), IntegrityStores.json.encodeToString(identity))
        Files.writeString(game.resolve("resourcepacks/pack.zip"), "approved")
        val generated = state.generate("gametest")
        val profile = GameProfile(UUID.randomUUID(), "IntegrityTest")
        val firstConnection = TestConnection()
        val first = ServerConfigurationPacketListenerImpl(server, firstConnection, CommonListenerCookie.createInitial(profile, false))
        val secondConnection = TestConnection()
        val second = ServerConfigurationPacketListenerImpl(server, secondConnection, CommonListenerCookie.createInitial(profile, false))
        var firstChallenge: ControlChallenge? = null
        var secondChallenge: ControlChallenge? = null
        var accepted = false
        var wronglyAccepted = false

        helper.startSequence().thenWaitUntil {
            helper.assertTrue(generated.isDone, "La référence se calcule sur un worker")
        }.thenExecute {
            generated.join()
            helper.assertTrue(state.reference == null, "Générer ne doit pas activer une référence")
            // Le serveur de test tourne depuis des dossiers de classes : sans JAR à authentifier, il tolère les clients de développement.
            Files.writeString(state.configPath, IntegrityStores.json.encodeToString(ServerConfig(allowDevelopmentClients = true)))
            state.reload()
            helper.assertTrue(state.config.allowDevelopmentClients, "Le rechargement doit tolérer les clients de développement")
            state.activate("gametest")
            helper.assertTrue(state.config.activeReference == "gametest", "L'activation doit être persistée")
            state.begin(first, { firstChallenge = it.challenge }, { accepted = true })
            helper.assertTrue(!accepted, "La connexion doit attendre sa réponse")
            val challenge = requireNotNull(firstChallenge)
            val report = clientReport(game, identity)
            ControlWire.split(challenge.id, SecureExchange.seal(challenge, report)).forEach { state.receive(first, it) }
        }.thenWaitUntil {
            helper.assertTrue(accepted, "Le client conforme doit être accepté après comparaison")
        }.thenExecute {
            helper.assertTrue(!firstConnection.rejected, "Le client conforme ne doit pas être déconnecté")
            state.disconnected(first)
            Files.writeString(game.resolve("resourcepacks/pack.zip"), "modified")
            helper.assertTrue(state.reference!!.report.entries.single().sha256 != IntegrityService.scan(game, identity).entries.single().sha256,
                "La copie modifiée ne doit pas changer la référence active")
            state.begin(second, { secondChallenge = it.challenge }, { wronglyAccepted = true })
            val challenge = requireNotNull(secondChallenge)
            helper.assertTrue(challenge.id != firstChallenge!!.id, "Le même UUID reconnecté doit recevoir un nouveau contrôle")
            ControlWire.split(challenge.id, SecureExchange.seal(challenge, clientReport(game, identity))).forEach { state.receive(second, it) }
        }.thenWaitUntil {
            helper.assertTrue(secondConnection.rejected, "La reconnexion avec un resourcepack modifié doit être refusée")
        }.thenExecute {
            helper.assertTrue(!wronglyAccepted, "Un refus ne doit pas terminer la tâche de connexion")
            state.disconnected(second)
        }.thenSucceed()
    }

    /** Le rapport d'un client de production conforme : l'inventaire du dossier, et un relevé d'exécution qui satisfait aux attentes du serveur. */
    private fun clientReport(game: Path, identity: PackIdentity): InventoryReport {
        val expectations = ServerExpectations.current
        val inventory = IntegrityService.scan(game, identity)
        val mods = listOf(LoadedMod("minecraft", identity.minecraftVersion, OriginKind.PATH, listOf("versions/${identity.minecraftVersion}.jar"))) +
            inventory.entries.filter { it.category == Category.MODS && it.modId != null }.map { LoadedMod(it.modId!!, it.modVersion!!, OriginKind.PATH, listOf(it.path)) }
        return inventory.copy(runtime = RuntimeReport(
            integrityVersion = requireNotNull(expectations.integrityVersion) { "integrity-game.properties is incomplete: $expectations" },
            protocolVersion = ControlWire.VERSION,
            loaderVersion = "0.19.5",
            minecraftVersion = identity.minecraftVersion,
            developmentEnvironment = false,
            gameJars = listOf(GameJar("versions/${identity.minecraftVersion}.jar", requireNotNull(expectations.clientSha1) { "integrity-game.properties is incomplete: $expectations" }, 1)),
            mods = mods,
            resourcePackDirectory = "resourcepacks",
            preLaunch = PreLaunchSnapshot(mods),
        ))
    }

    /** Capture le refus sans ouvrir de port ni lancer un client graphique. */
    private class TestConnection : Connection(PacketFlow.SERVERBOUND) {
        var rejected = false
        override fun isConnected(): Boolean = !rejected
        override fun isConnecting(): Boolean = false
        override fun isMemoryConnection(): Boolean = true
        override fun getRemoteAddress() = InetSocketAddress("127.0.0.1", 0)
        override fun send(packet: Packet<*>) { if (packet is ClientboundDisconnectPacket) rejected = true }
        override fun send(packet: Packet<*>, listener: ChannelFutureListener?) = send(packet)
        override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) = send(packet)
        override fun setReadOnly() {}
        override fun handleDisconnection() {}
    }
}
