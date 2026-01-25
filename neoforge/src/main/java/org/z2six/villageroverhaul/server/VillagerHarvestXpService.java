package org.z2six.villageroverhaul.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

/**
 * Awards villager XP based on planted block counts.
 * Triggered from the vanilla HarvestFarmland goal via mixin (block placement interception).
 */
public final class VillagerHarvestXpService {

    private VillagerHarvestXpService() {}

    private static final String PD_KEY = "ezvr_plant_item_progress";
    private static final String PD_LAST_LOG_MS = "ezvr_plant_last_log_ms";

    public static void onPlanted(Villager vill, int plantedCount) {
        try {
            if (vill == null) return;
            if (plantedCount <= 0) return;

            // Only while vanilla AI is allowed to run (NEUTRAL).
            if (VillagerBrain.getMode(vill) != VillagerBrain.Mode.NEUTRAL) {
                ezvr$maybeInfo(vill, "skip:not_neutral");
                return;
            }
            if (VillagerBrain.isStorageActive(vill)) {
                ezvr$maybeInfo(vill, "skip:storage_active");
                return;
            }

            try { VillagerHistoryService.addFarmingPlanted(vill, plantedCount, false); } catch (Throwable ignored) {}

            int xpPerUnit = Math.max(0, ServerConfig.farmingHarvestXp);
            if (xpPerUnit <= 0) {
                ezvr$maybeInfo(vill, "skip:xp_disabled");
                return;
            }

            int itemsPerXp = Math.max(1, ServerConfig.farmingHarvestItemsPerXp);

            CompoundTag pd = vill.getPersistentData();
            int progress = 0;
            try { progress = Math.max(0, pd.getInt(PD_KEY)); } catch (Throwable ignored) { progress = 0; }

            long next = (long) progress + (long) plantedCount;
            if (next < 0L) next = 0L;
            if (next > Integer.MAX_VALUE) next = Integer.MAX_VALUE;

            int total = (int) next;
            int units = total / itemsPerXp;
            int rem = total - units * itemsPerXp;

            if (rem < 0) rem = 0;
            pd.putInt(PD_KEY, rem);

            int addXp = units * xpPerUnit;
            if (addXp > 0) {
                VillagerXpService.grantXp(vill, addXp, null);
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Farming XP: villager={} planted+{} progressRem={} threshold={} grantXp={}",
                        vill.getUUID(), plantedCount, rem, itemsPerXp, addXp
                );
            }
        } catch (Throwable ignored) {}
    }

    private static void ezvr$maybeInfo(Villager vill, String reason) {
        try {
            CompoundTag pd = vill.getPersistentData();
            long now = System.currentTimeMillis();
            long last = 0L;
            try { last = pd.getLong(PD_LAST_LOG_MS); } catch (Throwable ignored) { last = 0L; }
            if (now - last < 10_000L) return; // rate limit per villager
            pd.putLong(PD_LAST_LOG_MS, now);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Farming XP planting ignored: villager={} reason={}", vill.getUUID(), reason);
        } catch (Throwable ignored) {}
    }
}
