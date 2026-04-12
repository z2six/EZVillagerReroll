package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.farming.FarmingSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerFarmingProfilesSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_player_farming_profiles";
    private static final int MAX_NAME_LEN = 48;

    public record Profile(String name, FarmingSettings settings) {}

    private final Map<UUID, List<Profile>> byPlayer = new LinkedHashMap<>();

    public static PlayerFarmingProfilesSavedData get(MinecraftServer server) {
        try {
            if (server == null) return new PlayerFarmingProfilesSavedData();
            ServerLevel level = server.overworld();
            if (level == null) return new PlayerFarmingProfilesSavedData();
            return level.getDataStorage().computeIfAbsent(
                    new Factory<>(PlayerFarmingProfilesSavedData::new, PlayerFarmingProfilesSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerFarmingProfilesSavedData.get failed", t);
            return new PlayerFarmingProfilesSavedData();
        }
    }

    public static PlayerFarmingProfilesSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        PlayerFarmingProfilesSavedData data = new PlayerFarmingProfilesSavedData();
        try {
            if (tag == null || !tag.contains("players", Tag.TAG_LIST)) return data;
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag p = players.getCompound(i);
                UUID id = readUuid(p, "id");
                if (id == null) continue;
                data.byPlayer.put(id, fromTag(p.getCompound("profiles")));
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerFarmingProfilesSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (tag == null) tag = new CompoundTag();
        try {
            ListTag players = new ListTag();
            for (Map.Entry<UUID, List<Profile>> en : byPlayer.entrySet()) {
                if (en.getKey() == null) continue;
                CompoundTag p = new CompoundTag();
                writeUuid(p, "id", en.getKey());
                p.put("profiles", toTag(en.getValue()));
                players.add(p);
            }
            tag.put("players", players);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerFarmingProfilesSavedData.save failed", t);
        }
        return tag;
    }

    public List<Profile> getProfiles(UUID playerId) {
        try {
            if (playerId == null) return List.of();
            List<Profile> list = byPlayer.get(playerId);
            if (list == null || list.isEmpty()) return List.of();
            return copyProfiles(list);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public void upsert(UUID playerId, String rawName, FarmingSettings rawSettings) {
        try {
            if (playerId == null) return;
            String name = sanitizeName(rawName);
            if (name.isEmpty()) return;

            FarmingSettings settings = normalizeProfileSettings(rawSettings);
            List<Profile> list = new ArrayList<>(byPlayer.getOrDefault(playerId, List.of()));
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                Profile p = list.get(i);
                if (p != null && name.equalsIgnoreCase(p.name())) {
                    list.set(i, new Profile(name, settings));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) list.add(new Profile(name, settings));
            byPlayer.put(playerId, list);
            setDirty();
        } catch (Throwable ignored) {}
    }

    public void delete(UUID playerId, String rawName) {
        try {
            if (playerId == null) return;
            String name = sanitizeName(rawName);
            if (name.isEmpty()) return;
            List<Profile> cur = byPlayer.get(playerId);
            if (cur == null || cur.isEmpty()) return;

            List<Profile> next = new ArrayList<>();
            for (Profile p : cur) {
                if (p == null || name.equalsIgnoreCase(p.name())) continue;
                next.add(p);
            }
            byPlayer.put(playerId, next);
            setDirty();
        } catch (Throwable ignored) {}
    }

    public static CompoundTag toTag(List<Profile> profiles) {
        CompoundTag tag = new CompoundTag();
        try {
            ListTag list = new ListTag();
            if (profiles != null) {
                for (Profile p : profiles) {
                    if (p == null) continue;
                    String name = sanitizeName(p.name());
                    if (name.isEmpty()) continue;
                    CompoundTag pt = new CompoundTag();
                    pt.putString("name", name);
                    pt.put("settings", normalizeProfileSettings(p.settings()).toTag());
                    list.add(pt);
                }
            }
            tag.put("profiles", list);
        } catch (Throwable ignored) {}
        return tag;
    }

    public static List<Profile> fromTag(CompoundTag tag) {
        List<Profile> out = new ArrayList<>();
        try {
            if (tag == null || !tag.contains("profiles", Tag.TAG_LIST)) return out;
            ListTag list = tag.getList("profiles", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag pt = list.getCompound(i);
                String name = sanitizeName(pt.getString("name"));
                if (name.isEmpty()) continue;
                FarmingSettings settings = normalizeProfileSettings(FarmingSettings.fromTag(pt.getCompound("settings")));
                out.add(new Profile(name, settings));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static FarmingSettings normalizeProfileSettings(FarmingSettings raw) {
        FarmingSettings settings = raw == null ? new FarmingSettings() : FarmingSettings.fromTag(raw.toTag());
        try {
            settings.updatedAt = 0L;
            settings.manualWorkstationRegistered = false;
            settings.hasDepositChest = false;
            settings.hasWithdrawChest = false;
        } catch (Throwable ignored) {}
        return settings;
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        String name = raw.trim();
        if (name.length() > MAX_NAME_LEN) name = name.substring(0, MAX_NAME_LEN);
        return name;
    }

    private static List<Profile> copyProfiles(List<Profile> profiles) {
        List<Profile> out = new ArrayList<>();
        if (profiles == null) return out;
        for (Profile p : profiles) {
            if (p == null) continue;
            String name = sanitizeName(p.name());
            if (name.isEmpty()) continue;
            out.add(new Profile(name, normalizeProfileSettings(p.settings())));
        }
        return out;
    }

    private static void writeUuid(CompoundTag t, String k, UUID v) {
        try { if (t != null && k != null && v != null) t.putUUID(k, v); } catch (Throwable ignored) {}
    }

    private static UUID readUuid(CompoundTag t, String k) {
        try {
            if (t == null || k == null || !t.hasUUID(k)) return null;
            return t.getUUID(k);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
