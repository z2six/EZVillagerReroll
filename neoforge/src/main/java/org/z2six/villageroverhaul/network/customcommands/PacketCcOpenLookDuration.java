package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: prompt the player for how long a LOOK step should last (teaching session).
 */
public record PacketCcOpenLookDuration(int villagerEntityId, int stepIndex) implements CustomPacketPayload {

    public static final Type<PacketCcOpenLookDuration> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_open_look_dur"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcOpenLookDuration> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.stepIndex());
                    },
                    (buf) -> new PacketCcOpenLookDuration(buf.readVarInt(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

