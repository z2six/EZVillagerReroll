package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: request list of taught actions for villager.
 */
public record PacketCcListQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCcListQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_list_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcListQuery> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.villagerEntityId()),
                    (buf) -> new PacketCcListQuery(buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

