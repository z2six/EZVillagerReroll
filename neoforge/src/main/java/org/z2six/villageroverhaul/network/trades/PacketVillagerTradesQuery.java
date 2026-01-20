// neoforge/src/main/java/org/z2six/villageroverhaul/network/trades/PacketVillagerTradesQuery.java
package org.z2six.villageroverhaul.network.trades;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server request for a villager trades snapshot (results + lock mask).
 */
public record PacketVillagerTradesQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerTradesQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_trades_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerTradesQuery> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerTradesQuery decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    return new PacketVillagerTradesQuery(id);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerTradesQuery msg) {
                    buf.writeVarInt(msg.villagerEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

