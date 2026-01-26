package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: cancel the current "waiting for target" recording step.
 */
public record PacketCcCancelRecord(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketCcCancelRecord> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_cancel_record"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcCancelRecord> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeVarInt(msg.villagerEntityId()),
                    (buf) -> new PacketCcCancelRecord(buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

