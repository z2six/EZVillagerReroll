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
        int tradesCompleted,
        int deaths,
        long emeraldsFromManualRerolls,
        long emeraldsFromAutoRerolls,
        long emeraldsFromTrades,
        long farmPlantedNeutral,
        long farmPlantedManual,
        long farmHarvestedNeutral,
        long farmHarvestedManual,
        long farmBonemealedNeutral,
        long farmBonemealedManual,
        long farmWithdrawnItemsNeutral,
        long farmWithdrawnItemsManual,
        long farmDepositedItemsNeutral,
        long farmDepositedItemsManual
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
                    int deaths = 0;

                    long emeraldsManual = 0L;
                    long emeraldsAuto = 0L;
                    long emeraldsTrades = 0L;

                    long plantedNeutral = 0L;
                    long plantedManual = 0L;
                    long harvestedNeutral = 0L;
                    long harvestedManual = 0L;
                    long bonemealedNeutral = 0L;
                    long bonemealedManual = 0L;
                    long withdrawnNeutral = 0L;
                    long withdrawnManual = 0L;
                    long depositedNeutral = 0L;
                    long depositedManual = 0L;

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
                    try { deaths = buf.readVarInt(); } catch (Throwable ignored) {}

                    try { emeraldsManual = buf.readLong(); } catch (Throwable ignored) {}
                    try { emeraldsAuto = buf.readLong(); } catch (Throwable ignored) {}
                    try { emeraldsTrades = buf.readLong(); } catch (Throwable ignored) {}

                    try { plantedNeutral = buf.readLong(); } catch (Throwable ignored) {}
                    try { plantedManual = buf.readLong(); } catch (Throwable ignored) {}
                    try { harvestedNeutral = buf.readLong(); } catch (Throwable ignored) {}
                    try { harvestedManual = buf.readLong(); } catch (Throwable ignored) {}
                    try { bonemealedNeutral = buf.readLong(); } catch (Throwable ignored) {}
                    try { bonemealedManual = buf.readLong(); } catch (Throwable ignored) {}
                    try { withdrawnNeutral = buf.readLong(); } catch (Throwable ignored) {}
                    try { withdrawnManual = buf.readLong(); } catch (Throwable ignored) {}
                    try { depositedNeutral = buf.readLong(); } catch (Throwable ignored) {}
                    try { depositedManual = buf.readLong(); } catch (Throwable ignored) {}

                    return new PacketVillagerHistoryData(
                            id, ok,
                            ticksAlive, dist,
                            foodEaten, heal,
                            blocks, hitsTaken, damageTakenTotal,
                            hitsDealt, damageDealtTotal,
                            kills,
                            manualRerolls, autoRerolls,
                            tradeLocksToggled, patrolRoutesRecorded,
                            opens, trades, deaths,
                            emeraldsManual, emeraldsAuto, emeraldsTrades,
                            plantedNeutral, plantedManual,
                            harvestedNeutral, harvestedManual,
                            bonemealedNeutral, bonemealedManual,
                            withdrawnNeutral, withdrawnManual,
                            depositedNeutral, depositedManual
                    );
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
                    buf.writeVarInt(d.deaths());

                    buf.writeLong(d.emeraldsFromManualRerolls());
                    buf.writeLong(d.emeraldsFromAutoRerolls());
                    buf.writeLong(d.emeraldsFromTrades());

                    buf.writeLong(d.farmPlantedNeutral());
                    buf.writeLong(d.farmPlantedManual());
                    buf.writeLong(d.farmHarvestedNeutral());
                    buf.writeLong(d.farmHarvestedManual());
                    buf.writeLong(d.farmBonemealedNeutral());
                    buf.writeLong(d.farmBonemealedManual());
                    buf.writeLong(d.farmWithdrawnItemsNeutral());
                    buf.writeLong(d.farmWithdrawnItemsManual());
                    buf.writeLong(d.farmDepositedItemsNeutral());
                    buf.writeLong(d.farmDepositedItemsManual());
                }
            };

    public static PacketVillagerHistoryData missing(int entityId) {
        return new PacketVillagerHistoryData(
                entityId, false,
                0L, 0L,
                0, 0.0f,
                0, 0, 0.0f,
                0, 0.0f,
                0,
                0, 0,
                0, 0,
                0, 0, 0,
                0L, 0L, 0L,
                0L, 0L,
                0L, 0L,
                0L, 0L,
                0L, 0L,
                0L, 0L
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
