package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: request details for a taught action.
 */
public record PacketCcActionDetailQuery(int villagerEntityId, int index) implements CustomPacketPayload {

    public static final Type<PacketCcActionDetailQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_action_detail_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcActionDetailQuery> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.index());
                    },
                    (buf) -> new PacketCcActionDetailQuery(buf.readVarInt(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

