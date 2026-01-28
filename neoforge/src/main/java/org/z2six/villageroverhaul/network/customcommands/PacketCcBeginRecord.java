package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: begin waiting for the next interaction target to record.
 *
 * kind:
 *  0 = interact (block/entity)
 *  1 = withdraw chest
 *  2 = deposit chest
 *  3 = look (yaw/pitch)
 */
public record PacketCcBeginRecord(int villagerEntityId, int kind) implements CustomPacketPayload {

    public static final Type<PacketCcBeginRecord> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_begin_record"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcBeginRecord> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.kind());
                    },
                    (buf) -> new PacketCcBeginRecord(buf.readVarInt(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
