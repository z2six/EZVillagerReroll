package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: start a teaching session for a villager.
 *
 * editIndex:
 *  - -1 => new taught action
 *  - >=0 => reteach existing action (overwrite)
 */
public record PacketCcBeginTeaching(int villagerEntityId, int editIndex) implements CustomPacketPayload {

    public static final Type<PacketCcBeginTeaching> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_begin_teaching"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcBeginTeaching> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.editIndex());
                    },
                    (buf) -> new PacketCcBeginTeaching(buf.readVarInt(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

