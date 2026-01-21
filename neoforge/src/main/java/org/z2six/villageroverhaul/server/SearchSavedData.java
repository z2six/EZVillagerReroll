// neoforge\src\main\java\org\z2six\villageroverhaul\server\SearchSavedData.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.*;

/**
 * Persistent storage for ongoing villager auto-search tasks AND completed settlements awaiting payment.
 */
public final class SearchSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_search_tasks";

    public static final class TaskData {
        public UUID villagerUuid;
        public int villagerEntityId;
        public UUID ownerPlayerUuid;

        public long nextRerollGameTime;
        public int cooldownTicks;

        public boolean wasGlowingAtStart;

        public List<ItemStack> requested = new ArrayList<>();
        public Set<String> requestedKeys = new HashSet<>();

        // snapshot at START
        public ListTag offersBeforeTag = new ListTag();
        public long lockMaskBefore = 0L;

        // number of successful rerolls performed so far during this auto-search task
        public int rerollCount = 0;
    }

    public static final class SettlementData {
        public UUID villagerUuid;
        public int villagerEntityId;
        public UUID ownerPlayerUuid;

        public long startedAtGameTime;
        public long completedAtGameTime;

        public int hourlyCost;
        public int finalCost;

        // ONLY snapshot we persist: offers BEFORE auto-search started
        public ListTag offersBeforeTag = new ListTag();

        // lock state at START
        public long lockMaskBefore = 0L;

        // requested targets for yellow highlight
        public List<String> requestedTargets = new ArrayList<>();

        public int totalVillagerXp = 0;
        public int rerollCount = 0;
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

    public static void loadIntoSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.loadIntoSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.importFromSavedData(server, data);
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchSavedData.loadIntoSearchService: imported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.loadIntoSearchService: import failed", t);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.loadIntoSearchService failed", t);
        }
    }

    public static void saveFromSearchService(MinecraftServer server) {
        try {
            if (server == null) return;

            ServerLevel overworld;
            try {
                overworld = server.overworld();
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.saveFromSearchService: cannot get overworld", t);
                return;
            }
            if (overworld == null) return;

            SearchSavedData data = get(overworld);

            try {
                SearchService.exportToSavedData(server, data);
                data.setDirty();
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchSavedData.saveFromSearchService: exported activeTasks={} settlements={}",
                        data.activeTasks.size(), data.settlements.size());
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.saveFromSearchService: export failed", t);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.saveFromSearchService failed", t);
        }
    }

    public static SearchSavedData get(ServerLevel overworld) {
        try {
            if (overworld == null) throw new IllegalArgumentException("overworld is null");

            return overworld.getDataStorage().computeIfAbsent(
                    new Factory<>(SearchSavedData::new, SearchSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.get failed", t);
            return new SearchSavedData();
        }
    }

    public static SearchSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        SearchSavedData data = new SearchSavedData();
        try {
            if (tag == null) return data;

            if (tag.contains("activeTasks", Tag.TAG_LIST)) {
                ListTag list = tag.getList("activeTasks", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    TaskData td = readTask(t, lookup);
                    if (td == null || td.villagerUuid == null) continue;
                    data.activeTasks.put(td.villagerUuid, td);
                }
            }

            if (tag.contains("settlements", Tag.TAG_LIST)) {
                ListTag list = tag.getList("settlements", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag t = list.getCompound(i);
                    SettlementData sd = readSettlement(t);
                    if (sd == null || sd.villagerUuid == null) continue;
                    data.settlements.put(sd.villagerUuid, sd);
                }
            }

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

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchSavedData loaded: activeTasks={} settlements={} pendingDoneOwners={}",
                    data.activeTasks.size(), data.settlements.size(), data.pendingDoneByOwner.size());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        try {
            if (tag == null) tag = new CompoundTag();

            ListTag active = new ListTag();
            for (TaskData td : activeTasks.values()) {
                CompoundTag t = writeTask(td, lookup);
                if (t != null) active.add(t);
            }
            tag.put("activeTasks", active);

            ListTag settles = new ListTag();
            for (SettlementData sd : settlements.values()) {
                CompoundTag t = writeSettlement(sd);
                if (t != null) settles.add(t);
            }
            tag.put("settlements", settles);

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
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.save failed", t);
        }
        return tag;
    }

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

            td.offersBeforeTag = new ListTag();
            if (t.contains("offersBeforeTag", Tag.TAG_LIST)) {
                ListTag list = t.getList("offersBeforeTag", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    try {
                        CompoundTag wrap = list.getCompound(i);
                        if (wrap != null) td.offersBeforeTag.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }

            td.lockMaskBefore = 0L;
            try { td.lockMaskBefore = t.getLong("lockMaskBefore"); } catch (Throwable ignored) { td.lockMaskBefore = 0L; }

            // 
            td.rerollCount = 0;
            try { td.rerollCount = Math.max(0, t.getInt("rerollCount")); } catch (Throwable ignored) { td.rerollCount = 0; }

            if (td.villagerUuid == null || td.ownerPlayerUuid == null || td.requestedKeys.isEmpty()) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] SearchSavedData.readTask: invalid record; skipping (villagerUuid={}, owner={}, keys={})",
                        td.villagerUuid, td.ownerPlayerUuid, td.requestedKeys.size());
                return null;
            }

            return td;

        } catch (Throwable e) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.readTask failed", e);
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
                    }
                } catch (Throwable saveErr) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchSavedData.writeTask: failed to save stack (soft): {}", saveErr.toString());
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

            ListTag before = new ListTag();
            if (td.offersBeforeTag != null) {
                for (int i = 0; i < td.offersBeforeTag.size(); i++) {
                    try {
                        CompoundTag wrap = td.offersBeforeTag.getCompound(i);
                        if (wrap != null) before.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }
            t.put("offersBeforeTag", before);

            t.putLong("lockMaskBefore", td.lockMaskBefore);

            // 
            t.putInt("rerollCount", Math.max(0, td.rerollCount));

            return t;

        } catch (Throwable e) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.writeTask failed", e);
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

            // ✅ ONLY snapshot we persist: offers BEFORE auto-search started
            sd.offersBeforeTag = new ListTag();
            if (t.contains("offersBeforeTag", Tag.TAG_LIST)) {
                ListTag list = t.getList("offersBeforeTag", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    try {
                        CompoundTag wrap = list.getCompound(i);
                        if (wrap != null) sd.offersBeforeTag.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }

            sd.lockMaskBefore = 0L;
            try { sd.lockMaskBefore = t.getLong("lockMaskBefore"); } catch (Throwable ignored) { sd.lockMaskBefore = 0L; }

            sd.requestedTargets = new ArrayList<>();
            if (t.contains("requestedTargets", Tag.TAG_LIST)) {
                ListTag keys = t.getList("requestedTargets", Tag.TAG_STRING);
                int n = Math.min(256, keys.size());
                for (int i = 0; i < n; i++) {
                    String s = keys.getString(i);
                    if (s == null) continue;
                    s = s.trim();
                    if (!s.isEmpty()) sd.requestedTargets.add(s);
                }
            }

            sd.totalVillagerXp = 0;
            try { sd.totalVillagerXp = Math.max(0, t.getInt("totalVillagerXp")); } catch (Throwable ignored) { sd.totalVillagerXp = 0; }

            sd.rerollCount = 0;
            try { sd.rerollCount = Math.max(0, t.getInt("rerollCount")); } catch (Throwable ignored) { sd.rerollCount = 0; }

            if (sd.villagerUuid == null) return null;
            return sd;

        } catch (Throwable e) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.readSettlement failed", e);
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

            // ONLY persisted snapshot
            ListTag before = new ListTag();
            if (sd.offersBeforeTag != null) {
                for (int i = 0; i < sd.offersBeforeTag.size(); i++) {
                    try {
                        CompoundTag wrap = sd.offersBeforeTag.getCompound(i);
                        if (wrap != null) before.add(wrap.copy());
                    } catch (Throwable ignored) {}
                }
            }
            t.put("offersBeforeTag", before);

            t.putLong("lockMaskBefore", sd.lockMaskBefore);

            ListTag targets = new ListTag();
            if (sd.requestedTargets != null) {
                int n = Math.min(256, sd.requestedTargets.size());
                for (int i = 0; i < n; i++) {
                    String s = sd.requestedTargets.get(i);
                    if (s == null) continue;
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    targets.add(net.minecraft.nbt.StringTag.valueOf(s));
                }
            }
            t.put("requestedTargets", targets);

            t.putInt("totalVillagerXp", Math.max(0, sd.totalVillagerXp));
            t.putInt("rerollCount", Math.max(0, sd.rerollCount));

            return t;

        } catch (Throwable e) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchSavedData.writeSettlement failed", e);
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
