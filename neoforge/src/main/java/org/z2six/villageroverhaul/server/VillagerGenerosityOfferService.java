//
package org.z2six.villageroverhaul.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

public final class VillagerGenerosityOfferService {

    private VillagerGenerosityOfferService() {}

    private static final String TAG_GEN = "ezvr_gen";
    private static final String K_FP = "fp";
    private static final String K_APPLIED_PCT = "appliedPct"; // double
    private static final String K_BASE_A = "baseA"; // int[]
    private static final String K_BASE_B = "baseB"; // int[]

    /**
     * Normalizes and applies Generosity discount/surcharge to all offers that cost emeralds.
     *
     * Stack-safe:
     * - Always applies from baseline (baseA/baseB) so it never compounds.
     * - Fingerprint ignores counts so our own mutations don't cause re-baseline loops.
     *
     * @return true if the offers were mutated or we changed stored appliedPct/baseline.
     */
    public static boolean normalizeAndApply(Entity e) {
        try {
            if (!(e instanceof AbstractVillager av)) return false;

            // Ensure stats exist (we read generosity points from persistent data)
            VillagerStatsService.ensureStats(e);

            CompoundTag pd = e.getPersistentData();
            if (pd == null || !pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) return false;

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);

            int gPts = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
            double pct = lerpFromPoints(gPts, ServerConfig.generosityMinPct, ServerConfig.generosityMaxPct);
            if (Double.isNaN(pct) || Double.isInfinite(pct)) pct = 0.0;

            MerchantOffers offers = av.getOffers();
            if (offers == null || offers.isEmpty()) return false;

            CompoundTag gen = root.contains(TAG_GEN, CompoundTag.TAG_COMPOUND) ? root.getCompound(TAG_GEN) : new CompoundTag();

            int fpNow = fingerprint(offers);
            int fpOld = gen.getInt(K_FP);

            int n = offers.size();
            int[] baseA;
            int[] baseB;

            boolean needRebaseline = (fpOld != fpNow)
                    || !gen.contains(K_BASE_A, IntArrayTag.TAG_INT_ARRAY)
                    || !gen.contains(K_BASE_B, IntArrayTag.TAG_INT_ARRAY);

            if (!needRebaseline) {
                baseA = gen.getIntArray(K_BASE_A);
                baseB = gen.getIntArray(K_BASE_B);
                needRebaseline = (baseA.length != n) || (baseB.length != n);
            }

            if (needRebaseline) {
                baseA = new int[n];
                baseB = new int[n];

                for (int i = 0; i < n; i++) {
                    MerchantOffer o = offers.get(i);
                    if (o == null) continue;

                    ItemStack a = safeBaseCostA(o);
                    ItemStack b = safeCostB(o);

                    baseA[i] = (isEmerald(a) ? clampEmeraldCount(a.getCount()) : 0);
                    baseB[i] = (isEmerald(b) ? clampEmeraldCount(b.getCount()) : 0);
                }

                gen.putInt(K_FP, fpNow);
                gen.put(K_BASE_A, new IntArrayTag(baseA));
                gen.put(K_BASE_B, new IntArrayTag(baseB));
                gen.putDouble(K_APPLIED_PCT, 0.0); // reset; we will apply fresh below
            } else {
                baseA = gen.getIntArray(K_BASE_A);
                baseB = gen.getIntArray(K_BASE_B);
            }

            double oldPct = gen.getDouble(K_APPLIED_PCT);

            // If pct is ~0, restore baseline (if we previously applied something)
            if (Math.abs(pct) < 0.0001) {
                if (Math.abs(oldPct) >= 0.0001) {
                    restoreBaseline(offers, baseA, baseB);
                    gen.putDouble(K_APPLIED_PCT, 0.0);
                    root.put(TAG_GEN, gen);
                    pd.put(VillagerStatsService.TAG_ROOT, root);
                    return true;
                }
                // Even if we didn't restore, if we re-baselined above, we should persist it:
                if (needRebaseline) {
                    root.put(TAG_GEN, gen);
                    pd.put(VillagerStatsService.TAG_ROOT, root);
                    return true;
                }
                return false;
            }

            // Apply from baseline => no stacking
            boolean changed = applyFromBaseline(offers, baseA, baseB, pct);
            if (changed || Math.abs(oldPct - pct) > 0.0001 || needRebaseline) {
                gen.putDouble(K_APPLIED_PCT, pct);
                root.put(TAG_GEN, gen);
                pd.put(VillagerStatsService.TAG_ROOT, root);

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Applied Generosity offers entityId={} uuid={} pct={} fp={}",
                        e.getId(), e.getUUID(), round2(pct), fpNow
                );
                return true;
            }

            return false;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerGenerosityOfferService.normalizeAndApply failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean applyFromBaseline(MerchantOffers offers, int[] baseA, int[] baseB, double pct) {
        boolean changed = false;
        int n = Math.min(offers.size(), Math.min(baseA.length, baseB.length));

        for (int i = 0; i < n; i++) {
            MerchantOffer o = offers.get(i);
            if (o == null) continue;

            ItemStack a = safeBaseCostA(o);
            ItemStack b = safeCostB(o);

            if (baseA[i] > 0 && isEmerald(a)) {
                int want = scaleCount(baseA[i], pct);
                if (a.getCount() != want) {
                    a.setCount(want);
                    changed = true;
                }
            }

            if (baseB[i] > 0 && isEmerald(b)) {
                int want = scaleCount(baseB[i], pct);
                if (b.getCount() != want) {
                    b.setCount(want);
                    changed = true;
                }
            }
        }

        return changed;
    }

    private static void restoreBaseline(MerchantOffers offers, int[] baseA, int[] baseB) {
        int n = Math.min(offers.size(), Math.min(baseA.length, baseB.length));
        for (int i = 0; i < n; i++) {
            MerchantOffer o = offers.get(i);
            if (o == null) continue;

            ItemStack a = safeBaseCostA(o);
            ItemStack b = safeCostB(o);

            if (baseA[i] > 0 && isEmerald(a)) a.setCount(clampEmeraldCount(baseA[i]));
            if (baseB[i] > 0 && isEmerald(b)) b.setCount(clampEmeraldCount(baseB[i]));
        }
    }

    // ---- helpers ----

    private static boolean isEmerald(ItemStack s) {
        return s != null && !s.isEmpty() && s.is(Items.EMERALD);
    }

    private static int clampEmeraldCount(int c) {
        if (c < 1) return 1;
        if (c > 64) return 64;
        return c;
    }

    private static int scaleCount(int base, double pct) {
        // pct >= 0 => discount
        double factor = (pct >= 0.0) ? (1.0 - (pct / 100.0)) : (1.0 + (Math.abs(pct) / 100.0));
        int out = (int) Math.round(base * factor);
        return clampEmeraldCount(out);
    }

    private static double lerpFromPoints(int points, double min, double max) {
        int p = VillagerStatsService.clampPoints(points);
        double t = (p + 100.0) / 200.0;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return min + (max - min) * t;
    }

    /**
     * Fingerprint used to detect "structure changes" in offers that should trigger re-baseline.
     *
     * CRITICAL: this must ignore stack counts, because generosity itself mutates emerald counts.
     * If counts are included, re-calling normalizeAndApply will re-baseline on already-discounted values and drift/stack.
     */
    private static int fingerprint(MerchantOffers offers) {
        int h = 1;
        int n = Math.min(256, offers.size());
        for (int i = 0; i < n; i++) {
            MerchantOffer o = offers.get(i);
            if (o == null) { h = 31 * h; continue; }

            ItemStack a = safeBaseCostA(o);
            ItemStack b = safeCostB(o);
            ItemStack r = safeResult(o);

            // Identity only; ignore counts.
            h = 31 * h + itemKeyHash(a);
            h = 31 * h + itemKeyHash(b);
            h = 31 * h + itemKeyHash(r);
        }
        return h;
    }

    private static int itemKeyHash(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return 0;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            return id == null ? 0 : id.hashCode();
        } catch (Throwable t) {
            return 0;
        }
    }

    // These are mapped names in modern Mojang mappings; adjust if your IDE complains.
    private static ItemStack safeBaseCostA(MerchantOffer o) {
        try { return o.getBaseCostA(); } catch (Throwable t) { try { return o.getCostA(); } catch (Throwable ignored) { return ItemStack.EMPTY; } }
    }

    private static ItemStack safeCostB(MerchantOffer o) {
        try { return o.getCostB(); } catch (Throwable t) { return ItemStack.EMPTY; }
    }

    private static ItemStack safeResult(MerchantOffer o) {
        try { return o.getResult(); } catch (Throwable t) { return ItemStack.EMPTY; }
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }
}
