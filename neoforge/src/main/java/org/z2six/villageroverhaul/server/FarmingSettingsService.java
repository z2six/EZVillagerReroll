package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.logic.VillagerTraitEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-villager farming/storage settings + registered chest location.
 */
public final class FarmingSettingsService {

    private FarmingSettingsService() {}

    private static final String TAG_ROOT = "ezvr_farming";

    private static final String K_SETTINGS = "settings";

    private static final String K_DIM = "dim";
    private static final String K_X = "x";
    private static final String K_Y = "y";
    private static final String K_Z = "z";
    private static final String K_IS_ENDER = "isEnder";

    // Withdraw chest
    private static final String K_WDIM = "wdim";
    private static final String K_WX = "wx";
    private static final String K_WY = "wy";
    private static final String K_WZ = "wz";
    private static final String K_WIS_ENDER = "wisEnder";

    // Manual farming workstation
    private static final String K_MDIM = "mdim";
    private static final String K_MX = "mx";
    private static final String K_MY = "my";
    private static final String K_MZ = "mz";

    public static FarmingSettings getSettings(Villager vill) {
        try {
            if (vill == null) return new FarmingSettings();
            CompoundTag root = getOrCreateRoot(vill);
            CompoundTag st = root.contains(K_SETTINGS, Tag.TAG_COMPOUND) ? root.getCompound(K_SETTINGS) : new CompoundTag();
            return FarmingSettings.fromTag(st);
        } catch (Throwable ignored) {
            return new FarmingSettings();
        }
    }

    public static void setSettings(Villager vill, FarmingSettings settings) {
        try {
            if (vill == null || settings == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.put(K_SETTINGS, settings.toTag());
        } catch (Throwable ignored) {}
    }

    public static void setRegisteredChest(Villager vill, String dimId, int x, int y, int z, boolean isEnderChest) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_DIM, dimId == null ? "" : dimId);
            root.putInt(K_X, x);
            root.putInt(K_Y, y);
            root.putInt(K_Z, z);
            root.putBoolean(K_IS_ENDER, isEnderChest);
        } catch (Throwable ignored) {}
    }

    public static void setRegisteredWithdrawChest(Villager vill, String dimId, int x, int y, int z, boolean isEnderChest) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_WDIM, dimId == null ? "" : dimId);
            root.putInt(K_WX, x);
            root.putInt(K_WY, y);
            root.putInt(K_WZ, z);
            root.putBoolean(K_WIS_ENDER, isEnderChest);
        } catch (Throwable ignored) {}
    }

    public static boolean hasRegisteredChest(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_DIM);
            return dim != null && !dim.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasRegisteredWithdrawChest(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_WDIM);
            return dim != null && !dim.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public record RegisteredChest(String dimId, int x, int y, int z, boolean isEnderChest) {}

    public static RegisteredChest getRegisteredChest(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_DIM);
            if (dim == null || dim.isBlank()) return null;
            return new RegisteredChest(dim, root.getInt(K_X), root.getInt(K_Y), root.getInt(K_Z), root.getBoolean(K_IS_ENDER));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static RegisteredChest getRegisteredWithdrawChest(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_WDIM);
            if (dim == null || dim.isBlank()) return null;
            return new RegisteredChest(dim, root.getInt(K_WX), root.getInt(K_WY), root.getInt(K_WZ), root.getBoolean(K_WIS_ENDER));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setRegisteredWorkstation(Villager vill, String dimId, int x, int y, int z) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreateRoot(vill);
            root.putString(K_MDIM, dimId == null ? "" : dimId);
            root.putInt(K_MX, x);
            root.putInt(K_MY, y);
            root.putInt(K_MZ, z);
        } catch (Throwable ignored) {}
    }

    public static boolean hasRegisteredWorkstation(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_MDIM);
            return dim != null && !dim.isBlank();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public record RegisteredWorkstation(String dimId, int x, int y, int z) {}

    public static RegisteredWorkstation getRegisteredWorkstation(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag root = getOrCreateRoot(vill);
            String dim = root.getString(K_MDIM);
            if (dim == null || dim.isBlank()) return null;
            return new RegisteredWorkstation(dim, root.getInt(K_MX), root.getInt(K_MY), root.getInt(K_MZ));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Returns the effective workstation for manual farming:
     * - If the player registered one via our UI, use that.
     * - Otherwise fall back to the villager's vanilla JOB_SITE memory (job site block).
     *
     * Returns null if none exists or if it's not in the given level dimension.
     */
    public static RegisteredWorkstation getEffectiveWorkstation(ServerLevel level, Villager vill) {
        try {
            if (level == null || vill == null) return null;

            String dim = "";
            try { dim = String.valueOf(level.dimension().location()); } catch (Throwable ignored) { dim = ""; }

            // Prefer explicit registered workstation (same-dim only)
            RegisteredWorkstation reg = getRegisteredWorkstation(vill);
            if (reg != null && reg.dimId() != null && !reg.dimId().isBlank() && reg.dimId().equals(dim)) {
                return reg;
            }

            // Fall back to vanilla job site (brain memory)
            try {
                var brain = vill.getBrain();
                if (brain != null && brain.hasMemoryValue(MemoryModuleType.JOB_SITE)) {
                    var opt = brain.getMemory(MemoryModuleType.JOB_SITE);
                    if (opt != null && opt.isPresent()) {
                        GlobalPos gp = opt.get();
                        if (gp != null && gp.dimension() != null && gp.pos() != null) {
                            String d2 = "";
                            try { d2 = String.valueOf(gp.dimension().location()); } catch (Throwable ignored) { d2 = ""; }
                            if (d2.equals(dim)) {
                                BlockPos p = gp.pos();
                                return new RegisteredWorkstation(dim, p.getX(), p.getY(), p.getZ());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int getEffectiveManualFarmingRange(Villager vill) {
        try {
            int maxAllowed = getMaxManualFarmingRange(vill);
            int requested = maxAllowed;
            try {
                FarmingSettings s = getSettings(vill);
                if (s != null) requested = Math.max(1, s.manualRange);
            } catch (Throwable ignored) {
                requested = maxAllowed;
            }
            int out = Math.min(requested, maxAllowed);
            if (out < 1) out = 1;
            if (out > 64) out = 64;
            return out;
        } catch (Throwable ignored) {
            return Math.max(1, Math.min(64, ServerConfig.manualFarmBaseRange));
        }
    }

    public static int getMaxManualFarmingRange(Villager vill) {
        try {
            int base = Math.max(1, Math.min(64, ServerConfig.manualFarmBaseRange));
            double pct = 0.0;
            try { pct = VillagerTraitEffects.rangerPct(vill); } catch (Throwable ignored) { pct = 0.0; }
            double mult = 1.0 + (pct / 100.0);
            if (Double.isNaN(mult) || Double.isInfinite(mult)) mult = 1.0;
            if (mult < 0.0) mult = 0.0;
            long out = Math.round(base * mult);
            if (out < 1L) out = 1L;
            if (out > 64L) out = 64L;
            return (int) out;
        } catch (Throwable ignored) {
            return Math.max(1, Math.min(64, ServerConfig.manualFarmBaseRange));
        }
    }

    public static boolean isWithinManualFarmingArea(ServerLevel level, Villager vill, BlockPos pos, boolean circular) {
        try {
            if (level == null || vill == null || pos == null) return false;
            RegisteredWorkstation ws = getEffectiveWorkstation(level, vill);
            if (ws == null) return false;
            int r = getEffectiveManualFarmingRange(vill);

            int dx = pos.getX() - ws.x();
            int dz = pos.getZ() - ws.z();

            if (circular) {
                long dist2 = (long) dx * dx + (long) dz * dz;
                long rr = (long) r * r;
                return dist2 <= rr;
            } else {
                int adx = Math.abs(dx);
                int adz = Math.abs(dz);
                return adx <= r && adz <= r;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static List<FarmingSettings.ItemRule> sanitizeRules(List<FarmingSettings.ItemRule> rules) {
        List<FarmingSettings.ItemRule> out = new ArrayList<>();
        if (rules == null) return out;

        int n = Math.min(512, rules.size());
        for (int i = 0; i < n; i++) {
            FarmingSettings.ItemRule r = rules.get(i);
            if (r == null || r.itemId == null) continue;

            String raw = r.itemId.trim().toLowerCase(Locale.ROOT);
            if (raw.isEmpty()) continue;

            try {
                ResourceLocation.parse(raw);
            } catch (Throwable ignored) {
                continue;
            }

            boolean exists = false;
            for (FarmingSettings.ItemRule e : out) {
                if (e != null && e.itemId != null && e.itemId.equalsIgnoreCase(raw)) { exists = true; break; }
            }
            if (exists) continue;

            int stacks = Math.max(0, r.stacksThreshold);
            int keep = Math.max(0, r.keepStacks);
            out.add(new FarmingSettings.ItemRule(raw, stacks, keep));
        }

        return out;
    }

    public static List<String> sanitizeItemIds(List<String> itemIds) {
        List<String> out = new ArrayList<>();
        if (itemIds == null) return out;

        int n = Math.min(512, itemIds.size());
        for (int i = 0; i < n; i++) {
            String raw = itemIds.get(i);
            if (raw == null) continue;
            String norm = raw.trim().toLowerCase(Locale.ROOT);
            if (norm.isEmpty()) continue;

            try {
                ResourceLocation.parse(norm);
            } catch (Throwable ignored) {
                continue;
            }

            boolean exists = false;
            for (String e : out) {
                if (e != null && e.equalsIgnoreCase(norm)) { exists = true; break; }
            }
            if (exists) continue;

            out.add(norm);
        }

        return out;
    }

    private static CompoundTag getOrCreateRoot(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) {
            pd.put(TAG_ROOT, new CompoundTag());
        }
        return pd.getCompound(TAG_ROOT);
    }
}
