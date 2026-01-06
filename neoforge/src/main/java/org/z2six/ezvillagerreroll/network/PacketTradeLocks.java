// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTradeLocks.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketTradeLocks(int containerId, long mask) implements CustomPacketPayload {

    public static final Type<PacketTradeLocks> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks"));

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocks> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketTradeLocks decode(FriendlyByteBuf buf) {
            int cid = buf.readVarInt();
            long mask = buf.readLong();
            return new PacketTradeLocks(cid, mask);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketTradeLocks msg) {
            buf.writeVarInt(msg.containerId());
            buf.writeLong(msg.mask());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
