// PacketVillagerStatsData.java
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/network/PacketVillagerStatsData.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> Client snapshot of villager stats.
 *
 * ok=false means the server could not resolve/validate the entity (or stats missing).
 * The client should treat that as "unavailable" (not "syncing forever").
 */
public record PacketVillagerStatsData(
        int villagerEntityId,
        boolean ok,
        int generosity,
        int timeliness,
        int intellect,
        int hoarder,
        int ambitious
) implements CustomPacketPayload {

    public static final Type<PacketVillagerStatsData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("ezvillagerreroll", "villager_stats_data"));

    /**
     * StreamCodec.composite in this environment only supports up to 6 fields,
     * so we bundle the 5 stats into a nested record and compose (id, ok, stats).
     */
    private record StatsBundle(int generosity, int timeliness, int intellect, int hoarder, int ambitious) {}

    private static final StreamCodec<RegistryFriendlyByteBuf, StatsBundle> STATS_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StatsBundle::generosity,
                    ByteBufCodecs.VAR_INT, StatsBundle::timeliness,
                    ByteBufCodecs.VAR_INT, StatsBundle::intellect,
                    ByteBufCodecs.VAR_INT, StatsBundle::hoarder,
                    ByteBufCodecs.VAR_INT, StatsBundle::ambitious,
                    StatsBundle::new
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerStatsData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerStatsData::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketVillagerStatsData::ok,
                    STATS_CODEC, d -> new StatsBundle(d.generosity(), d.timeliness(), d.intellect(), d.hoarder(), d.ambitious()),
                    (id, ok, s) -> new PacketVillagerStatsData(id, ok, s.generosity(), s.timeliness(), s.intellect(), s.hoarder(), s.ambitious())
            );

    public static PacketVillagerStatsData missing(int entityId) {
        return new PacketVillagerStatsData(entityId, false, 0, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
