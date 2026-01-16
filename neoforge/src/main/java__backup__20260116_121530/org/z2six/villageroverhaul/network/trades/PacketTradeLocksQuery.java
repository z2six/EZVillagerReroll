// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketTradeLocksQuery.java
package org.z2six.villageroverhaul.network.trades;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketTradeLocksQuery() implements CustomPacketPayload {

    public static final Type<PacketTradeLocksQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocksQuery> STREAM_CODEC =
            StreamCodec.unit(new PacketTradeLocksQuery());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
