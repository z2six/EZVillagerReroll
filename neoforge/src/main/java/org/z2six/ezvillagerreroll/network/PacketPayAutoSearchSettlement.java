// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketPayAutoSearchSettlement.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketPayAutoSearchSettlement(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketPayAutoSearchSettlement> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "pay_auto_search_settlement"));

    public static final StreamCodec<FriendlyByteBuf, PacketPayAutoSearchSettlement> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketPayAutoSearchSettlement decode(FriendlyByteBuf buf) {
            return new PacketPayAutoSearchSettlement(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketPayAutoSearchSettlement msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
