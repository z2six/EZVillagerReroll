package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: tells the client whether we're waiting for a record target,
 * so ESC can cancel.
 */
public record PacketCcWaitState(int villagerEntityId, boolean waiting) implements CustomPacketPayload {

    public static final Type<PacketCcWaitState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_wait_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcWaitState> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeBoolean(msg.waiting());
                    },
                    (buf) -> new PacketCcWaitState(buf.readVarInt(), buf.readBoolean())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

