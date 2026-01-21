// neoforge\src\main\java\org\z2six\villageroverhaul\network\autotrade\PacketAutoTradeState.java
package org.z2six.villageroverhaul.network.autotrade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: informs client of auto-trade status for a container.
 */
public record PacketAutoTradeState(int containerId, boolean active, String reason) implements CustomPacketPayload {

    public static final Type<PacketAutoTradeState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_trade_state"));

    private static final int REASON_MAX = 128;

    public static final StreamCodec<FriendlyByteBuf, PacketAutoTradeState> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketAutoTradeState::containerId,
                    ByteBufCodecs.BOOL, PacketAutoTradeState::active,
                    ByteBufCodecs.stringUtf8(REASON_MAX), p -> p.reason() == null ? "" : p.reason(),
                    PacketAutoTradeState::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

