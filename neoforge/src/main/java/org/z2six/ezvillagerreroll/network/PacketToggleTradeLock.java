// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketToggleTradeLock.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketToggleTradeLock(int traderEntityId, int tradeIndex) implements CustomPacketPayload {

    public static final Type<PacketToggleTradeLock> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "toggle_trade_lock"));

    public static final StreamCodec<FriendlyByteBuf, PacketToggleTradeLock> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketToggleTradeLock decode(FriendlyByteBuf buf) {
            int traderId = buf.readVarInt();
            int idx = buf.readVarInt();
            return new PacketToggleTradeLock(traderId, idx);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketToggleTradeLock p) {
            buf.writeVarInt(p.traderEntityId());
            buf.writeVarInt(p.tradeIndex());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
