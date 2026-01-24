// neoforge/src/main/java/org/z2six/villageroverhaul/server/PlayerAutoSearchCostService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.*;

public final class PlayerAutoSearchCostService {

    private PlayerAutoSearchCostService() {}

    public static long getValueV(MinecraftServer server, UUID playerUuid, String itemKey) {
        try {
            if (server == null || playerUuid == null || itemKey == null || itemKey.isBlank()) return 0L;
            ServerLevel level = server.overworld();
            if (level == null) return 0L;
            PlayerAutoSearchCostSavedData data = PlayerAutoSearchCostSavedData.get(level);
            Map<String, Long> map = data.byPlayer().get(playerUuid);
            if (map == null) return 0L;
            Long v = map.get(itemKey);
            return v == null ? 0L : Math.max(0L, v);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void addValuesV(MinecraftServer server, UUID playerUuid, Map<String, Long> addByKey) {
        try {
            if (server == null || playerUuid == null) return;
            if (addByKey == null || addByKey.isEmpty()) return;
            ServerLevel level = server.overworld();
            if (level == null) return;

            PlayerAutoSearchCostSavedData data = PlayerAutoSearchCostSavedData.get(level);
            Map<String, Long> map = data.byPlayer().computeIfAbsent(playerUuid, k -> new LinkedHashMap<>());

            boolean changed = false;
            for (Map.Entry<String, Long> en : addByKey.entrySet()) {
                String key = en.getKey();
                long delta = en.getValue() == null ? 0L : en.getValue();
                if (key == null || key.isBlank()) continue;
                if (delta <= 0L) continue;

                long prev = map.getOrDefault(key, 0L);
                long next = prev + delta;
                if (next < 0L) next = Long.MAX_VALUE;
                map.put(key, next);
                changed = true;
            }

            if (changed) data.setDirty();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostService.addValuesV failed", t);
        }
    }

    /**
     * Computes per-key increment for one auto-search reroll attempt (and applies it) in V units.
     *
     * Total V per attempt = offersRerolledPerReroll (1 offer rerolled = 1V).
     * V is then distributed evenly across requested keys.
     */
    public static void addAttemptValueForRequestedKeys(MinecraftServer server, UUID playerUuid, Set<String> requestedKeys, int offersRerolledPerReroll) {
        try {
            if (server == null || playerUuid == null) return;
            if (requestedKeys == null || requestedKeys.isEmpty()) return;

            int slots = Math.max(0, offersRerolledPerReroll);
            if (slots <= 0) return;

            List<String> keys = new ArrayList<>(requestedKeys.size());
            for (String k : requestedKeys) {
                if (k == null || k.isBlank()) continue;
                keys.add(k);
            }
            if (keys.isEmpty()) return;
            keys.sort(String::compareToIgnoreCase);

            long totalV = (long) slots;
            if (totalV <= 0L) return;

            int n = keys.size();
            long perItem = totalV / (long) n;
            long rem = totalV % (long) n;

            if (perItem <= 0L && rem <= 0L) return;

            Map<String, Long> add = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                long delta = perItem + (i < rem ? 1L : 0L);
                if (delta <= 0L) continue;
                add.put(keys.get(i), delta);
            }

            if (!add.isEmpty()) addValuesV(server, playerUuid, add);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostService.addAttemptValueForRequestedKeys failed", t);
        }
    }

    public static List<Long> buildValuesForCatalog(MinecraftServer server, UUID playerUuid, List<ItemStack> catalog) {
        try {
            if (server == null || playerUuid == null) return List.of();
            if (catalog == null || catalog.isEmpty()) return List.of();

            ArrayList<Long> out = new ArrayList<>(catalog.size());
            for (ItemStack s : catalog) {
                String key = (s == null || s.isEmpty()) ? null : CatalogBuilder.keyOf(s);
                out.add(getValueV(server, playerUuid, key));
            }
            return out;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostService.buildValuesForCatalog failed", t);
            return List.of();
        }
    }

    public static void clearValuesForKeys(MinecraftServer server, UUID playerUuid, Collection<String> keys) {
        try {
            if (server == null || playerUuid == null) return;
            if (keys == null || keys.isEmpty()) return;
            ServerLevel level = server.overworld();
            if (level == null) return;

            PlayerAutoSearchCostSavedData data = PlayerAutoSearchCostSavedData.get(level);
            Map<String, Long> map = data.byPlayer().get(playerUuid);
            if (map == null || map.isEmpty()) return;

            boolean changed = false;
            for (String k : keys) {
                if (k == null || k.isBlank()) continue;
                if (!map.containsKey(k)) continue;
                map.remove(k);
                changed = true;
            }
            if (changed) data.setDirty();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostService.clearValuesForKeys failed", t);
        }
    }
}
