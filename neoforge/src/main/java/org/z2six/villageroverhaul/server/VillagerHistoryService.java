// neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerHistoryService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.network.history.PacketVillagerHistoryData;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Server-side persistent history counters for recruited villagers.
 *
 * Stored on the villager's persistent NBT so it survives restarts.
 */
public final class VillagerHistoryService {
    private static final String TAG = "ezvr_history";

    private static final String K_TICKS_ALIVE = "ticks_alive";
    private static final String K_DISTANCE_MB = "distance_milliblocks";

    private static final String K_FOOD_EATEN = "food_eaten";
    private static final String K_FOOD_HEAL_TOTAL = "food_heal_total";

    private static final String K_BLOCKS = "blocks";
    private static final String K_HITS_TAKEN = "hits_taken";
    private static final String K_DAMAGE_TAKEN_TOTAL = "damage_taken_total";
    private static final String K_HITS_DEALT = "hits_dealt";
    private static final String K_DAMAGE_DEALT_TOTAL = "damage_dealt_total";
    private static final String K_KILLS = "kills";

    private static final String K_MANUAL_REROLLS = "manual_rerolls";
    private static final String K_AUTO_REROLLS = "auto_rerolls";
    private static final String K_TRADE_LOCK_TOGGLES = "trade_lock_toggles";
    private static final String K_PATROL_ROUTES_RECORDED = "patrol_routes_recorded";

    private static final String K_MERCHANT_MENU_OPENS = "merchant_menu_opens";
    private static final String K_TRADES_COMPLETED = "trades_completed";
    private static final String K_DEATHS = "deaths";

    // Trading economics (emeralds)
    private static final String K_EMERALDS_FROM_MANUAL_REROLLS = "emeralds_manual_rerolls";
    private static final String K_EMERALDS_FROM_AUTO_REROLLS = "emeralds_auto_rerolls";
    private static final String K_EMERALDS_FROM_TRADES = "emeralds_trades";

    // Farming counters (split by NEUTRAL/vanilla vs MANUAL mode)
    private static final String K_FARM_PLANTED_NEUTRAL = "farm_planted_neutral";
    private static final String K_FARM_PLANTED_MANUAL = "farm_planted_manual";
    private static final String K_FARM_HARVESTED_NEUTRAL = "farm_harvested_neutral";
    private static final String K_FARM_HARVESTED_MANUAL = "farm_harvested_manual";
    private static final String K_FARM_BONEMEALED_NEUTRAL = "farm_bonemealed_neutral";
    private static final String K_FARM_BONEMEALED_MANUAL = "farm_bonemealed_manual";
    private static final String K_FARM_DEPOSITED_ITEMS_NEUTRAL = "farm_deposited_items_neutral";
    private static final String K_FARM_DEPOSITED_ITEMS_MANUAL = "farm_deposited_items_manual";
    private static final String K_FARM_WITHDRAWN_ITEMS_NEUTRAL = "farm_withdrawn_items_neutral";
    private static final String K_FARM_WITHDRAWN_ITEMS_MANUAL = "farm_withdrawn_items_manual";

    private static final String K_LAST_X = "last_x";
    private static final String K_LAST_Y = "last_y";
    private static final String K_LAST_Z = "last_z";
    private static final String K_LAST_POS_VALID = "last_pos_valid";
    private static final String K_LAST_HEALTH = "last_health";
    private static final String K_LAST_HEALTH_VALID = "last_health_valid";

    private static final Set<Villager> TRACKED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private VillagerHistoryService() {}

    public static void track(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            TRACKED.add(vill);
        } catch (Throwable ignored) {}
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (TRACKED.isEmpty()) return;

            Iterator<Villager> it = TRACKED.iterator();
            while (it.hasNext()) {
                Villager vill = it.next();
                if (vill == null || vill.level() == null || vill.level().isClientSide() || !vill.isAlive()) {
                    it.remove();
                    continue;
                }
                if (!RecruitService.isRecruited(vill)) continue;
                tickOne(vill);
            }
        } catch (Throwable ignored) {}
    }

    private static void tickOne(Villager vill) {
        try {
            CompoundTag root = getOrCreate(vill);
            root.putLong(K_TICKS_ALIVE, safeLong(root.getLong(K_TICKS_ALIVE)) + 1L);

            // Hits/damage taken (actual health reduction only; blocked hits are tracked separately via "blocks").
            float curHp = vill.getHealth();
            if (root.getBoolean(K_LAST_HEALTH_VALID)) {
                float lastHp = root.getFloat(K_LAST_HEALTH);
                float delta = lastHp - curHp;
                if (delta > 0.001f) {
                    root.putInt(K_HITS_TAKEN, safeInt(root.getInt(K_HITS_TAKEN)) + 1);
                    root.putFloat(K_DAMAGE_TAKEN_TOTAL, root.getFloat(K_DAMAGE_TAKEN_TOTAL) + delta);
                }
            } else {
                root.putBoolean(K_LAST_HEALTH_VALID, true);
            }
            root.putFloat(K_LAST_HEALTH, curHp);

            boolean valid = root.getBoolean(K_LAST_POS_VALID);
            double x = vill.getX();
            double y = vill.getY();
            double z = vill.getZ();

            if (!valid) {
                root.putDouble(K_LAST_X, x);
                root.putDouble(K_LAST_Y, y);
                root.putDouble(K_LAST_Z, z);
                root.putBoolean(K_LAST_POS_VALID, true);
                return;
            }

            double lx = root.getDouble(K_LAST_X);
            double ly = root.getDouble(K_LAST_Y);
            double lz = root.getDouble(K_LAST_Z);

            // Horizontal distance is a better "travel" metric for UI purposes.
            double dx = x - lx;
            double dz = z - lz;
            double dist = Math.sqrt(dx * dx + dz * dz);

            long add = (long) Math.floor(dist * 1000.0); // milliblocks
            if (add > 0) {
                root.putLong(K_DISTANCE_MB, safeLong(root.getLong(K_DISTANCE_MB)) + add);
            }

            root.putDouble(K_LAST_X, x);
            root.putDouble(K_LAST_Y, y);
            root.putDouble(K_LAST_Z, z);
        } catch (Throwable ignored) {}
    }

    public static void addFoodEaten(Villager vill, int count, float healAmount) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;

            CompoundTag root = getOrCreate(vill);
            root.putInt(K_FOOD_EATEN, safeInt(root.getInt(K_FOOD_EATEN)) + Math.max(0, count));
            root.putFloat(K_FOOD_HEAL_TOTAL, root.getFloat(K_FOOD_HEAL_TOTAL) + Math.max(0.0f, healAmount));
        } catch (Throwable ignored) {}
    }

    public static void addBlock(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_BLOCKS, safeInt(root.getInt(K_BLOCKS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addKill(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_KILLS, safeInt(root.getInt(K_KILLS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addDamageDealt(Villager vill, float amount) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (amount <= 0.0f) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_HITS_DEALT, safeInt(root.getInt(K_HITS_DEALT)) + 1);
            root.putFloat(K_DAMAGE_DEALT_TOTAL, root.getFloat(K_DAMAGE_DEALT_TOTAL) + amount);
        } catch (Throwable ignored) {}
    }

    public static void addTradeLockToggle(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_TRADE_LOCK_TOGGLES, safeInt(root.getInt(K_TRADE_LOCK_TOGGLES)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addPatrolRouteRecorded(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_PATROL_ROUTES_RECORDED, safeInt(root.getInt(K_PATROL_ROUTES_RECORDED)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addManualReroll(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_MANUAL_REROLLS, safeInt(root.getInt(K_MANUAL_REROLLS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addAutoReroll(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_AUTO_REROLLS, safeInt(root.getInt(K_AUTO_REROLLS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addMerchantMenuOpen(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_MERCHANT_MENU_OPENS, safeInt(root.getInt(K_MERCHANT_MENU_OPENS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addTradesCompleted(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_TRADES_COMPLETED, safeInt(root.getInt(K_TRADES_COMPLETED)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static void addEmeraldsFromManualRerolls(Villager vill, long emeralds) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (emeralds <= 0L) return;
            CompoundTag root = getOrCreate(vill);
            root.putLong(K_EMERALDS_FROM_MANUAL_REROLLS, safeLong(root.getLong(K_EMERALDS_FROM_MANUAL_REROLLS)) + emeralds);
        } catch (Throwable ignored) {}
    }

    public static void addEmeraldsFromAutoRerolls(Villager vill, long emeralds) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (emeralds <= 0L) return;
            CompoundTag root = getOrCreate(vill);
            root.putLong(K_EMERALDS_FROM_AUTO_REROLLS, safeLong(root.getLong(K_EMERALDS_FROM_AUTO_REROLLS)) + emeralds);
        } catch (Throwable ignored) {}
    }

    public static void addEmeraldsFromTrades(Villager vill, long emeralds) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            if (emeralds <= 0L) return;
            CompoundTag root = getOrCreate(vill);
            root.putLong(K_EMERALDS_FROM_TRADES, safeLong(root.getLong(K_EMERALDS_FROM_TRADES)) + emeralds);
        } catch (Throwable ignored) {}
    }

    public static void addFarmingPlanted(Villager vill, int count, boolean manualMode) {
        addFarmingCounter(vill, manualMode ? K_FARM_PLANTED_MANUAL : K_FARM_PLANTED_NEUTRAL, count);
    }

    public static void addFarmingHarvested(Villager vill, int count, boolean manualMode) {
        addFarmingCounter(vill, manualMode ? K_FARM_HARVESTED_MANUAL : K_FARM_HARVESTED_NEUTRAL, count);
    }

    public static void addFarmingBonemealed(Villager vill, int count, boolean manualMode) {
        addFarmingCounter(vill, manualMode ? K_FARM_BONEMEALED_MANUAL : K_FARM_BONEMEALED_NEUTRAL, count);
    }

    public static void addFarmingDepositedItems(Villager vill, long items, boolean manualMode) {
        addFarmingCounterLong(vill, manualMode ? K_FARM_DEPOSITED_ITEMS_MANUAL : K_FARM_DEPOSITED_ITEMS_NEUTRAL, items);
    }

    public static void addFarmingWithdrawnItems(Villager vill, long items, boolean manualMode) {
        addFarmingCounterLong(vill, manualMode ? K_FARM_WITHDRAWN_ITEMS_MANUAL : K_FARM_WITHDRAWN_ITEMS_NEUTRAL, items);
    }

    private static void addFarmingCounter(Villager vill, String key, int add) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            int n = Math.max(0, add);
            if (n <= 0) return;
            CompoundTag root = getOrCreate(vill);
            root.putLong(key, safeLong(root.getLong(key)) + (long) n);
        } catch (Throwable ignored) {}
    }

    private static void addFarmingCounterLong(Villager vill, String key, long add) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            long n = Math.max(0L, add);
            if (n <= 0L) return;
            CompoundTag root = getOrCreate(vill);
            root.putLong(key, safeLong(root.getLong(key)) + n);
        } catch (Throwable ignored) {}
    }

    public static void addDeath(Villager vill, int count) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            if (!RecruitService.isRecruited(vill)) return;
            CompoundTag root = getOrCreate(vill);
            root.putInt(K_DEATHS, safeInt(root.getInt(K_DEATHS)) + Math.max(0, count));
        } catch (Throwable ignored) {}
    }

    public static PacketVillagerHistoryData snapshot(Villager vill) {
        try {
            if (vill == null) return PacketVillagerHistoryData.missing(-1);
            CompoundTag root = getOrCreate(vill);
            return new PacketVillagerHistoryData(
                    vill.getId(),
                    true,
                    safeLong(root.getLong(K_TICKS_ALIVE)),
                    safeLong(root.getLong(K_DISTANCE_MB)),
                    safeInt(root.getInt(K_FOOD_EATEN)),
                    root.getFloat(K_FOOD_HEAL_TOTAL),
                    safeInt(root.getInt(K_BLOCKS)),
                    safeInt(root.getInt(K_HITS_TAKEN)),
                    root.getFloat(K_DAMAGE_TAKEN_TOTAL),
                    safeInt(root.getInt(K_HITS_DEALT)),
                    root.getFloat(K_DAMAGE_DEALT_TOTAL),
                    safeInt(root.getInt(K_KILLS)),
                    safeInt(root.getInt(K_MANUAL_REROLLS)),
                    safeInt(root.getInt(K_AUTO_REROLLS)),
                    safeInt(root.getInt(K_TRADE_LOCK_TOGGLES)),
                    safeInt(root.getInt(K_PATROL_ROUTES_RECORDED)),
                    safeInt(root.getInt(K_MERCHANT_MENU_OPENS)),
                    safeInt(root.getInt(K_TRADES_COMPLETED)),
                    safeInt(root.getInt(K_DEATHS)),
                    safeLong(root.getLong(K_EMERALDS_FROM_MANUAL_REROLLS)),
                    safeLong(root.getLong(K_EMERALDS_FROM_AUTO_REROLLS)),
                    safeLong(root.getLong(K_EMERALDS_FROM_TRADES)),
                    safeLong(root.getLong(K_FARM_PLANTED_NEUTRAL)),
                    safeLong(root.getLong(K_FARM_PLANTED_MANUAL)),
                    safeLong(root.getLong(K_FARM_HARVESTED_NEUTRAL)),
                    safeLong(root.getLong(K_FARM_HARVESTED_MANUAL)),
                    safeLong(root.getLong(K_FARM_BONEMEALED_NEUTRAL)),
                    safeLong(root.getLong(K_FARM_BONEMEALED_MANUAL)),
                    safeLong(root.getLong(K_FARM_WITHDRAWN_ITEMS_NEUTRAL)),
                    safeLong(root.getLong(K_FARM_WITHDRAWN_ITEMS_MANUAL)),
                    safeLong(root.getLong(K_FARM_DEPOSITED_ITEMS_NEUTRAL)),
                    safeLong(root.getLong(K_FARM_DEPOSITED_ITEMS_MANUAL))
            );
        } catch (Throwable ignored) {
            return PacketVillagerHistoryData.missing(vill == null ? -1 : vill.getId());
        }
    }

    private static CompoundTag getOrCreate(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG, CompoundTag.TAG_COMPOUND)) {
            pd.put(TAG, new CompoundTag());
        }
        return pd.getCompound(TAG);
    }

    private static int safeInt(int v) {
        if (v < 0) return 0;
        return v;
    }

    private static long safeLong(long v) {
        if (v < 0L) return 0L;
        return v;
    }
}
