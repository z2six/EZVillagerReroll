package org.z2six.villageroverhaul.network.chatcommands;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: returns per-player chat-command configuration.
 */
public record PacketPlayerChatCommandsData(CompoundTag data) implements CustomPacketPayload {
    public static final Type<PacketPlayerChatCommandsData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pcmd_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPlayerChatCommandsData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeNbt(msg.data() == null ? new CompoundTag() : msg.data()),
                    (buf) -> {
                        CompoundTag tag = buf.readNbt();
                        if (tag == null) tag = new CompoundTag();
                        return new PacketPlayerChatCommandsData(tag);
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

