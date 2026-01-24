// neoforge/src/main/java/org/z2six/villageroverhaul/server/PlayerAutoSearchCostSavedData.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent, player-global "accumulated auto-search value" storage.
 *
 * Keyed by player UUID, then by CatalogBuilder.keyOf(ItemStack) string.
 * Values are stored in "V" units:
 * - 1 offer slot rerolled = 1V
 */
public final class PlayerAutoSearchCostSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_autosearch_costs";

    private final Map<UUID, Map<String, Long>> byPlayer = new LinkedHashMap<>();

    public Map<UUID, Map<String, Long>> byPlayer() {
        return byPlayer;
    }

    public static PlayerAutoSearchCostSavedData get(ServerLevel level) {
        try {
            if (level == null) return new PlayerAutoSearchCostSavedData();
            return level.getDataStorage().computeIfAbsent(
                    new Factory<>(PlayerAutoSearchCostSavedData::new, PlayerAutoSearchCostSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostSavedData.get failed", t);
            return new PlayerAutoSearchCostSavedData();
        }
    }

    public static PlayerAutoSearchCostSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        PlayerAutoSearchCostSavedData data = new PlayerAutoSearchCostSavedData();
        try {
            if (tag == null) return data;
            if (!tag.contains("players", Tag.TAG_LIST)) return data;

            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag p = players.getCompound(i);
                if (p == null || !p.hasUUID("uuid")) continue;
                UUID uuid = p.getUUID("uuid");

                Map<String, Long> map = new LinkedHashMap<>();
                if (p.contains("entries", Tag.TAG_LIST)) {
                    ListTag entries = p.getList("entries", Tag.TAG_COMPOUND);
                    for (int j = 0; j < entries.size(); j++) {
                        CompoundTag e = entries.getCompound(j);
                        if (e == null) continue;
                        String k = e.getString("k");
                        if (k == null || k.isBlank()) continue;
                        long v = 0L;
                        try { v = e.getLong("v"); } catch (Throwable ignored) { v = 0L; }
                        if (v <= 0L) {
                            // legacy field name (was "m" when we stored milli-cost)
                            try { v = e.getLong("m"); } catch (Throwable ignored) { v = 0L; }
                        }
                        if (v <= 0L) continue;
                        map.put(k, v);
                    }
                }

                if (!map.isEmpty()) data.byPlayer.put(uuid, map);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (tag == null) tag = new CompoundTag();
        try {
            ListTag players = new ListTag();
            for (Map.Entry<UUID, Map<String, Long>> en : byPlayer.entrySet()) {
                UUID uuid = en.getKey();
                Map<String, Long> map = en.getValue();
                if (uuid == null || map == null || map.isEmpty()) continue;

                CompoundTag p = new CompoundTag();
                p.putUUID("uuid", uuid);

                ListTag entries = new ListTag();
                for (Map.Entry<String, Long> me : map.entrySet()) {
                    String k = me.getKey();
                    long v = me.getValue() == null ? 0L : me.getValue();
                    if (k == null || k.isBlank()) continue;
                    if (v <= 0L) continue;
                    CompoundTag e = new CompoundTag();
                    e.putString("k", k);
                    e.putLong("v", v);
                    entries.add(e);
                }

                p.put("entries", entries);
                players.add(p);
            }
            tag.put("players", players);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerAutoSearchCostSavedData.save failed", t);
        }
        return tag;
    }
}
