package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.farming.FarmingSettings;

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

    public static List<String> sanitizeItemIds(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        int n = Math.min(512, ids.size());
        for (int i = 0; i < n; i++) {
            String raw = ids.get(i);
            if (raw == null) continue;
            raw = raw.trim().toLowerCase(Locale.ROOT);
            if (raw.isEmpty()) continue;
            // Best-effort sanity: must parse as a ResourceLocation
            try {
                ResourceLocation.parse(raw);
            } catch (Throwable ignored) {
                continue;
            }
            if (!out.contains(raw)) out.add(raw);
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

