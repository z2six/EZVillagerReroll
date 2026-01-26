package org.z2six.villageroverhaul.network.chatcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: request current per-player chat-command configuration.
 */
public record PacketPlayerChatCommandsQuery() implements CustomPacketPayload {
    public static final Type<PacketPlayerChatCommandsQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pcmd_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPlayerChatCommandsQuery> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> {}, (buf) -> new PacketPlayerChatCommandsQuery());

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
