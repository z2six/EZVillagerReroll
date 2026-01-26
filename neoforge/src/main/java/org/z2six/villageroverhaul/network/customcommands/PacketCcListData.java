package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: list data for Custom Commands UI.
 */
public record PacketCcListData(int villagerEntityId, CompoundTag data) implements CustomPacketPayload {

    public static final Type<PacketCcListData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_list_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcListData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeNbt(msg.data() == null ? new CompoundTag() : msg.data());
                    },
                    (buf) -> {
                        int id = 0;
                        CompoundTag tag = new CompoundTag();
                        try {
                            id = buf.readVarInt();
                            tag = buf.readNbt();
                            if (tag == null) tag = new CompoundTag();
                        } catch (Throwable ignored) {
                            tag = new CompoundTag();
                        }
                        return new PacketCcListData(id, tag);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

