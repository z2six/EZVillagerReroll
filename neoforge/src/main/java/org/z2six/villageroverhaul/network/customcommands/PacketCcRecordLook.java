package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: record a LOOK step (yaw + pitch) for the current teaching session.
 */
public record PacketCcRecordLook(int villagerEntityId, float yaw, float pitch) implements CustomPacketPayload {

    public static final Type<PacketCcRecordLook> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_record_look"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcRecordLook> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeFloat(msg.yaw());
                        buf.writeFloat(msg.pitch());
                    },
                    (buf) -> new PacketCcRecordLook(buf.readVarInt(), buf.readFloat(), buf.readFloat())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

