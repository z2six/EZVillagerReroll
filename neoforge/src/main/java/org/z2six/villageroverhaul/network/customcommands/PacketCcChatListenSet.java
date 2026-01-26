package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: set whether a villager listens to chat triggers (macro + module chat commands).
 */
public record PacketCcChatListenSet(int villagerEntityId, boolean listen, boolean pass, int passRange) implements CustomPacketPayload {
    public static final Type<PacketCcChatListenSet> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_listen_s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcChatListenSet> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeBoolean(msg.listen());
                        buf.writeBoolean(msg.pass());
                        buf.writeVarInt(msg.passRange());
                    },
                    (buf) -> new PacketCcChatListenSet(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
