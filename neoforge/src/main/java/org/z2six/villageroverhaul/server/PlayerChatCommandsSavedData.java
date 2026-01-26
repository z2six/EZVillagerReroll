// neoforge/src/main/java/org/z2six/villageroverhaul/server/PlayerChatCommandsSavedData.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ServerConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-player chat-command configuration (UUID keyed).
 */
public final class PlayerChatCommandsSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_player_chat_commands";
    private static final int MAX_PHRASE_LEN = 256;

    public static final class Config {
        public int range = 26;
        public boolean chain = false;
        public boolean caseSensitive = false;

        public String help = "";
        public String neutral = "";
        public String idle = "";
        public String follow = "";
        public String patrol = "";
        public String manualFarming = "";
        public String flee = "";
        public String defend = "";
        public String aggressive = "";
    }

    private final Map<UUID, Config> byPlayer = new LinkedHashMap<>();

    public static PlayerChatCommandsSavedData get(MinecraftServer server) {
        try {
            if (server == null) return new PlayerChatCommandsSavedData();
            ServerLevel level = server.overworld();
            if (level == null) return new PlayerChatCommandsSavedData();
            return level.getDataStorage().computeIfAbsent(
                    new Factory<>(PlayerChatCommandsSavedData::new, PlayerChatCommandsSavedData::load),
                    DATA_NAME
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerChatCommandsSavedData.get failed", t);
            return new PlayerChatCommandsSavedData();
        }
    }

    public static PlayerChatCommandsSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        PlayerChatCommandsSavedData data = new PlayerChatCommandsSavedData();
        try {
            if (tag == null) return data;
            if (!tag.contains("players", Tag.TAG_LIST)) return data;
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag p = players.getCompound(i);
                UUID id = readUuid(p, "id");
                if (id == null) continue;
                Config cfg = decodeCfg(p);
                data.byPlayer.put(id, cfg);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerChatCommandsSavedData.load failed", t);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        if (tag == null) tag = new CompoundTag();
        try {
            ListTag players = new ListTag();
            for (Map.Entry<UUID, Config> en : byPlayer.entrySet()) {
                if (en.getKey() == null || en.getValue() == null) continue;
                CompoundTag p = new CompoundTag();
                writeUuid(p, "id", en.getKey());
                encodeCfgInto(p, en.getValue());
                players.add(p);
            }
            tag.put("players", players);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PlayerChatCommandsSavedData.save failed", t);
        }
        return tag;
    }

    public Config getOrCreate(UUID playerId) {
        try {
            if (playerId == null) return new Config();
            Config cfg = byPlayer.get(playerId);
            if (cfg == null) {
                cfg = new Config();
                cfg.range = Math.max(1, ServerConfig.customCommandsChatRadius);
                byPlayer.put(playerId, cfg);
                setDirty();
            }
            return cfg;
        } catch (Throwable ignored) {
            return new Config();
        }
    }

    public void update(UUID playerId, Config cfg) {
        try {
            if (playerId == null || cfg == null) return;
            byPlayer.put(playerId, cfg);
            setDirty();
        } catch (Throwable ignored) {}
    }

    public static CompoundTag toTag(Config cfg) {
        CompoundTag t = new CompoundTag();
        try { encodeCfgInto(t, cfg); } catch (Throwable ignored) {}
        return t;
    }

    public static Config fromTag(CompoundTag t) {
        try { return decodeCfg(t); } catch (Throwable ignored) { return new Config(); }
    }

    private static Config decodeCfg(CompoundTag t) {
        Config cfg = new Config();
        try {
            if (t == null) return cfg;
            cfg.range = clampInt(t.getInt("range"), 1, 128);
            cfg.chain = t.getBoolean("chain");
            cfg.caseSensitive = t.getBoolean("caseSensitive");
            cfg.help = safeStr(t.getString("help"));
            cfg.neutral = safeStr(t.getString("neutral"));
            cfg.idle = safeStr(t.getString("idle"));
            cfg.follow = safeStr(t.getString("follow"));
            cfg.patrol = safeStr(t.getString("patrol"));
            cfg.manualFarming = safeStr(t.getString("manualFarming"));
            cfg.flee = safeStr(t.getString("flee"));
            cfg.defend = safeStr(t.getString("defend"));
            cfg.aggressive = safeStr(t.getString("aggressive"));
        } catch (Throwable ignored) {}
        return cfg;
    }

    private static void encodeCfgInto(CompoundTag t, Config cfg) {
        if (t == null || cfg == null) return;
        t.putInt("range", clampInt(cfg.range, 1, 128));
        t.putBoolean("chain", cfg.chain);
        t.putBoolean("caseSensitive", cfg.caseSensitive);
        t.putString("help", safeStr(cfg.help));
        t.putString("neutral", safeStr(cfg.neutral));
        t.putString("idle", safeStr(cfg.idle));
        t.putString("follow", safeStr(cfg.follow));
        t.putString("patrol", safeStr(cfg.patrol));
        t.putString("manualFarming", safeStr(cfg.manualFarming));
        t.putString("flee", safeStr(cfg.flee));
        t.putString("defend", safeStr(cfg.defend));
        t.putString("aggressive", safeStr(cfg.aggressive));
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String safeStr(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() > MAX_PHRASE_LEN) t = t.substring(0, MAX_PHRASE_LEN);
        return t;
    }

    private static void writeUuid(CompoundTag t, String k, UUID v) {
        try { if (t != null && k != null && v != null) t.putUUID(k, v); } catch (Throwable ignored) {}
    }

    private static UUID readUuid(CompoundTag t, String k) {
        try {
            if (t == null || k == null) return null;
            if (!t.hasUUID(k)) return null;
            return t.getUUID(k);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
