// neoforge\src\main\java\org\z2six\villageroverhaul\logic\TradeLockState.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class TradeLockState {

    private static final String NBT_ROOT = "villageroverhaul";
    private static final String NBT_LOCK_MASK = "locked_trade_mask";
    private static final String NBT_LOCKED_OFFERS = "locked_trade_offers";
    private static final String TAG_WRAP_VALUE = "v";

    public static long getMask(Villager vill) {
        try {
            if (vill == null) return 0L;
            CompoundTag pd = vill.getPersistentData();
            if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return 0L;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_LOCK_MASK, CompoundTag.TAG_LONG)) return 0L;
            return root.getLong(NBT_LOCK_MASK);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockState.getMask failed: {}", t.toString());
            return 0L;
        }
    }

    public static void setMask(Villager vill, long mask) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            CompoundTag root = pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND) ? pd.getCompound(NBT_ROOT) : new CompoundTag();
            root.putLong(NBT_LOCK_MASK, mask);
            pd.put(NBT_ROOT, root);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockState.setMask failed: {}", t.toString());
        }
    }

    /**
     * Store an exact snapshot of the CURRENT offer at idx so locks remain stable even if vanilla/mods mutate offers.
     */
    public static void captureLockedOffer(Villager vill, int idx) {
        try {
            if (vill == null) return;
            MerchantOffers offers = vill.getOffers();
            if (offers == null) return;
            if (idx < 0 || idx >= offers.size()) return;

            MerchantOffer offer = offers.get(idx);
            if (offer == null) return;

            CompoundTag wrap = encodeOfferWrapped(vill, offer);
            if (wrap == null) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] capture_failed villager={} idx={}", vill.getUUID(), idx);
                return;
            }

            CompoundTag pd = vill.getPersistentData();
            CompoundTag root = pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND) ? pd.getCompound(NBT_ROOT) : new CompoundTag();
            CompoundTag locked = root.contains(NBT_LOCKED_OFFERS, CompoundTag.TAG_COMPOUND) ? root.getCompound(NBT_LOCKED_OFFERS) : new CompoundTag();

            locked.put(String.valueOf(idx), wrap);
            root.put(NBT_LOCKED_OFFERS, locked);
            pd.put(NBT_ROOT, root);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] captured villager={} idx={}", vill.getUUID(), idx);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] capture_error villager={} idx={} err={}",
                    vill == null ? "null" : vill.getUUID(), idx, t.toString());
        }
    }

    public static boolean hasLockedOfferSnapshot(Villager vill, int idx) {
        try {
            if (vill == null) return false;
            if (idx < 0 || idx >= 63) return false;
            CompoundTag locked = getLockedOffersCompound(vill);
            if (locked == null) return false;
            return locked.contains(String.valueOf(idx), CompoundTag.TAG_COMPOUND);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Best-effort: ensure snapshots exist for every currently-locked index.
     * Used for backward compatibility with villagers that already had a lock mask stored before snapshots existed.
     */
    public static void ensureSnapshotsForLockedMask(Villager vill) {
        try {
            if (vill == null) return;
            MerchantOffers offers = vill.getOffers();
            if (offers == null || offers.isEmpty()) return;

            long mask = sanitizeMaskForSize(getMask(vill), offers.size());
            if (mask == 0L) return;

            for (int i = 0; i < offers.size() && i < 63; i++) {
                if ((mask & (1L << i)) == 0L) continue;
                if (hasLockedOfferSnapshot(vill, i)) continue;
                captureLockedOffer(vill, i);
            }
        } catch (Throwable ignored) {}
    }

    public static void clearLockedOfferSnapshot(Villager vill, int idx) {
        try {
            if (vill == null) return;

            CompoundTag pd = vill.getPersistentData();
            if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_LOCKED_OFFERS, CompoundTag.TAG_COMPOUND)) return;

            CompoundTag locked = root.getCompound(NBT_LOCKED_OFFERS);
            locked.remove(String.valueOf(idx));

            root.put(NBT_LOCKED_OFFERS, locked);
            pd.put(NBT_ROOT, root);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] cleared_snapshot villager={} idx={}", vill.getUUID(), idx);
        } catch (Throwable ignored) {}
    }

    /**
     * Enforces snapshots for currently-locked indices by overwriting the offer list entries.
     * Call this after ANY operation that might mutate offers (reroll, hoarder, vanilla updateTrades).
     *
     * @return number of offers restored.
     */
    public static int restoreLockedOffersFromSnapshots(Villager vill, MerchantOffers offers) {
        try {
            if (vill == null || offers == null) return 0;

            long mask = sanitizeMaskForSize(getMask(vill), offers.size());
            if (mask == 0L) return 0;

            CompoundTag locked = getLockedOffersCompound(vill);
            if (locked == null) return 0;

            int restored = 0;
            for (int i = 0; i < offers.size() && i < 63; i++) {
                if ((mask & (1L << i)) == 0L) continue;

                MerchantOffer cur = null;
                try { cur = offers.get(i); } catch (Throwable ignored) { cur = null; }

                CompoundTag wrap;
                try { wrap = locked.getCompound(String.valueOf(i)); } catch (Throwable t) { wrap = null; }
                if (wrap == null) continue;

                Tag offerTag;
                try { offerTag = wrap.get(TAG_WRAP_VALUE); } catch (Throwable t) { offerTag = null; }
                if (offerTag == null) continue;

                MerchantOffer decoded = decodeOffer(vill, offerTag);
                if (decoded == null) continue;

                // Preserve dynamic runtime fields from the CURRENT offer (uses, demand, specialPriceDiff),
                // so restoring the locked "identity" cannot be used to reset trade depletion.
                try {
                    if (cur != null) {
                        int curUses = safeGetUses(cur);
                        if (curUses >= 0) safeSetUses(decoded, curUses);

                        int curSpd = safeGetSpecialPriceDiff(cur);
                        safeSetSpecialPriceDiff(decoded, curSpd);

                        int curDemand = safeGetDemand(cur);
                        if (curDemand != Integer.MIN_VALUE) safeSetDemand(decoded, curDemand);
                    }
                } catch (Throwable ignored) {}

                try {
                    offers.set(i, decoded);
                    restored++;
                } catch (Throwable ignored) {}
            }

            if (restored > 0) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] restored villager={} restored={} mask={}",
                        vill.getUUID(), restored, Long.toUnsignedString(mask));
            }

            return restored;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [lock] restore_error villager={} err={}",
                    vill == null ? "null" : vill.getUUID(), t.toString());
            return 0;
        }
    }

    /**
     * Removes stored snapshots for indices that are no longer valid (size shrink) or no longer locked.
     */
    public static void sanitizeLockedOfferSnapshots(Villager vill, long mask, int size) {
        try {
            if (vill == null) return;
            if (size <= 0) {
                CompoundTag pd = vill.getPersistentData();
                if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return;
                CompoundTag root = pd.getCompound(NBT_ROOT);
                root.remove(NBT_LOCKED_OFFERS);
                pd.put(NBT_ROOT, root);
                return;
            }

            CompoundTag pd = vill.getPersistentData();
            if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_LOCKED_OFFERS, CompoundTag.TAG_COMPOUND)) return;

            CompoundTag locked = root.getCompound(NBT_LOCKED_OFFERS);
            boolean changed = false;

            for (String k : locked.getAllKeys()) {
                int idx;
                try { idx = Integer.parseInt(k); } catch (Throwable ignored) { idx = -1; }
                if (idx < 0 || idx >= size || idx >= 63) {
                    locked.remove(k);
                    changed = true;
                    continue;
                }
                if ((mask & (1L << idx)) == 0L) {
                    locked.remove(k);
                    changed = true;
                }
            }

            if (changed) {
                root.put(NBT_LOCKED_OFFERS, locked);
                pd.put(NBT_ROOT, root);
            }
        } catch (Throwable ignored) {}
    }

    public static long toggle(Villager vill, int idx) {
        try {
            long mask = getMask(vill);
            long bit = (1L << idx);
            long next = mask ^ bit;
            setMask(vill, next);
            return next;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] TradeLockState.toggle failed: {}", t.toString());
            return getMask(vill);
        }
    }

    /**
     * Clears bits larger than size (or if size smaller than 0, clears all).
     */
    public static long sanitizeMaskForSize(long mask, int size) {
        if (size <= 0) return 0L;
        if (size >= 63) return mask; // we only ever toggle indices < 63, but keep it safe.
        long allowed = (1L << size) - 1L;
        return mask & allowed;
    }

    private static CompoundTag getLockedOffersCompound(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag pd = vill.getPersistentData();
            if (!pd.contains(NBT_ROOT, CompoundTag.TAG_COMPOUND)) return null;
            CompoundTag root = pd.getCompound(NBT_ROOT);
            if (!root.contains(NBT_LOCKED_OFFERS, CompoundTag.TAG_COMPOUND)) return null;
            return root.getCompound(NBT_LOCKED_OFFERS);
        } catch (Throwable t) {
            return null;
        }
    }

    private static CompoundTag encodeOfferWrapped(Villager vill, MerchantOffer offer) {
        try {
            if (vill == null || offer == null) return null;
            if (!(vill.level() instanceof ServerLevel sl)) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, sl.registryAccess());
            var res = MerchantOffer.CODEC.encodeStart(ops, offer);
            Tag tag = res.result().orElse(null);
            if (tag == null) return null;

            CompoundTag wrap = new CompoundTag();
            wrap.put(TAG_WRAP_VALUE, tag);
            return wrap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static MerchantOffer decodeOffer(Villager vill, Tag offerTag) {
        try {
            if (vill == null || offerTag == null) return null;
            if (!(vill.level() instanceof ServerLevel sl)) return null;

            var ops = RegistryOps.create(NbtOps.INSTANCE, sl.registryAccess());
            var parsed = MerchantOffer.CODEC.parse(ops, offerTag);
            return parsed.result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeGetUses(MerchantOffer o) {
        try { return o == null ? -1 : o.getUses(); } catch (Throwable t) { return -1; }
    }

    private static void safeSetUses(MerchantOffer o, int uses) {
        try {
            if (o == null) return;
            // Try method first (if present in this version/mapping)
            try {
                var m = o.getClass().getMethod("setUses", int.class);
                m.setAccessible(true);
                m.invoke(o, uses);
                return;
            } catch (Throwable ignored) {}

            // Field fallback
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                try {
                    var f = c.getDeclaredField("uses");
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        f.setInt(o, uses);
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                    // keep walking
                } catch (Throwable ignored) {
                    return;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static int safeGetSpecialPriceDiff(MerchantOffer o) {
        try { return o == null ? 0 : o.getSpecialPriceDiff(); } catch (Throwable t) { return 0; }
    }

    private static void safeSetSpecialPriceDiff(MerchantOffer o, int v) {
        try {
            if (o == null) return;
            try {
                o.setSpecialPriceDiff(v);
                return;
            } catch (Throwable ignored) {}

            try {
                var m = o.getClass().getMethod("setSpecialPriceDiff", int.class);
                m.setAccessible(true);
                m.invoke(o, v);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static int safeGetDemand(MerchantOffer o) {
        try {
            if (o == null) return Integer.MIN_VALUE;
            try {
                var m = o.getClass().getMethod("getDemand");
                m.setAccessible(true);
                Object r = m.invoke(o);
                return (r instanceof Integer i) ? i : Integer.MIN_VALUE;
            } catch (Throwable ignored) {}

            // field fallback
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                try {
                    var f = c.getDeclaredField("demand");
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        return f.getInt(o);
                    }
                } catch (NoSuchFieldException ignored) {
                    // keep walking
                } catch (Throwable ignored) {
                    return Integer.MIN_VALUE;
                }
                c = c.getSuperclass();
            }

            return Integer.MIN_VALUE;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static void safeSetDemand(MerchantOffer o, int v) {
        try {
            if (o == null) return;

            try {
                var m = o.getClass().getMethod("setDemand", int.class);
                m.setAccessible(true);
                m.invoke(o, v);
                return;
            } catch (Throwable ignored) {}

            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                try {
                    var f = c.getDeclaredField("demand");
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        f.setInt(o, v);
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                    // keep walking
                } catch (Throwable ignored) {
                    return;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private TradeLockState() {}
}
