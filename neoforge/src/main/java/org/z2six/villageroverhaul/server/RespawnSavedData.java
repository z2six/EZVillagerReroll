// neoforge/src/main/java/org/z2six/villageroverhaul/server/RespawnSavedData.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.*;

/**
 * Persistent storage for recruited villager "respawn snapshots".
 *
 * Keyed by owner UUID, then by respawnId (stable per villager across deaths/respawns).
 */
public final class RespawnSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_respawn_snapshots";

    public static final class Snapshot {
        public UUID owner;
        public UUID respawnId;

        public String nameJson; // Component JSON
        public String professionId; // ResourceLocation string

        public int recruitCostAtDeath;
        public int deaths;

        public long capturedAtGameTime;

        public CompoundTag villagerNbt = new CompoundTag(); // entity "saveWithoutId" + tweaks (UUID removed)
    }

    private final Map<UUID, Map<UUID, Snapshot>> byOwner = new LinkedHashMap<>();

    public Map<UUID, Map<UUID, Snapshot>> byOwner() {
        return byOwner;
    }

    public static RespawnSavedData get(ServerLevel level) {
        try {
            if (level == null) return new RespawnSavedData();
            return level.getDataStorage().computeIfAbsent(
                    new Factory<>(RespawnSavedData::new, RespawnSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnSavedData.get failed", t);
            return new RespawnSavedData();
        }
    }

    public static RespawnSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        RespawnSavedData data = new RespawnSavedData();
        try {
            if (tag == null) return data;

            if (!tag.contains("owners", Tag.TAG_LIST)) return data;
            ListTag owners = tag.getList("owners", Tag.TAG_COMPOUND);

            for (int i = 0; i < owners.size(); i++) {
                CompoundTag o = owners.getCompound(i);
                UUID owner = readUuid(o, "owner");
                if (owner == null) continue;

                Map<UUID, Snapshot> map = new LinkedHashMap<>();

                if (o.contains("snaps", Tag.TAG_LIST)) {
                    ListTag snaps = o.getList("snaps", Tag.TAG_COMPOUND);
                    for (int j = 0; j < snaps.size(); j++) {
                        CompoundTag s = snaps.getCompound(j);
                        Snapshot snap = readSnapshot(s);
                        if (snap == null || snap.respawnId == null) continue;
                        snap.owner = owner;
                        map.put(snap.respawnId, snap);
                    }
                }

                if (!map.isEmpty()) data.byOwner.put(owner, map);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] RespawnSavedData loaded: owners={}", data.byOwner.size());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (tag == null) tag = new CompoundTag();
        try {
            ListTag owners = new ListTag();

            for (Map.Entry<UUID, Map<UUID, Snapshot>> en : byOwner.entrySet()) {
                UUID owner = en.getKey();
                Map<UUID, Snapshot> snaps = en.getValue();
                if (owner == null || snaps == null || snaps.isEmpty()) continue;

                CompoundTag o = new CompoundTag();
                writeUuid(o, "owner", owner);

                ListTag list = new ListTag();
                for (Snapshot s : snaps.values()) {
                    CompoundTag st = writeSnapshot(s);
                    if (st != null) list.add(st);
                }
                o.put("snaps", list);
                owners.add(o);
            }

            tag.put("owners", owners);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RespawnSavedData.save failed", t);
        }
        return tag;
    }

    private static Snapshot readSnapshot(CompoundTag tag) {
        try {
            if (tag == null) return null;
            Snapshot s = new Snapshot();
            s.respawnId = readUuid(tag, "rid");
            s.nameJson = tag.getString("name");
            s.professionId = tag.getString("prof");
            s.recruitCostAtDeath = tag.getInt("rcost");
            s.deaths = tag.getInt("deaths");
            s.capturedAtGameTime = tag.getLong("capturedAt");
            if (tag.contains("villNbt", Tag.TAG_COMPOUND)) {
                s.villagerNbt = tag.getCompound("villNbt");
            } else {
                s.villagerNbt = new CompoundTag();
            }
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static CompoundTag writeSnapshot(Snapshot s) {
        try {
            if (s == null || s.respawnId == null) return null;
            CompoundTag tag = new CompoundTag();
            writeUuid(tag, "rid", s.respawnId);
            tag.putString("name", s.nameJson == null ? "" : s.nameJson);
            tag.putString("prof", s.professionId == null ? "" : s.professionId);
            tag.putInt("rcost", Math.max(0, s.recruitCostAtDeath));
            tag.putInt("deaths", Math.max(0, s.deaths));
            tag.putLong("capturedAt", s.capturedAtGameTime);
            tag.put("villNbt", s.villagerNbt == null ? new CompoundTag() : s.villagerNbt);
            return tag;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static UUID readUuid(CompoundTag tag, String key) {
        try {
            if (tag == null || key == null) return null;
            if (!tag.hasUUID(key)) return null;
            return tag.getUUID(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeUuid(CompoundTag tag, String key, UUID uuid) {
        try {
            if (tag == null || key == null || uuid == null) return;
            tag.putUUID(key, uuid);
        } catch (Throwable ignored) {}
    }
}

