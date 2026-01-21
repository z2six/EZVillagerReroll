// neoforge\src\main\java\org\z2six\villageroverhaul\network\autotrade\PacketAutoTradeStart.java
package org.z2six.villageroverhaul.network.autotrade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Client -> Server: start an auto-trade loop for the currently open MerchantMenu.
 */
public record PacketAutoTradeStart(int containerId, int offerIndex) implements CustomPacketPayload {

    public static final Type<PacketAutoTradeStart> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_trade_start"));

    public static final StreamCodec<FriendlyByteBuf, PacketAutoTradeStart> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketAutoTradeStart::containerId,
                    ByteBufCodecs.VAR_INT, PacketAutoTradeStart::offerIndex,
                    PacketAutoTradeStart::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

