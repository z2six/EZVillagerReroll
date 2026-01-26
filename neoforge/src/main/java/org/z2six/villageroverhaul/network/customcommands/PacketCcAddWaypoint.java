package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: add a waypoint step (player's current block center).
 */
public record PacketCcAddWaypoint(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCcAddWaypoint> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_add_waypoint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcAddWaypoint> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.villagerEntityId()),
                    (buf) -> new PacketCcAddWaypoint(buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

