// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerStatsService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.Merchant;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

public final class VillagerStatsService {
    public record StatSnapshot(
            int generosity,
            int timeliness,
            int intellect,
            int hoarder,
            int vitality,
            int agility,
            int strength,
            int armor,
            int motivation,
            int efficiency,
            int plantWhisperer,
            int ranger
    ) {
    }

    private VillagerStatsService() {}

    // Root persistent data tag on the entity
    public static final String TAG_ROOT = "ezvr_stats";

    // Version for future migrations
    private static final String TAG_VERSION = "v";
    private static final int STATS_VERSION = 3; // bumped for farming stats

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

    // Farming stat keys (stored as int points)
    public static final String K_MOTIVATION = "motivation";
    public static final String K_EFFICIENCY = "efficiency";
    public static final String K_PLANT_WHISPERER = "plant_whisperer";
    public static final String K_RANGER = "ranger";

    public static final int POINTS_MIN = -100;
    public static final int POINTS_MAX = 100;
    private static final String[] ALL_STAT_KEYS = {
            K_GENEROSITY, K_TIMELINESS, K_INTELLECT, K_HOARDER,
            K_VITALITY, K_AGILITY, K_STRENGTH, K_ARMOR,
            K_MOTIVATION, K_EFFICIENCY, K_PLANT_WHISPERER, K_RANGER
    };

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

            CompoundTag pd = getPersistentDataSafe(e);
            if (pd == null) return;

            CompoundTag root = getOrCreateRoot(pd);

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
                            root.contains(K_ARMOR) &&
                            root.contains(K_MOTIVATION) &&
                            root.contains(K_EFFICIENCY) &&
                            root.contains(K_PLANT_WHISPERER) &&
                            root.contains(K_RANGER);

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

            changed |= ensureKey(root, r, K_MOTIVATION);
            changed |= ensureKey(root, r, K_EFFICIENCY);
            changed |= ensureKey(root, r, K_PLANT_WHISPERER);
            changed |= ensureKey(root, r, K_RANGER);

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

                int m = clampPoints(root.getInt(K_MOTIVATION));
                int ePts = clampPoints(root.getInt(K_EFFICIENCY));
                int pw = clampPoints(root.getInt(K_PLANT_WHISPERER));
                int rg = clampPoints(root.getInt(K_RANGER));

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] VillagerStats assigned/upgraded: type={} entityId={} uuid={} generosity={} timeliness={} intellect={} hoarder={} vitality={} agility={} strength={} armor={} motivation={} efficiency={} plant_whisperer={} ranger={}",
                        String.valueOf(e.getType()),
                        e.getId(),
                        e.getUUID(),
                        g, t, i, h,
                        v, a, s, ar,
                        m, ePts, pw, rg
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerStatsService.ensureStats failed (soft): {}", t.toString());
        }
    }

    public static boolean inheritStatsFromParents(Villager child, Villager parentA, Villager parentB) {
        try {
            if (child == null || parentA == null || parentB == null) return false;

            ensureStats(parentA);
            ensureStats(parentB);
            ensureStats(child);

            CompoundTag parentARoot = getRoot(parentA);
            CompoundTag parentBRoot = getRoot(parentB);
            CompoundTag childRoot = getRoot(child);
            if (parentARoot == null || parentBRoot == null || childRoot == null) return false;

            RandomSource random = safeRandom(child);
            double maxVariancePct = ServerConfig.breedingStatMutationChancePct;
            if (Double.isNaN(maxVariancePct) || Double.isInfinite(maxVariancePct)) maxVariancePct = 0.0D;
            if (maxVariancePct < 0.0D) maxVariancePct = 0.0D;

            for (String key : ALL_STAT_KEYS) {
                int valueA = readOrRoll(parentARoot, key, random);
                int valueB = readOrRoll(parentBRoot, key, random);
                int inherited = random.nextBoolean() ? valueA : valueB;
                int varied = applyInheritedVariance(inherited, maxVariancePct, random);
                childRoot.putInt(key, clampPoints(varied));
            }
            childRoot.putInt(TAG_VERSION, STATS_VERSION);
            child.getPersistentData().put(TAG_ROOT, childRoot);
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerStatsService.inheritStatsFromParents failed (soft): {}", t.toString());
            return false;
        }
    }

    public static StatSnapshot snapshot(Entity entity) {
        ensureStats(entity);
        CompoundTag root = getRoot(entity);
        if (root == null) {
            return emptySnapshot();
        }
        return new StatSnapshot(
                readPoints(root, K_GENEROSITY),
                readPoints(root, K_TIMELINESS),
                readPoints(root, K_INTELLECT),
                readPoints(root, K_HOARDER),
                readPoints(root, K_VITALITY),
                readPoints(root, K_AGILITY),
                readPoints(root, K_STRENGTH),
                readPoints(root, K_ARMOR),
                readPoints(root, K_MOTIVATION),
                readPoints(root, K_EFFICIENCY),
                readPoints(root, K_PLANT_WHISPERER),
                readPoints(root, K_RANGER)
        );
    }

    public static StatSnapshot emptySnapshot() {
        return new StatSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
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

    private static int readOrRoll(CompoundTag root, String key, RandomSource random) {
        if (root == null || key == null) return rollPoints(random);
        if (!root.contains(key, CompoundTag.TAG_INT)) return rollPoints(random);
        return clampPoints(root.getInt(key));
    }

    private static int readPoints(CompoundTag root, String key) {
        if (root == null || key == null || !root.contains(key, CompoundTag.TAG_INT)) {
            return 0;
        }
        return clampPoints(root.getInt(key));
    }

    private static int applyInheritedVariance(int inherited, double maxVariancePct, RandomSource random) {
        if (random == null || maxVariancePct <= 0.0D || inherited == 0) {
            return inherited;
        }

        double varianceFactor = (random.nextDouble() * 2.0D) - 1.0D;
        double scale = 1.0D + (varianceFactor * (maxVariancePct / 100.0D));
        int varied = (int) Math.round(inherited * scale);

        // Avoid exact parent clones when variance is enabled and rounding collapses back to the same integer.
        if (varied == inherited) {
            varied += inherited > 0 ? 1 : -1;
        }
        return clampPoints(varied);
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

    private static CompoundTag getPersistentDataSafe(Entity e) {
        try {
            return e == null ? null : e.getPersistentData();
        } catch (Throwable t) {
            return null;
        }
    }

    private static CompoundTag getRoot(Entity e) {
        CompoundTag pd = getPersistentDataSafe(e);
        if (pd == null || !pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        return pd.getCompound(TAG_ROOT);
    }

    private static CompoundTag getOrCreateRoot(CompoundTag pd) {
        if (pd.contains(TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
            return pd.getCompound(TAG_ROOT);
        }
        CompoundTag root = new CompoundTag();
        pd.put(TAG_ROOT, root);
        return root;
    }
}
