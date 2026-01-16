// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketAutoSearchSettlementCleared.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

/**
 * Server -> Client: settlement ended (paid or declined) so any payment UI should close.
 */
public record PacketAutoSearchSettlementCleared(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketAutoSearchSettlementCleared> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "auto_search_settlement_cleared"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAutoSearchSettlementCleared> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketAutoSearchSettlementCleared decode(RegistryFriendlyByteBuf buf) {
            return new PacketAutoSearchSettlementCleared(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketAutoSearchSettlementCleared msg) {
            buf.writeVarInt(msg.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
