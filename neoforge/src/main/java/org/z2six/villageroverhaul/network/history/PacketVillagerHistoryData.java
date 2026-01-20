// neoforge/src/main/java/org/z2six/villageroverhaul/network/history/PacketVillagerHistoryData.java
package org.z2six.villageroverhaul.network.history;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> Client history snapshot for the villager.
 *
 * ok=false means server couldn't resolve/validate the entity.
 */
public record PacketVillagerHistoryData(
        int villagerEntityId,
        boolean ok,
        long ticksAlive,
        long distanceMilliBlocks,
        int foodEaten,
        float foodHealTotal,
        int blocksSuccessful,
        int hitsTaken,
        float damageTakenTotal,
        int hitsDealt,
        float damageDealtTotal,
        int kills,
        int manualRerolls,
        int autoRerolls,
        int tradeLocksToggled,
        int patrolRoutesRecorded,
        int merchantMenuOpens,
        int tradesCompleted
) implements CustomPacketPayload {

    public static final Type<PacketVillagerHistoryData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_history_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerHistoryData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerHistoryData decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    boolean ok = false;
                    long ticksAlive = 0L;
                    long dist = 0L;
                    int foodEaten = 0;
                    float heal = 0.0f;
                    int blocks = 0;
                    int hitsTaken = 0;
                    float damageTakenTotal = 0.0f;
                    int hitsDealt = 0;
                    float damageDealtTotal = 0.0f;
                    int kills = 0;
                    int manualRerolls = 0;
                    int autoRerolls = 0;
                    int tradeLocksToggled = 0;
                    int patrolRoutesRecorded = 0;
                    int opens = 0;
                    int trades = 0;

                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { ok = buf.readBoolean(); } catch (Throwable ignored) {}

                    try { ticksAlive = buf.readLong(); } catch (Throwable ignored) {}
                    try { dist = buf.readLong(); } catch (Throwable ignored) {}
                    try { foodEaten = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { heal = buf.readFloat(); } catch (Throwable ignored) {}
                    try { blocks = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { hitsTaken = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { damageTakenTotal = buf.readFloat(); } catch (Throwable ignored) {}
                    try { hitsDealt = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { damageDealtTotal = buf.readFloat(); } catch (Throwable ignored) {}
                    try { kills = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { manualRerolls = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { autoRerolls = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { tradeLocksToggled = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { patrolRoutesRecorded = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { opens = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { trades = buf.readVarInt(); } catch (Throwable ignored) {}

                    return new PacketVillagerHistoryData(id, ok, ticksAlive, dist, foodEaten, heal, blocks, hitsTaken, damageTakenTotal, hitsDealt, damageDealtTotal, kills, manualRerolls, autoRerolls, tradeLocksToggled, patrolRoutesRecorded, opens, trades);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerHistoryData d) {
                    buf.writeVarInt(d.villagerEntityId());
                    buf.writeBoolean(d.ok());

                    buf.writeLong(d.ticksAlive());
                    buf.writeLong(d.distanceMilliBlocks());
                    buf.writeVarInt(d.foodEaten());
                    buf.writeFloat(d.foodHealTotal());
                    buf.writeVarInt(d.blocksSuccessful());
                    buf.writeVarInt(d.hitsTaken());
                    buf.writeFloat(d.damageTakenTotal());
                    buf.writeVarInt(d.hitsDealt());
                    buf.writeFloat(d.damageDealtTotal());
                    buf.writeVarInt(d.kills());
                    buf.writeVarInt(d.manualRerolls());
                    buf.writeVarInt(d.autoRerolls());
                    buf.writeVarInt(d.tradeLocksToggled());
                    buf.writeVarInt(d.patrolRoutesRecorded());
                    buf.writeVarInt(d.merchantMenuOpens());
                    buf.writeVarInt(d.tradesCompleted());
                }
            };

    public static PacketVillagerHistoryData missing(int entityId) {
        return new PacketVillagerHistoryData(entityId, false, 0L, 0L, 0, 0.0f, 0, 0, 0.0f, 0, 0.0f, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
