// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketDeclineAutoSearchSettlement.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketDeclineAutoSearchSettlement(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketDeclineAutoSearchSettlement> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "decline_auto_search_settlement"));

    public static final StreamCodec<FriendlyByteBuf, PacketDeclineAutoSearchSettlement> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketDeclineAutoSearchSettlement decode(FriendlyByteBuf buf) {
            return new PacketDeclineAutoSearchSettlement(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketDeclineAutoSearchSettlement msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
