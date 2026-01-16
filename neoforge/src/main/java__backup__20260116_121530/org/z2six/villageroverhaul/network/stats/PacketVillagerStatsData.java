package org.z2six.villageroverhaul.network.stats;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> Client snapshot of villager stats.
 *
 * ok=false means the server could not resolve/validate the entity (or stats missing).
 * The client should treat that as "unavailable" (not "syncing forever").
 *
 * NOTE: We use a custom StreamCodec (manual read with try/catch) so older/shorter payloads
 * won't hard-crash decoding (fields will default to 0).
 */
public record PacketVillagerStatsData(
        int villagerEntityId,
        boolean ok,
        int generosity,
        int timeliness,
        int intellect,
        int hoarder,
        int vitality,
        int agility,
        int strength,
        int armor
) implements CustomPacketPayload {

    public static final Type<PacketVillagerStatsData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_stats_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerStatsData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerStatsData decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    boolean ok = false;

                    int g = 0, t = 0, i = 0, h = 0;
                    int v = 0, a = 0, s = 0, ar = 0;

                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { ok = buf.readBoolean(); } catch (Throwable ignored) {}

                    try { g = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { t = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { i = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { h = buf.readVarInt(); } catch (Throwable ignored) {}

                    // New fields (safe-read)
                    try { v = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { a = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { s = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { ar = buf.readVarInt(); } catch (Throwable ignored) {}

                    return new PacketVillagerStatsData(id, ok, g, t, i, h, v, a, s, ar);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerStatsData d) {
                    buf.writeVarInt(d.villagerEntityId());
                    buf.writeBoolean(d.ok());

                    buf.writeVarInt(d.generosity());
                    buf.writeVarInt(d.timeliness());
                    buf.writeVarInt(d.intellect());
                    buf.writeVarInt(d.hoarder());

                    buf.writeVarInt(d.vitality());
                    buf.writeVarInt(d.agility());
                    buf.writeVarInt(d.strength());
                    buf.writeVarInt(d.armor());
                }
            };

    public static PacketVillagerStatsData missing(int entityId) {
        return new PacketVillagerStatsData(entityId, false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
