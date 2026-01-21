// neoforge\src\main\java\org\z2six\villageroverhaul\network\autotrade\PacketAutoTradeStop.java
package org.z2six.villageroverhaul.network.autotrade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: stop the current auto-trade loop.
 */
public record PacketAutoTradeStop(int containerId) implements CustomPacketPayload {

    public static final Type<PacketAutoTradeStop> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_trade_stop"));

    public static final StreamCodec<FriendlyByteBuf, PacketAutoTradeStop> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketAutoTradeStop::containerId,
                    PacketAutoTradeStop::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

