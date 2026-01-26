package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: detailed action data for a specific taught action.
 */
public record PacketCcActionDetailData(int villagerEntityId, int index, CompoundTag data) implements CustomPacketPayload {

    public static final Type<PacketCcActionDetailData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_action_detail_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcActionDetailData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeVarInt(msg.index());
                        buf.writeNbt(msg.data() == null ? new CompoundTag() : msg.data());
                    },
                    (buf) -> {
                        int id = 0;
                        int idx = 0;
                        CompoundTag tag = new CompoundTag();
                        try {
                            id = buf.readVarInt();
                            idx = buf.readVarInt();
                            tag = buf.readNbt();
                            if (tag == null) tag = new CompoundTag();
                        } catch (Throwable ignored) {
                            tag = new CompoundTag();
                        }
                        return new PacketCcActionDetailData(id, idx, tag);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

