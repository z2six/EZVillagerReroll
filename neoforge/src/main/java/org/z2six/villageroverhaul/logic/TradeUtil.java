// neoforge\src\main\java\org\z2six\villageroverhaul\logic\TradeUtil.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.AbstractVillagerAccessor;
import org.z2six.villageroverhaul.mixin.VillagerAccessor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class TradeUtil {

    /**
     * Max attempts to rebuild offers until we avoid duplicates against preserved locked offers.
     * Keep this bounded to avoid stalls.
     */
    private static final int MAX_DEDUP_ATTEMPTS = 25;

    public static boolean rebuildOffers(Villager vill, ServerPlayer player) {
        return rebuildOffersInternal(vill, player, true);
    }

    /**
     * Internal offer rebuild used by:
     * - normal reroll (syncToPlayer=true)
     * - auto-search rerolls (syncToPlayer=false)
     * - catalog sampling (syncToPlayer=false)
     *
     * Must preserve trade locks and avoid "duplicate exact offers" in the final list when possible.
     */
    public static boolean rebuildOffersInternal(Villager vill, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (vill == null) return false;

            final VillagerData original = vill.getVillagerData();
            final int targetLevel = Math.max(1, Math.min(5, original.getLevel()));
            final int offersBefore = vill.getOffers() != null ? vill.getOffers().size() : -1;

            // Snapshot old offers (needed to preserve locked indices)
            final MerchantOffers oldOffersSnapshot = snapshotOffersSafe(vill);

            // Preserve lock mask (server-side authoritative on the entity via TradeLockState)
            final long beforeMask = TradeLockState.getMask(vill);

            // We may rebuild multiple times if we detect duplicates involving unlocked trades.
            MerchantOffers finalOffers = null;
            long finalMask = beforeMask;

            int attempt;
            boolean accepted = false;

            for (attempt = 1; attempt <= MAX_DEDUP_ATTEMPTS; attempt++) {
                // 1) rebuild fresh offers using vanilla updateTrades progression
                MerchantOffers rebuilt = rebuildOffersVanillaSteps(vill, player, original, targetLevel);

                // 2) sanitize lock mask to rebuilt size
                long afterMask = TradeLockState.sanitizeMaskForSize(beforeMask, rebuilt == null ? 0 : rebuilt.size());
                if (afterMask != beforeMask) {
                    // keep entity state updated so future logic is consistent
                    TradeLockState.setMask(vill, afterMask);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Lock mask sanitized due to offer size change (villager={} before={} after={})",
                            vill.getUUID(), Long.toUnsignedString(beforeMask), Long.toUnsignedString(afterMask));
                }

                // 3) restore locked offers into the rebuilt list
                if (rebuilt != null && oldOffersSnapshot != null && afterMask != 0L) {
                    restoreLockedOffersSafe(rebuilt, oldOffersSnapshot, afterMask, vill);
                }

                // 4) check duplicates in final list (especially duplicates caused by restoring locks)
                boolean hasBadDupes = hasDuplicatesInvolvingUnlocked(rebuilt, afterMask);

                if (!hasBadDupes) {
                    // success
                    finalOffers = rebuilt;
                    finalMask = afterMask;
                    accepted = true;
                    break;
                }

                // If we get here, duplicates exist where at least one is unlocked.
                // Try again with a new random roll.
                if (attempt == 1 || attempt == 5 || attempt == 10 || attempt == 25) {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] Dedup rebuild retry {} / {} (villager={} level={}): duplicates detected involving unlocked offers.",
                            attempt, MAX_DEDUP_ATTEMPTS, vill.getUUID(), targetLevel
                    );
                }
            }

            if (!accepted) {
                // We tried; keep whatever we have currently on the villager (already set by last rebuild).
                finalOffers = vill.getOffers();
                finalMask = TradeLockState.getMask(vill);

                VillagerOverhaul.LOG().warn(
                        "[VillagerOverhaul] Dedup rebuild exhausted ({} attempts). Keeping last rebuild result (villager={}, offers={}).",
                        MAX_DEDUP_ATTEMPTS, vill.getUUID(), finalOffers == null ? -1 : finalOffers.size()
                );
            }

            // Ensure villager has the final offers applied (in case our helper returned a detached instance)
            try {
                if (finalOffers != null && vill.getOffers() != finalOffers) {
                    ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(finalOffers);
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Failed to force-set final offers reference (soft): {}", t.toString());
            }

            // Optional GUI sync
            if (syncToPlayer && player != null && player.containerMenu instanceof MerchantMenu menu) {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                player.sendMerchantOffers(
                        menu.containerId,
                        offers,
                        vill.getVillagerData().getLevel(),
                        vill.getVillagerXp(),
                        vill.showProgressBar(),
                        vill.canRestock()
                );

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] Rebuilt offers (dedup attempts={}): villager={}, level={}, offers {} -> {}",
                        accepted ? attempt : MAX_DEDUP_ATTEMPTS,
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            } else {
                MerchantOffers offers = vill.getOffers();
                int offersAfter = offers != null ? offers.size() : -1;

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Rebuilt offers (no GUI sync, dedup attempts={}): villager={}, level={}, offers {} -> {}",
                        accepted ? attempt : MAX_DEDUP_ATTEMPTS,
                        vill.getUUID(), targetLevel, offersBefore, offersAfter
                );
            }

            // If we changed/sanitized locks, push updated locks to active traders so UI stays consistent.
            if (finalMask != beforeMask) {
                try {
                    org.z2six.villageroverhaul.server.TradeLockSyncService.syncToActiveTraders(vill, finalMask);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Failed to sync sanitized lock mask to active traders: {}", t.toString());
                }
            }

            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] rebuildOffersInternal exception", t);
            return false;
        }
    }

    /**
     * Rebuild offers by clearing and running vanilla updateTrades() per level up to targetLevel.
     * Returns the offers instance currently on the villager after rebuild.
     */
    private static MerchantOffers rebuildOffersVanillaSteps(Villager vill, ServerPlayer player, VillagerData original, int targetLevel) {
        try {
            // Clear and rebuild per level
            ((AbstractVillagerAccessor) (AbstractVillager) vill).ezvr$setOffers(new MerchantOffers());

            for (int l = 1; l <= targetLevel; l++) {
                VillagerData step = new VillagerData(original.getType(), original.getProfession(), l);
                vill.setVillagerData(step);
                ((VillagerAccessor) vill).ezvr$updateTrades(); // appends that level's offers
            }

            vill.setVillagerData(original);

            // Only apply special prices if player is known
            try {
                if (player != null) ((VillagerAccessor) vill).ezvr$updateSpecialPrices(player);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] updateSpecialPrices skipped/failed (soft): {}", t.toString());
            }

            return vill.getOffers();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] rebuildOffersVanillaSteps failed (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return vill != null ? vill.getOffers() : null;
        }
    }

    private static MerchantOffers snapshotOffersSafe(Villager vill) {
        try {
            if (vill == null) return null;
            if (vill.getOffers() == null) return null;

            MerchantOffers snap = new MerchantOffers();
            for (MerchantOffer o : vill.getOffers()) {
                if (o != null) snap.add(o);
            }
            return snap;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] Failed to snapshot old offers (villager={}): {}", vill == null ? "null" : vill.getUUID(), t.toString());
            return null;
        }
    }

    private static void restoreLockedOffersSafe(MerchantOffers current, MerchantOffers oldOffers, long mask, Villager vill) {
        try {
            if (current == null || oldOffers == null || mask == 0L) return;

            int limit = Math.min(current.size(), oldOffers.size());
            int preserved = 0;

            for (int i = 0; i < limit; i++) {
                if ((mask & (1L << i)) == 0L) continue;

                try {
                    MerchantOffer old = oldOffers.get(i);
                    if (old != null) {
                        current.set(i, old);
                        preserved++;
                    }
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] Failed to preserve locked offer idx={} villager={} {}",
                            i, vill == null ? "null" : vill.getUUID(), t.toString());
                }
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Preserved {} locked offers (villager={}, mask={})",
                    preserved, vill == null ? "null" : vill.getUUID(), Long.toUnsignedString(mask));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] restoreLockedOffersSafe failed (soft): {}", t.toString());
        }
    }

    /**
     * Returns true if there are duplicates in the offer list where at least one of the duplicates is UNLOCKED.
     * If duplicates exist but they are both locked, we do not retry (we cannot safely change locked offers).
     */
    private static boolean hasDuplicatesInvolvingUnlocked(MerchantOffers offers, long lockMask) {
        try {
            if (offers == null || offers.isEmpty()) return false;

            // Keyed by RESULT identity only (item id + NBT/components), ignoring offer inputs and counts.
            Map<String, Integer> firstIndexByResultSig = new HashMap<>();

            for (int i = 0; i < offers.size(); i++) {
                MerchantOffer o = offers.get(i);
                if (o == null) continue;

                ItemStack res = null;
                try { res = o.getResult(); } catch (Throwable ignored) {}
                if (res == null || res.isEmpty()) continue;

                String sig = resultSignatureOf(res);
                if (sig == null || sig.isEmpty()) continue;

                Integer first = firstIndexByResultSig.putIfAbsent(sig, i);
                if (first == null) continue;

                boolean firstLocked = (lockMask & (1L << first)) != 0L;
                boolean thisLocked  = (lockMask & (1L << i)) != 0L;

                // If at least one side is unlocked, we can fix it by rerolling again.
                if (!firstLocked || !thisLocked) {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] Duplicate RESULT detected (idxA={}, idxB={}, lockedA={}, lockedB={}, resultSig={})",
                            first, i, firstLocked, thisLocked, sig
                    );
                    return true;
                }

                // Both are locked duplicates -> not fixable without breaking locks.
                // Log once in a while to avoid spam.
                if ((i == 0 || i == 1) && VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug(
                            "[VillagerOverhaul] Duplicate RESULT exists but both are locked (idxA={}, idxB={}, resultSig={}); leaving as-is.",
                            first, i, sig
                    );
                }
            }

            return false;

        } catch (Throwable t) {
            // Fail-open: don't risk endless rebuild loops.
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] hasDuplicatesInvolvingUnlocked(result-only) failed (soft): {}", t.toString());
            return false;
        }
    }

    private static String resultSignatureOf(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String id = "unknown";
            try {
                Item item = s.getItem();
                id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
            } catch (Throwable ignored) {}

            // Universal “data identity”:
            // - Prefer components patch (1.21+)
            // - Fallback to tag if present
            // - Fallback to toString()
            String extra = readExtraDataStringReflective(s);

            if (extra != null && extra.length() > 2048) {
                extra = extra.substring(0, 2048);
            }

            // IMPORTANT: ignore count to satisfy "never duplicate items"
            // (same item+data is considered duplicate even if count differs).
            return id + ":" + (extra == null ? "" : extra);

        } catch (Throwable t) {
            return "result_err";
        }
    }

    private static ItemStack safeStack(ItemStack s) {
        return s == null ? ItemStack.EMPTY : s;
    }

    /**
     * ItemStack identity for dedup:
     * - item id
     * - count
     * - "extra data" (NBT or components), discovered reflectively to survive 1.21.x signature changes.
     */
    private static String stackSig(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String id = "unknown";
            try {
                Item item = s.getItem();
                id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
            } catch (Throwable ignored) {}

            int count = 0;
            try { count = s.getCount(); } catch (Throwable ignored) {}

            String extra = readExtraDataStringReflective(s);

            // Keep signature bounded-ish; this is internal logic, not player-facing.
            if (extra != null && extra.length() > 2048) {
                extra = extra.substring(0, 2048);
            }

            return id + "x" + count + ":" + (extra == null ? "" : extra);
        } catch (Throwable t) {
            return "stack_err";
        }
    }

    /**
     * Try multiple strategies to extract differentiating data:
     * - getTag() (CompoundTag) if present
     * - getComponents() / getComponentsPatch() if present
     * - finally fall back to toString()
     */
    private static String readExtraDataStringReflective(ItemStack s) {
        try {
            if (s == null) return "";

            // 1) Components patch (1.21+). This is usually the best identity source.
            try {
                Method m = s.getClass().getMethod("getComponentsPatch");
                Object patch = m.invoke(s);
                if (patch != null) return patch.toString();
            } catch (Throwable ignored) {}

            // 2) Full components (fallback)
            try {
                Method m = s.getClass().getMethod("getComponents");
                Object comps = m.invoke(s);
                if (comps != null) return comps.toString();
            } catch (Throwable ignored) {}

            // 3) Legacy tag (if present)
            try {
                Method m = s.getClass().getMethod("getTag");
                Object tagObj = m.invoke(s);
                if (tagObj instanceof CompoundTag tag) {
                    return tag.toString();
                }
            } catch (Throwable ignored) {}

            // 4) Last resort
            try {
                return s.toString();
            } catch (Throwable ignored) {
                return "";
            }

        } catch (Throwable t) {
            return "";
        }
    }

    private TradeUtil() {}
}
