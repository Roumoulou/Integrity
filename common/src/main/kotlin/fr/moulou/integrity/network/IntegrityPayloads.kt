package fr.moulou.integrity.network

import fr.moulou.integrity.model.ControlChallenge
import fr.moulou.integrity.model.UploadChunk
import fr.moulou.integrity.protocol.ControlWire
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class ChallengePayload(val challenge: ControlChallenge) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ChallengePayload>(Identifier.fromNamespaceAndPath("integrity", "v2/challenge"))
        val CODEC: StreamCodec<FriendlyByteBuf, ChallengePayload> = StreamCodec.of(
            { buffer, value -> buffer.writeByteArray(ControlWire.encodeChallenge(value.challenge)) },
            { buffer -> ChallengePayload(ControlWire.decodeChallenge(buffer.readByteArray(4096))) },
        )
    }
    override fun type(): CustomPacketPayload.Type<ChallengePayload> = TYPE
}

data class ChunkPayload(val chunk: UploadChunk) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ChunkPayload>(Identifier.fromNamespaceAndPath("integrity", "v2/response"))
        val CODEC: StreamCodec<FriendlyByteBuf, ChunkPayload> = StreamCodec.of(
            { buffer, value -> buffer.writeByteArray(ControlWire.encodeChunk(value.chunk)) },
            { buffer -> ChunkPayload(ControlWire.decodeChunk(buffer.readByteArray(ControlWire.CHUNK_BYTES + 32))) },
        )
    }
    override fun type(): CustomPacketPayload.Type<ChunkPayload> = TYPE
}

/** Les canaux du protocole `2` ; ils changent de nom avec le protocole, pour qu'un client trop ancien se reconnaisse à ce qu'il annonce. */
object IntegrityPayloads {
    /** Le canal du challenge de la `1.0.x` : un client qui ne connaît que lui est trop ancien, et le serveur le lui dit. */
    val LEGACY_CHALLENGE: Identifier = Identifier.fromNamespaceAndPath("integrity", "challenge")

    fun register() {
        PayloadTypeRegistry.clientboundConfiguration().register(ChallengePayload.TYPE, ChallengePayload.CODEC)
        PayloadTypeRegistry.serverboundConfiguration().register(ChunkPayload.TYPE, ChunkPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ChallengePayload.TYPE, ChallengePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ChunkPayload.TYPE, ChunkPayload.CODEC)
    }
}
