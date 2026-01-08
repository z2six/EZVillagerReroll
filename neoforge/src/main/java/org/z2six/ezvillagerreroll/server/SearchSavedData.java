// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/server/SearchSavedData.java
package org.z2six.ezvillagerreroll.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

import java.util.*;

/**
 * Persistent storage for ongoing villager auto-search tasks AND completed settlements awaiting payment.
 *
 * We store:
 * - activeTasks: villagerUuid -> TaskData
 * - settlements: villagerUuid -> SettlementData
 * - pendingDone: ownerUuid -> list of DoneData (optional delivery when owner logs in)
 */
public final class SearchSavedData extends SavedData {

    public static final String DATA_NAME = "ezvillagerreroll_search_tasks";

    public static final class TaskData {
        public UUID villagerUuid;
        public int villagerEntityId; // best-effort; may be stale across restarts
        public UUID ownerPlayerUuid;

        public long nextRerollGameTime;
        public int cooldownTicks;

        public boolean wasGlowingAtStart;

        public List<ItemStack> requested = new ArrayList<>();
        public Set<String> requestedKeys = new HashSet<>();
    }

    public static final class SettlementData {
        public UUID villagerUuid;
        public int villagerEntityId;
        public UUID ownerPlayerUuid;

        public long startedAtGameTime;
        public long completedAtGameTime;

        public int hourlyCost;
        public int finalCost;

        /**
         * Cached offers as ListTag of wrappers { "v": <MerchantOffer encoded tag> }.
         * This matches the safe pattern used elsewhere (registry-aware decoding will happen server-side).
         */
        public ListTag offers = new ListTag();
    }

    public static final class DoneData {
        public int villagerEntityId;
        public UUID villagerUuid;
        public long completedAtRealMillis;
    }

    private final Map<UUID, TaskData> activeTasks = new LinkedHashMap<>();
    private final Map<UUID, SettlementData> settlements = new LinkedHashMap<>();
    private final Map<UUID, List<DoneData>> pendingDoneByOwner = new LinkedHashMap<>();

    public SearchSavedData() {}

    public Map<UUID, TaskData> activeTasks() {
        return activeTasks;
    }

    public Map<UUID, SettlementData> settlements() {
        return settlements;
    }

    public Map<UUID, List<DoneData>> pendingDoneByOwner() {
        return pendingDoneByOwner;
    }

    // ------------------------------------------------------------
    // Integration hooks
    // ------------------------------------------------------------

    public static void loadIntoSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.importFromSavedData(server, data);
                EZVillagerReroll.LOG().info("[EZVR] SearchSavedData.loadIntoSearchService: imported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService: import failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.loadIntoSearchService failed", t);
        }
    }

    public static void saveFromSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.exportToSavedData(server, data);
                data.setDirty();
                EZVillagerReroll.LOG().info("[EZVR] SearchSavedData.saveFromSearchService: exported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService: export failed", t);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.saveFromSearchService failed", t);
        }
    }

    // ------------------------------------------------------------
    // Loading / Saving
    // ------------------------------------------------------------

    public static SearchSavedData get(ServerLevel overworld) {
        try {
            if (overworld == null) throw new IllegalArgumentException("overworld is null");

            return overworld.getDataStorage().computeIfAbsent(
                    new Factory<>(SearchSavedData::new, SearchSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.get failed", t);
            return new SearchSavedData();
        }
    }

    public static SearchSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        SearchSavedData data = new SearchSavedData();
        try {
            if (tag == null) return data;

            // Active tasks
            if (tag.contains("activeTasks", Tag.TAG_LIST)) {
                ListTag list = tag.getList("activeTasks", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    TaskData td = readTask(t, lookup);
                    if (td == null || td.villagerUuid == null) continue;
                    data.activeTasks.put(td.villagerUuid, td);
                }
            }

            // Settlements
            if (tag.contains("settlements", Tag.TAG_LIST)) {
                ListTag list = tag.getList("settlements", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    SettlementData sd = readSettlement(t);
                    if (sd == null || sd.villagerUuid == null) continue;
                    data.settlements.put(sd.villagerUuid, sd);
                }
            }

            // Pending done notifications
            if (tag.contains("pendingDone", Tag.TAG_LIST)) {
                ListTag list = tag.getList("pendingDone", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag root = list.getCompound(i);
                    UUID owner = readUuid(root, "owner");
                    if (owner == null) continue;

                    List<DoneData> dones = new ArrayList<>();
                    if (root.contains("items", Tag.TAG_LIST)) {
                        ListTag items = root.getList("items", Tag.TAG_COMPOUND);
                        for (int j = 0; j < items.size(); j++) {
                            CompoundTag dtag = items.getCompound(j);
                            DoneData dd = new DoneData();
                            dd.villagerEntityId = dtag.getInt("villagerEntityId");
                            dd.villagerUuid = readUuid(dtag, "villagerUuid");
                            dd.completedAtRealMillis = dtag.getLong("completedAtRealMillis");
                            if (dd.villagerUuid != null) dones.add(dd);
                        }
                    }
                    if (!dones.isEmpty()) data.pendingDoneByOwner.put(owner, dones);
                }
            }

            EZVillagerReroll.LOG().info("[EZVR] SearchSavedData loaded: activeTasks={} settlements={} pendingDoneOwners={}",
                    data.activeTasks.size(), data.settlements.size(), data.pendingDoneByOwner.size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        try {
            if (tag == null) tag = new CompoundTag();

            // Active tasks
            ListTag active = new ListTag();
            for (TaskData td : activeTasks.values()) {
                CompoundTag t = writeTask(td, lookup);
                if (t != null) active.add(t);
            }
            tag.put("activeTasks", active);

            // Settlements
            ListTag settles = new ListTag();
            for (SettlementData sd : settlements.values()) {
                CompoundTag t = writeSettlement(sd);
                if (t != null) settles.add(t);
            }
            tag.put("settlements", settles);

            // Pending done
            ListTag pending = new ListTag();
            for (Map.Entry<UUID, List<DoneData>> en : pendingDoneByOwner.entrySet()) {
                UUID owner = en.getKey();
                List<DoneData> list = en.getValue();
                if (owner == null || list == null || list.isEmpty()) continue;

                CompoundTag root = new CompoundTag();
                writeUuid(root, "owner", owner);

                ListTag items = new ListTag();
                for (DoneData dd : list) {
                    if (dd == null || dd.villagerUuid == null) continue;
                    CompoundTag dtag = new CompoundTag();
                    dtag.putInt("villagerEntityId", dd.villagerEntityId);
                    writeUuid(dtag, "villagerUuid", dd.villagerUuid);
                    dtag.putLong("completedAtRealMillis", dd.completedAtRealMillis);
                    items.add(dtag);
                }
                root.put("items", items);
                pending.add(root);
            }
            tag.put("pendingDone", pending);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.save failed", t);
        }
        return tag;
    }

    // ------------------------------------------------------------
    // Task (de)serialization helpers
    // ------------------------------------------------------------

    private static TaskData readTask(CompoundTag t, HolderLookup.Provider lookup) {
        try {
            if (t == null) return null;

            TaskData td = new TaskData();
            td.villagerUuid = readUuid(t, "villagerUuid");
            td.villagerEntityId = t.getInt("villagerEntityId");
            td.ownerPlayerUuid = readUuid(t, "ownerPlayerUuid");

            td.nextRerollGameTime = t.getLong("nextRerollGameTime");
            td.cooldownTicks = Math.max(1, t.getInt("cooldownTicks"));

            td.wasGlowingAtStart = t.getBoolean("wasGlowingAtStart");

            td.requested.clear();
            if (t.contains("requested", Tag.TAG_LIST)) {
                ListTag req = t.getList("requested", Tag.TAG_COMPOUND);
                int n = Math.min(4096, req.size());
                for (int i = 0; i < n; i++) {
                    CompoundTag st = req.getCompound(i);
                    ItemStack stack;
                    try {
                        stack = ItemStack.parseOptional(lookup, st);
                    } catch (Throwable parseErr) {
                        stack = ItemStack.EMPTY;
                    }
                    if (stack == null) stack = ItemStack.EMPTY;
                    if (!stack.isEmpty()) td.requested.add(stack);
                }
            }

            td.requestedKeys.clear();
            if (t.contains("requestedKeys", Tag.TAG_LIST)) {
                ListTag keys = t.getList("requestedKeys", Tag.TAG_STRING);
                for (int i = 0; i < keys.size(); i++) {
                    String s = keys.getString(i);
                    if (s != null && !s.isBlank()) td.requestedKeys.add(s);
                }
            } else {
                for (ItemStack s : td.requested) {
                    if (s == null || s.isEmpty()) continue;
                    td.requestedKeys.add(CatalogBuilder.keyOf(s));
                }
            }

            if (td.villagerUuid == null || td.ownerPlayerUuid == null || td.requestedKeys.isEmpty()) {
                EZVillagerReroll.LOG().warn("[EZVR] SearchSavedData.readTask: invalid record; skipping (villagerUuid={}, owner={}, keys={})",
                        td.villagerUuid, td.ownerPlayerUuid, td.requestedKeys.size());
                return null;
            }

            return td;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.readTask failed", e);
            return null;
        }
    }

    private static CompoundTag writeTask(TaskData td, HolderLookup.Provider lookup) {
        try {
            if (td == null || td.villagerUuid == null || td.ownerPlayerUuid == null) return null;

            CompoundTag t = new CompoundTag();
            writeUuid(t, "villagerUuid", td.villagerUuid);
            t.putInt("villagerEntityId", td.villagerEntityId);
            writeUuid(t, "ownerPlayerUuid", td.ownerPlayerUuid);

            t.putLong("nextRerollGameTime", td.nextRerollGameTime);
            t.putInt("cooldownTicks", Math.max(1, td.cooldownTicks));

            t.putBoolean("wasGlowingAtStart", td.wasGlowingAtStart);

            ListTag req = new ListTag();
            int n = td.requested == null ? 0 : Math.min(4096, td.requested.size());
            for (int i = 0; i < n; i++) {
                ItemStack s = td.requested.get(i);
                if (s == null || s.isEmpty()) continue;

                try {
                    Tag tag = s.saveOptional(lookup);
                    if (tag instanceof CompoundTag st) {
                        req.add(st);
                    } else if (tag != null) {
                        EZVillagerReroll.LOG().debug(
                                "[EZVR] SearchSavedData.writeTask: saveOptional returned non-compound Tag type={} for stack={}, skipping.",
                                tag.getClass().getName(),
                                String.valueOf(s)
                        );
                    }
                } catch (Throwable saveErr) {
                    EZVillagerReroll.LOG().debug("[EZVR] SearchSavedData.writeTask: failed to save stack (soft): {}", saveErr.toString());
                }
            }
            t.put("requested", req);

            ListTag keys = new ListTag();
            if (td.requestedKeys != null) {
                for (String k : td.requestedKeys) {
                    if (k == null || k.isBlank()) continue;
                    keys.add(net.minecraft.nbt.StringTag.valueOf(k));
                }
            }
            t.put("requestedKeys", keys);

            return t;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.writeTask failed", e);
            return null;
        }
    }

    private static SettlementData readSettlement(CompoundTag t) {
        try {
            if (t == null) return null;

            SettlementData sd = new SettlementData();
            sd.villagerUuid = readUuid(t, "villagerUuid");
            sd.villagerEntityId = t.getInt("villagerEntityId");
            sd.ownerPlayerUuid = readUuid(t, "ownerPlayerUuid");

            sd.startedAtGameTime = t.getLong("startedAtGameTime");
            sd.completedAtGameTime = t.getLong("completedAtGameTime");

            sd.hourlyCost = Math.max(0, t.getInt("hourlyCost"));
            sd.finalCost = Math.max(0, t.getInt("finalCost"));

            sd.offers = new ListTag();
            if (t.contains("offers", Tag.TAG_LIST)) {
                ListTag list = t.getList("offers", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    try {
                        CompoundTag wrap = list.getCompound(i);
                        if (wrap != null) sd.offers.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }

            if (sd.villagerUuid == null) return null;
            return sd;
        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.readSettlement failed", e);
            return null;
        }
    }

    private static CompoundTag writeSettlement(SettlementData sd) {
        try {
            if (sd == null || sd.villagerUuid == null) return null;

            CompoundTag t = new CompoundTag();
            writeUuid(t, "villagerUuid", sd.villagerUuid);
            t.putInt("villagerEntityId", sd.villagerEntityId);
            if (sd.ownerPlayerUuid != null) writeUuid(t, "ownerPlayerUuid", sd.ownerPlayerUuid);

            t.putLong("startedAtGameTime", sd.startedAtGameTime);
            t.putLong("completedAtGameTime", sd.completedAtGameTime);

            t.putInt("hourlyCost", Math.max(0, sd.hourlyCost));
            t.putInt("finalCost", Math.max(0, sd.finalCost));

            ListTag copy = new ListTag();
            if (sd.offers != null) {
                for (int i = 0; i < sd.offers.size(); i++) {
                    try {
                        CompoundTag wrap = sd.offers.getCompound(i);
                        if (wrap != null) copy.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }
            t.put("offers", copy);

            return t;

        } catch (Throwable e) {
            EZVillagerReroll.LOG().error("[EZVR] SearchSavedData.writeSettlement failed", e);
            return null;
        }
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        try {
            if (tag == null || key == null) return null;
            if (!tag.contains(key + "Most", Tag.TAG_LONG) || !tag.contains(key + "Least", Tag.TAG_LONG)) return null;
            long most = tag.getLong(key + "Most");
            long least = tag.getLong(key + "Least");
            return new UUID(most, least);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeUuid(CompoundTag tag, String key, UUID uuid) {
        try {
            if (tag == null || key == null || uuid == null) return;
            tag.putLong(key + "Most", uuid.getMostSignificantBits());
            tag.putLong(key + "Least", uuid.getLeastSignificantBits());
        } catch (Throwable ignored) {}
    }
}
