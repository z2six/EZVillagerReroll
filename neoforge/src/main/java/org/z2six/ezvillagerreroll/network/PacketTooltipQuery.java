// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/PacketTooltipQuery.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public record PacketTooltipQuery(int traderEntityId) implements CustomPacketPayload {

    public static final Type<PacketTooltipQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EZVillagerReroll.MODID, "tooltip_query"));

    public static final StreamCodec<FriendlyByteBuf, PacketTooltipQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketTooltipQuery::traderEntityId,
                    PacketTooltipQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
