// neoforge\src\main\java\org\z2six\villageroverhaul\logic\TradeLockState.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class TradeLockState {

    private static final String NBT_ROOT = "villageroverhaul";
    private static final String NBT_LOCK_MASK = "locked_trade_mask";

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

    private TradeLockState() {}
}
