package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: add a WAIT step (seconds) to the current teaching session.
 */
public record PacketCcAddWaitStep(
        int villagerEntityId,
        float seconds
) implements CustomPacketPayload {

    public static final Type<PacketCcAddWaitStep> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_add_wait"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcAddWaitStep> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeFloat(msg.seconds());
                    },
                    (buf) -> new PacketCcAddWaitStep(
                            buf.readVarInt(),
                            buf.readFloat()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

