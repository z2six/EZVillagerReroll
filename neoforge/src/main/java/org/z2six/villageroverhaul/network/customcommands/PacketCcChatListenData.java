package org.z2six.villageroverhaul.network.customcommands;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: response to {@link PacketCcChatListenQuery}.
 */
public record PacketCcChatListenData(int villagerEntityId, boolean listen, boolean pass, int passRange) implements CustomPacketPayload {
    public static final Type<PacketCcChatListenData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cc_listen_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCcChatListenData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        buf.writeBoolean(msg.listen());
                        buf.writeBoolean(msg.pass());
                        buf.writeVarInt(msg.passRange());
                    },
                    (buf) -> new PacketCcChatListenData(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
