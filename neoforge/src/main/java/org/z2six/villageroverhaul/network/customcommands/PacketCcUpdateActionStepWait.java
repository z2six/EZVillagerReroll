package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: update an existing WAIT step (seconds) inside a saved teaching action.
 */
public record PacketCcUpdateActionStepWait(
        int villagerEntityId,
        int actionIndex,
        int stepIndex,
        float seconds
) implements CustomPacketPayload {

    public static final Type<PacketCcUpdateActionStepWait> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_update_step_wait"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcUpdateActionStepWait> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.actionIndex());
                        buf.writeVarInt(msg.stepIndex());
                        buf.writeFloat(msg.seconds());
                    },
                    (buf) -> new PacketCcUpdateActionStepWait(
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readFloat()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

