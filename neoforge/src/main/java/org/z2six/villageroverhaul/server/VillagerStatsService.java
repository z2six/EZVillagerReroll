// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/VillagerStatsService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class VillagerStatsService {

    private VillagerStatsService() {}

    // Root persistent data tag on the entity
    public static final String TAG_ROOT = "ezvr_stats";

    // Version for future migrations
    private static final String TAG_VERSION = "v";
    private static final int STATS_VERSION = 1;

    // Stat keys (stored as int points)
    public static final String K_GENEROSITY = "generosity";
    public static final String K_TIMELINESS = "timeliness";
    public static final String K_INTELLECT  = "intellect";
    public static final String K_HOARDER    = "hoarder";

    public static final int POINTS_MIN = -100;
    public static final int POINTS_MAX = 100;

    /**
     * Ensure the entity has stats. No-op if already present.
     *
     * Works for:
     * - Villagers (including babies)
     * - Wandering traders (AbstractVillager)
     * - Modded merchant-ish entities (Merchant)
     *
     * Uses Entity#getPersistentData so it survives world saves.
     */
    public static void ensureStats(Entity e) {
        try {
            if (e == null) return;
            if (!isSupportedMerchantEntity(e)) return;

            CompoundTag pd;
            try {
                pd = e.getPersistentData();
            } catch (Throwable t) {
                return;
            }
            if (pd == null) return;

            CompoundTag root;
            if (pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                root = pd.getCompound(TAG_ROOT);
            } else {
                root = new CompoundTag();
                pd.put(TAG_ROOT, root);
            }

            // Already initialized?
            int ver = 0;
            try { ver = root.getInt(TAG_VERSION); } catch (Throwable ignored) { ver = 0; }

            boolean hasAll =
                    root.contains(K_GENEROSITY) &&
                            root.contains(K_TIMELINESS) &&
                            root.contains(K_INTELLECT) &&
                            root.contains(K_HOARDER);

            if (ver >= STATS_VERSION && hasAll) {
                return;
            }

            RandomSource r = safeRandom(e);

            int g = rollPoints(r);
            int t = rollPoints(r);
            int i = rollPoints(r);
            int h = rollPoints(r);
            int a = rollPoints(r);

            root.putInt(TAG_VERSION, STATS_VERSION);
            root.putInt(K_GENEROSITY, g);
            root.putInt(K_TIMELINESS, t);
            root.putInt(K_INTELLECT, i);
            root.putInt(K_HOARDER, h);

            pd.put(TAG_ROOT, root);

            // INFO so you see it without debug logs enabled
            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] VillagerStats assigned: type={} entityId={} uuid={} generosity={} timeliness={} intellect={} hoarder={}",
                    String.valueOf(e.getType()),
                    e.getId(),
                    e.getUUID(),
                    g, t, i, h, a
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerStatsService.ensureStats failed (soft): {}", t.toString());
        }
    }

    public static boolean isSupportedMerchantEntity(Entity e) {
        try {
            if (e == null) return false;
            // AbstractVillager covers Villager + WanderingTrader and many modded villager-like types.
            if (e instanceof AbstractVillager) return true;
            // Merchant interface catch-all for modded merchant entities.
            return e instanceof Merchant;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int rollPoints(RandomSource r) {
        try {
            if (r == null) return 0;
            // [-100..100] inclusive
            return r.nextInt((POINTS_MAX - POINTS_MIN) + 1) + POINTS_MIN;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int clampPoints(int p) {
        if (p < POINTS_MIN) return POINTS_MIN;
        if (p > POINTS_MAX) return POINTS_MAX;
        return p;
    }

    private static RandomSource safeRandom(Entity e) {
        try {
            RandomSource r = e.getRandom();
            return r == null ? RandomSource.create() : r;
        } catch (Throwable t) {
            return RandomSource.create();
        }
    }
}
