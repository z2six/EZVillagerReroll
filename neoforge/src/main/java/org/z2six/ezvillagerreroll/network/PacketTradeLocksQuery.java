// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketTradeLocksQuery.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

public record PacketTradeLocksQuery(int traderEntityId) implements CustomPacketPayload {

    public static final Type<PacketTradeLocksQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "trade_locks_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketTradeLocksQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketTradeLocksQuery::traderEntityId,
                    PacketTradeLocksQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
