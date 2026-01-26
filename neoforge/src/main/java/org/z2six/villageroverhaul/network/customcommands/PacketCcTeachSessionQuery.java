package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: request current teaching session data for the villager.
 */
public record PacketCcTeachSessionQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCcTeachSessionQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_teach_session_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcTeachSessionQuery> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.villagerEntityId()),
                    (buf) -> new PacketCcTeachSessionQuery(buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

