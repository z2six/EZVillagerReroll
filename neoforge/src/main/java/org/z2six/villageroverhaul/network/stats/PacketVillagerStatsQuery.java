// neoforge\src\main\java\org\z2six\villageroverhaul\network\stats\PacketVillagerStatsQuery.java
package org.z2six.villageroverhaul.network.stats;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server request for villager stats for a specific entityId.
 */
public record PacketVillagerStatsQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerStatsQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_stats_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerStatsQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerStatsQuery::villagerEntityId,
                    PacketVillagerStatsQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
