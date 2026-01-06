// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketToggleTradeLock.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.ezvillagerreroll.Constants;

/**
 * Client -> Server: toggle lock for a trade index on the CURRENTLY OPEN MerchantMenu.
 *
 * We intentionally do NOT include a trader entity id because on the client the merchant can be
 * a client-side merchant implementation (no backing entity).
 *
 * The server resolves the villager from the player's currently open MerchantMenu.
 */
public record PacketToggleTradeLock(int tradeIndex) implements CustomPacketPayload {

    public static final Type<PacketToggleTradeLock> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "toggle_trade_lock"));

    public static final StreamCodec<FriendlyByteBuf, PacketToggleTradeLock> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketToggleTradeLock::tradeIndex,
                    PacketToggleTradeLock::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
