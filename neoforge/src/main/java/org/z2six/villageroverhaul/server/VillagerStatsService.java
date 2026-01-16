// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerStatsService.java
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
    private static final int STATS_VERSION = 2; // bumped for combat stats

    // Merchant stat keys (stored as int points)
    public static final String K_GENEROSITY = "generosity";
    public static final String K_TIMELINESS = "timeliness";
    public static final String K_INTELLECT  = "intellect";
    public static final String K_HOARDER    = "hoarder";

    // Combat stat keys (stored as int points)
    public static final String K_VITALITY = "vitality";
    public static final String K_AGILITY  = "agility";
    public static final String K_STRENGTH = "strength";
    public static final String K_ARMOR    = "armor";

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

            int ver = 0;
            try { ver = root.getInt(TAG_VERSION); } catch (Throwable ignored) { ver = 0; }

            boolean hasAll =
                    root.contains(K_GENEROSITY) &&
                            root.contains(K_TIMELINESS) &&
                            root.contains(K_INTELLECT) &&
                            root.contains(K_HOARDER) &&
                            root.contains(K_VITALITY) &&
                            root.contains(K_AGILITY) &&
                            root.contains(K_STRENGTH) &&
                            root.contains(K_ARMOR);

            // Already initialized for this version + has all keys
            if (ver >= STATS_VERSION && hasAll) {
                return;
            }

            RandomSource r = safeRandom(e);

            // IMPORTANT: upgrade-in-place.
            // Only roll missing keys; never reroll existing values.
            boolean changed = false;

            changed |= ensureKey(root, r, K_GENEROSITY);
            changed |= ensureKey(root, r, K_TIMELINESS);
            changed |= ensureKey(root, r, K_INTELLECT);
            changed |= ensureKey(root, r, K_HOARDER);

            changed |= ensureKey(root, r, K_VITALITY);
            changed |= ensureKey(root, r, K_AGILITY);
            changed |= ensureKey(root, r, K_STRENGTH);
            changed |= ensureKey(root, r, K_ARMOR);

            // update version
            if (ver < STATS_VERSION) {
                root.putInt(TAG_VERSION, STATS_VERSION);
                changed = true;
            }

            if (changed) {
                pd.put(TAG_ROOT, root);

                int g = clampPoints(root.getInt(K_GENEROSITY));
                int t = clampPoints(root.getInt(K_TIMELINESS));
                int i = clampPoints(root.getInt(K_INTELLECT));
                int h = clampPoints(root.getInt(K_HOARDER));

                int v = clampPoints(root.getInt(K_VITALITY));
                int a = clampPoints(root.getInt(K_AGILITY));
                int s = clampPoints(root.getInt(K_STRENGTH));
                int ar = clampPoints(root.getInt(K_ARMOR));

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] VillagerStats assigned/upgraded: type={} entityId={} uuid={} generosity={} timeliness={} intellect={} hoarder={} vitality={} agility={} strength={} armor={}",
                        String.valueOf(e.getType()),
                        e.getId(),
                        e.getUUID(),
                        g, t, i, h,
                        v, a, s, ar
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerStatsService.ensureStats failed (soft): {}", t.toString());
        }
    }

    private static boolean ensureKey(CompoundTag root, RandomSource r, String key) {
        try {
            if (root == null || key == null) return false;
            if (root.contains(key, CompoundTag.TAG_INT)) return false;
            root.putInt(key, rollPoints(r));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isSupportedMerchantEntity(Entity e) {
        try {
            if (e == null) return false;
            if (e instanceof AbstractVillager) return true;
            return e instanceof Merchant;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int rollPoints(RandomSource r) {
        try {
            if (r == null) return 0;
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
