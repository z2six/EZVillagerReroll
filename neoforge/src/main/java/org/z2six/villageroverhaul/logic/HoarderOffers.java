// HoarderOffers.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/logic/HoarderOffers.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.mixin.VillagerAccessor;
import org.z2six.villageroverhaul.server.VillagerStatsService;

public final class HoarderOffers {

    private static final String NBT_ROOT = "villageroverhaul";
    private static final String NBT_BASELINE = "hoarder_baseline_offers";
    private static final String NBT_APPLIED_DELTA = "hoarder_applied_delta";

    // Safety: avoid runaway updateTrades loops if a modded villager refuses to append more offers.
    private static final int MAX_UPDATE_TRADES_CALLS = 32;

    private HoarderOffers() {}

    /**
     * Normalize villager offer count to:
     *   target = clampMin1( baseline + desiredDelta )
     *
     * Key rules:
     * - baseline is the "external/vanilla/modpack" baseline offer count (dynamic; can go up or down)
     * - appliedDelta is the delta we ACTUALLY applied last time (NOT the desired delta)
     * - drift correction: if current offers != baseline + appliedDelta, treat it as external change and recompute baseline
     * - reductions are enforced even if trades are locked; we truncate from the end and then sanitize lock mask
     */
    public static boolean normalizeOffers(Villager vill, ServerPlayer maybePlayerForSync) {
        try {
            if (vill == null) return false;

            MerchantOffers offers = vill.getOffers();
            if (offers == null) return false;

            int beforeSize = safeSize(offers);

            int baseline = getBaseline(vill);
            int prevApplied = getAppliedDelta(vill);

            // If baseline missing, initialize baseline as "current offers minus applied delta"
            // (applied delta is usually 0 on first run, so baseline becomes current).
            if (baseline < 0) {
                baseline = beforeSize - prevApplied;
                if (baseline < 0) baseline = 0;
                setBaseline(vill, baseline);
            }

            // ---- DRIFT CORRECTION ----
            // If vanilla/mods changed the offer list (level-up, injections, reloads, etc.),
            // recompute baseline so that: baseline + prevApplied == current offers.
            // This lets baseline move BOTH directions (no hardcoding by level).
            int expected = baseline + prevApplied;
            if (expected != beforeSize) {
                int newBaseline = beforeSize - prevApplied;
                if (newBaseline < 0) newBaseline = 0;

                if (newBaseline != baseline) {
                    baseline = newBaseline;
                    setBaseline(vill, baseline);

                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] HoarderOffers drift detected: villager={} current={} expected={} prevApplied={} baseline {}->{}",
                                vill.getUUID(), beforeSize, expected, prevApplied, (expected - prevApplied), baseline
                        );
                    }
                }
            }

            // Step 2: compute desired delta (config-clamped)
            int desiredDelta = computeDesiredDelta(vill);

            int target = baseline + desiredDelta;
            if (target < 1) target = 1;

            boolean changed = false;

            // Step 3: enforce exact target size
            if (beforeSize > target) {
                // Truncate from end. Locks do NOT prevent the reduction.
                for (int i = beforeSize - 1; i >= target; i--) {
                    try { offers.remove(i); } catch (Throwable ignored) {}
                }
                changed = true;

            } else if (beforeSize < target) {
                // Append offers via vanilla logic (updateTrades should append current level's offers).
                // This avoids “fake offers” that vanilla/other mods don’t understand.
                int calls = 0;
                int last = beforeSize;

                while (safeSize(offers) < target && calls < MAX_UPDATE_TRADES_CALLS) {
                    calls++;
                    try {
                        ((VillagerAccessor) vill).ezvr$updateTrades();
                    } catch (Throwable t) {
                        break;
                    }

                    int now = safeSize(offers);
                    if (now <= last) {
                        // No growth => likely cannot append further (modded behavior). Break to avoid infinite loop.
                        break;
                    }
                    last = now;
                }

                // If we overshot (updateTrades can add multiple offers), trim back down.
                int afterGrow = safeSize(offers);
                if (afterGrow > target) {
                    for (int i = afterGrow - 1; i >= target; i--) {
                        try { offers.remove(i); } catch (Throwable ignored) {}
                    }
                    changed = true;
                } else if (afterGrow >= target) {
                    changed = true;
                } else {
                    // Could not reach target; keep best-effort result.
                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOffers: could not append enough offers (villager={} target={} got={})",
                                vill.getUUID(), target, afterGrow);
                    }
                    changed = (afterGrow != beforeSize);
                }

                // Apply special prices for the player if we have one (optional but nice).
                if (maybePlayerForSync != null) {
                    try { ((VillagerAccessor) vill).ezvr$updateSpecialPrices(maybePlayerForSync); } catch (Throwable ignored) {}
                }
            }

            // Step 4: always sanitize lock mask to current size (especially after truncation)
            try {
                int finalSize = safeSize(offers);
                long mask = TradeLockState.getMask(vill);
                long sanitized = TradeLockState.sanitizeMaskForSize(mask, finalSize);
                if (sanitized != mask) {
                    TradeLockState.setMask(vill, sanitized);
                }
            } catch (Throwable ignored) {}

            // Step 5: record ACTUAL applied delta now (not desired delta)
            // This is CRITICAL for correct drift handling when target is clamped (min 1 offer).
            int finalSize = safeSize(offers);
            int appliedActual = finalSize - baseline;
            setAppliedDelta(vill, appliedActual);

            // Step 6: resync UI if player is currently in a MerchantMenu
            if (maybePlayerForSync != null && maybePlayerForSync.containerMenu instanceof MerchantMenu menu) {
                try {
                    maybePlayerForSync.sendMerchantOffers(
                            menu.containerId,
                            vill.getOffers(),
                            vill.getVillagerData().getLevel(),
                            vill.getVillagerXp(),
                            vill.showProgressBar(),
                            vill.canRestock()
                    );
                } catch (Throwable ignored) {}
            }

            return changed;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOffers.normalizeOffers failed (soft): {}", t.toString());
            return false;
        }
    }

    /**
     * IMPORTANT: Call this after an offer list has been REPLACED (clear+rebuild, restore canonical offers,
     * settlement pay/decline apply, etc.)
     *
     * It resets:
     * - baseline = currentOffersSize  (can go DOWN as well as up)
     * - appliedDelta = 0
     *
     * Then calls normalizeOffers to enforce the configured hoarder delta.
     */
    public static boolean normalizeAfterOfferRebuild(Villager vill, ServerPlayer maybePlayerForSync) {
        try {
            if (vill == null) return false;

            MerchantOffers offers = vill.getOffers();
            if (offers == null) return false;

            int nowSize = safeSize(offers);

            // Treat current list as new baseline (can decrease compared to previous baseline).
            setBaseline(vill, nowSize);

            // Reset applied delta so drift correction is clean.
            setAppliedDelta(vill, 0);

            return normalizeOffers(vill, maybePlayerForSync);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] HoarderOffers.normalizeAfterOfferRebuild failed (soft): {}", t.toString());
            return false;
        }
    }

    // ------------------------------------------------------------
    // Delta computation
    // ------------------------------------------------------------

    private static int computeDesiredDelta(Villager vill) {
        // Server-configured delta clamp
        int minDelta = -3;
        int maxDelta = 3;

        try {
            minDelta = ServerConfig.hoarderExtraOffersMin;
            maxDelta = ServerConfig.hoarderExtraOffersMax;
            if (minDelta > maxDelta) {
                int tmp = minDelta; minDelta = maxDelta; maxDelta = tmp;
            }
        } catch (Throwable ignored) {}

        int points = 0;
        try {
            CompoundTag pd = vill.getPersistentData();
            if (pd != null && pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) {
                CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);
                points = root.getInt(VillagerStatsService.K_HOARDER);
            }
        } catch (Throwable ignored) {}

        // Points are always clamped to [-100..100]
        points = VillagerStatsService.clampPoints(points);

        // Map points [-100..100] into delta range [minDelta..maxDelta]
        // t in [0..1]
        double t = (points + 100.0) / 200.0;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        double d = minDelta + (maxDelta - minDelta) * t;
        int delta = (int) Math.round(d);

        // Safety clamp after rounding
        if (delta < minDelta) delta = minDelta;
        if (delta > maxDelta) delta = maxDelta;

        return delta;
    }

    // ------------------------------------------------------------
    // Persistent per-villager bookkeeping
    // ------------------------------------------------------------

    private static int getBaseline(Villager vill) {
        try {
            CompoundTag pd = vill.getPersistentData();
            if (pd == null || !pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return -1;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_BASELINE, CompoundTag.TAG_INT)) return -1;
            return root.getInt(NBT_BASELINE);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void setBaseline(Villager vill, int baseline) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            CompoundTag root = pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND) ? pd.getCompound(NBT_ROOT) : new CompoundTag();
            root.putInt(NBT_BASELINE, Math.max(0, baseline));
            pd.put(NBT_ROOT, root);
        } catch (Throwable ignored) {}
    }

    private static int getAppliedDelta(Villager vill) {
        try {
            CompoundTag pd = vill.getPersistentData();
            if (pd == null || !pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return 0;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_APPLIED_DELTA, CompoundTag.TAG_INT)) return 0;
            return root.getInt(NBT_APPLIED_DELTA);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void setAppliedDelta(Villager vill, int delta) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            CompoundTag root = pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND) ? pd.getCompound(NBT_ROOT) : new CompoundTag();
            root.putInt(NBT_APPLIED_DELTA, delta);
            pd.put(NBT_ROOT, root);
        } catch (Throwable ignored) {}
    }

    private static int safeSize(MerchantOffers offers) {
        try { return offers == null ? 0 : Math.max(0, offers.size()); } catch (Throwable t) { return 0; }
    }
}
