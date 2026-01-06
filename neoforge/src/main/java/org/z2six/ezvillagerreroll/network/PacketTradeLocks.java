// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTradeLocks.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public final class PacketTradeLocks implements CustomPacketPayload {

    public static final Type<PacketTradeLocks> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks"));

    public int traderEntityId;
    public long mask;

    public PacketTradeLocks() {}

    public PacketTradeLocks(int traderEntityId, long mask) {
        this.traderEntityId = traderEntityId;
        this.mask = mask;
    }

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocks> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketTradeLocks decode(FriendlyByteBuf buf) {
            PacketTradeLocks p = new PacketTradeLocks();
            p.traderEntityId = buf.readVarInt();
            p.mask = buf.readLong();
            return p;
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketTradeLocks p) {
            buf.writeVarInt(p.traderEntityId);
            buf.writeLong(p.mask);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
