package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: set duration seconds for a LOOK step in the current teaching session.
 */
public record PacketCcSetLookDuration(int villagerEntityId, int stepIndex, float seconds) implements CustomPacketPayload {

    public static final Type<PacketCcSetLookDuration> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_set_look_dur"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcSetLookDuration> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.stepIndex());
                        buf.writeFloat(msg.seconds());
                    },
                    (buf) -> new PacketCcSetLookDuration(buf.readVarInt(), buf.readVarInt(), buf.readFloat())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

