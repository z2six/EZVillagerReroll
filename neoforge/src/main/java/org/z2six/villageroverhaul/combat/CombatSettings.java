// neoforge\src\main\java\org\z2six\villageroverhaul\combat\CombatSettings.java
package org.z2six.villageroverhaul.combat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.util.ArrayList;
import java.util.List;

public final class CombatSettings {

    private static final String K_FLEE = "flee";
    private static final String K_DEFEND = "defend";
    private static final String K_AGGRESSIVE = "aggressive";
    private static final String K_AI = "ai";

    public final ModeSettings flee = new ModeSettings();
    public final ModeSettings defend = new ModeSettings();
    public final ModeSettings aggressive = new ModeSettings();
    public final AiSettings ai = new AiSettings();

    public ModeSettings getForMode(VillagerBrain.CombatMode mode) {
        if (mode == null) return flee;
        return switch (mode) {
            case FLEE -> flee;
            case DEFEND -> defend;
            case AGGRESSIVE -> aggressive;
            default -> flee;
        };
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put(K_FLEE, flee.toTag());
        tag.put(K_DEFEND, defend.toTag());
        tag.put(K_AGGRESSIVE, aggressive.toTag());
        tag.put(K_AI, ai.toTag());
        return tag;
    }

    public static CombatSettings fromTag(CompoundTag tag) {
        CombatSettings out = new CombatSettings();
        if (tag == null) return out;

        ModeSettings legacyGeneral = null;
        if (tag.contains("general", Tag.TAG_COMPOUND)) {
            legacyGeneral = new ModeSettings();
            legacyGeneral.readFrom(tag.getCompound("general"));
        }

        if (tag.contains(K_FLEE, Tag.TAG_COMPOUND)) out.flee.readFrom(tag.getCompound(K_FLEE));
        else if (legacyGeneral != null) out.flee.copyFromLegacy(legacyGeneral);

        if (tag.contains(K_DEFEND, Tag.TAG_COMPOUND)) out.defend.readFrom(tag.getCompound(K_DEFEND));
        else if (legacyGeneral != null) out.defend.copyFromLegacy(legacyGeneral);

        if (tag.contains(K_AGGRESSIVE, Tag.TAG_COMPOUND)) out.aggressive.readFrom(tag.getCompound(K_AGGRESSIVE));
        else if (legacyGeneral != null) out.aggressive.copyFromLegacy(legacyGeneral);

        if (tag.contains(K_AI, Tag.TAG_COMPOUND)) out.ai.readFrom(tag.getCompound(K_AI));

        return out;
    }

    /**
     * Non-power / behavior tuning for combat AI.
     *
     * Values are clamped on read to prevent overpowered configs.
     */
    public static final class AiSettings {
        private static final String K_ENABLE_BLOCKING = "enable_blocking";
        private static final String K_ENABLE_EATING = "enable_eating";
        private static final String K_ENABLE_CIRCLING = "enable_circling";
        private static final String K_EAT_FORCE_HITS = "eat_force_hits";
        private static final String K_EAT_MAX_RESETS = "eat_max_resets";
        private static final String K_TARGET_TIMEOUT_SECONDS = "target_timeout_seconds";

        // These are intentionally conservative clamps.
        private static final int EAT_FORCE_HITS_MIN = 0;   // 0 => immediately force-eat
        private static final int EAT_FORCE_HITS_MAX = 6;
        private static final int EAT_MAX_RESETS_MIN = 0;
        private static final int EAT_MAX_RESETS_MAX = 5;
        private static final int TARGET_TIMEOUT_SECONDS_MIN = 0; // 0 => disabled
        private static final int TARGET_TIMEOUT_SECONDS_MAX = 600;
        private static final int TARGET_TIMEOUT_SECONDS_DEFAULT = 16;

        public boolean enableBlocking = true;
        public boolean enableEating = true;
        public boolean enableCircling = true;

        public int eatForceHits = 2;
        public int eatMaxResets = 1;
        public int targetTimeoutSeconds = TARGET_TIMEOUT_SECONDS_DEFAULT;

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(K_ENABLE_BLOCKING, enableBlocking);
            tag.putBoolean(K_ENABLE_EATING, enableEating);
            tag.putBoolean(K_ENABLE_CIRCLING, enableCircling);
            tag.putInt(K_EAT_FORCE_HITS, clampInt(eatForceHits, EAT_FORCE_HITS_MIN, EAT_FORCE_HITS_MAX));
            tag.putInt(K_EAT_MAX_RESETS, clampInt(eatMaxResets, EAT_MAX_RESETS_MIN, EAT_MAX_RESETS_MAX));
            tag.putInt(K_TARGET_TIMEOUT_SECONDS, clampInt(targetTimeoutSeconds, TARGET_TIMEOUT_SECONDS_MIN, TARGET_TIMEOUT_SECONDS_MAX));
            return tag;
        }

        public void readFrom(CompoundTag tag) {
            if (tag == null) return;
            enableBlocking = tag.getBoolean(K_ENABLE_BLOCKING);
            enableEating = tag.getBoolean(K_ENABLE_EATING);
            enableCircling = tag.getBoolean(K_ENABLE_CIRCLING);
            eatForceHits = clampInt(tag.getInt(K_EAT_FORCE_HITS), EAT_FORCE_HITS_MIN, EAT_FORCE_HITS_MAX);
            eatMaxResets = clampInt(tag.getInt(K_EAT_MAX_RESETS), EAT_MAX_RESETS_MIN, EAT_MAX_RESETS_MAX);
            targetTimeoutSeconds = tag.contains(K_TARGET_TIMEOUT_SECONDS, Tag.TAG_ANY_NUMERIC)
                    ? clampInt(tag.getInt(K_TARGET_TIMEOUT_SECONDS), TARGET_TIMEOUT_SECONDS_MIN, TARGET_TIMEOUT_SECONDS_MAX)
                    : TARGET_TIMEOUT_SECONDS_DEFAULT;
        }
    }

    public static final class ModeSettings {
        private static final String K_TR_OWNER_ATTACKED = "tr_owner_attacked";
        private static final String K_TR_OWNER_ATTACKS = "tr_owner_attacks";
        private static final String K_TR_ENTITY_ATTACKS = "tr_entity_attacks";
        private static final String K_TR_ENTITY_ATTACKED = "tr_entity_attacked";
        private static final String K_TR_ENABLED = "enabled";
        private static final String K_TR_WL = "wl";
        private static final String K_TR_BL = "bl";
        private static final String K_AGGRO_WL = "aggressive_wl";
        private static final String K_AGGRO_BL = "aggressive_bl";
        private static final String K_AGGRO_HOSTILE = "aggressive_hostile_mobs";
        private static final String K_AGGRO_PASSIVE = "aggressive_passive_mobs";
        private static final String K_AGGRO_PLAYERS = "aggressive_players";
        private static final String K_AGGRO_PLAYER_WL = "aggressive_player_whitelist";

        // Legacy keys
        private static final String K_OWNER_ATTACKED = "owner_attacked";
        private static final String K_OWNER_ATTACKS = "owner_attacks";
        private static final String K_WL_ATTACKED = "wl_attacked";
        private static final String K_WL_ATTACKS = "wl_attacks";
        private static final String K_BL_ATTACKED = "bl_attacked";
        private static final String K_BL_ATTACKS = "bl_attacks";

        public final TriggerSettings ownerAttacked = new TriggerSettings();
        public final TriggerSettings ownerAttacks = new TriggerSettings();
        public final TriggerSettings entityAttacks = new TriggerSettings();
        public final TriggerSettings entityAttacked = new TriggerSettings();

        public final List<String> aggressiveWhitelist = new ArrayList<>();
        public final List<String> aggressiveBlacklist = new ArrayList<>();
        public boolean aggressiveHostileMobs = false;
        public boolean aggressivePassiveMobs = false;
        public boolean aggressivePlayers = false;
        public final List<String> aggressivePlayerWhitelist = new ArrayList<>();

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.put(K_TR_OWNER_ATTACKED, ownerAttacked.toTag());
            tag.put(K_TR_OWNER_ATTACKS, ownerAttacks.toTag());
            tag.put(K_TR_ENTITY_ATTACKS, entityAttacks.toTag());
            tag.put(K_TR_ENTITY_ATTACKED, entityAttacked.toTag());
            tag.put(K_AGGRO_WL, writeStringList(aggressiveWhitelist));
            tag.put(K_AGGRO_BL, writeStringList(aggressiveBlacklist));
            tag.putBoolean(K_AGGRO_HOSTILE, aggressiveHostileMobs);
            tag.putBoolean(K_AGGRO_PASSIVE, aggressivePassiveMobs);
            tag.putBoolean(K_AGGRO_PLAYERS, aggressivePlayers);
            tag.put(K_AGGRO_PLAYER_WL, writeStringList(aggressivePlayerWhitelist));
            return tag;
        }

        public void readFrom(CompoundTag tag) {
            if (tag == null) return;

            if (tag.contains(K_TR_OWNER_ATTACKED, Tag.TAG_COMPOUND)) ownerAttacked.readFrom(tag.getCompound(K_TR_OWNER_ATTACKED));
            if (tag.contains(K_TR_OWNER_ATTACKS, Tag.TAG_COMPOUND)) ownerAttacks.readFrom(tag.getCompound(K_TR_OWNER_ATTACKS));
            if (tag.contains(K_TR_ENTITY_ATTACKS, Tag.TAG_COMPOUND)) entityAttacks.readFrom(tag.getCompound(K_TR_ENTITY_ATTACKS));
            if (tag.contains(K_TR_ENTITY_ATTACKED, Tag.TAG_COMPOUND)) entityAttacked.readFrom(tag.getCompound(K_TR_ENTITY_ATTACKED));

            aggressiveWhitelist.clear();
            aggressiveBlacklist.clear();
            aggressivePlayerWhitelist.clear();

            readStringList(tag, K_AGGRO_WL, aggressiveWhitelist);
            readStringList(tag, K_AGGRO_BL, aggressiveBlacklist);
            aggressiveHostileMobs = tag.getBoolean(K_AGGRO_HOSTILE);
            aggressivePassiveMobs = tag.getBoolean(K_AGGRO_PASSIVE);
            aggressivePlayers = tag.getBoolean(K_AGGRO_PLAYERS);
            readStringList(tag, K_AGGRO_PLAYER_WL, aggressivePlayerWhitelist);

            // Legacy migration
            if (tag.contains(K_OWNER_ATTACKED, Tag.TAG_ANY_NUMERIC) || tag.contains(K_WL_ATTACKED, Tag.TAG_LIST)) {
                ownerAttacked.enabled = tag.getBoolean(K_OWNER_ATTACKED);
                ownerAttacks.enabled = tag.getBoolean(K_OWNER_ATTACKS);
                ownerAttacked.whitelist.clear();
                ownerAttacked.blacklist.clear();
                ownerAttacks.whitelist.clear();
                ownerAttacks.blacklist.clear();
                readStringList(tag, K_WL_ATTACKED, ownerAttacked.whitelist);
                readStringList(tag, K_BL_ATTACKED, ownerAttacked.blacklist);
                readStringList(tag, K_WL_ATTACKS, ownerAttacks.whitelist);
                readStringList(tag, K_BL_ATTACKS, ownerAttacks.blacklist);
            }
        }

        private void copyFromLegacy(ModeSettings legacy) {
            if (legacy == null) return;
            ownerAttacked.enabled = legacy.ownerAttacked.enabled;
            ownerAttacks.enabled = legacy.ownerAttacks.enabled;
            ownerAttacked.whitelist.addAll(legacy.ownerAttacked.whitelist);
            ownerAttacked.blacklist.addAll(legacy.ownerAttacked.blacklist);
            ownerAttacks.whitelist.addAll(legacy.ownerAttacks.whitelist);
            ownerAttacks.blacklist.addAll(legacy.ownerAttacks.blacklist);
        }
    }

    public static final class TriggerSettings {
        public boolean enabled = false;
        public final List<String> whitelist = new ArrayList<>();
        public final List<String> blacklist = new ArrayList<>();

        private CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(ModeSettings.K_TR_ENABLED, enabled);
            tag.put(ModeSettings.K_TR_WL, writeStringList(whitelist));
            tag.put(ModeSettings.K_TR_BL, writeStringList(blacklist));
            return tag;
        }

        private void readFrom(CompoundTag tag) {
            if (tag == null) return;
            enabled = tag.getBoolean(ModeSettings.K_TR_ENABLED);
            whitelist.clear();
            blacklist.clear();
            readStringList(tag, ModeSettings.K_TR_WL, whitelist);
            readStringList(tag, ModeSettings.K_TR_BL, blacklist);
        }
    }

    private static ListTag writeStringList(List<String> items) {
        ListTag out = new ListTag();
        if (items == null) return out;
        for (String s : items) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            out.add(StringTag.valueOf(v));
        }
        return out;
    }

    private static void readStringList(CompoundTag tag, String key, List<String> out) {
        if (tag == null || key == null || out == null) return;
        if (!tag.contains(key, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String v = list.getString(i);
            if (v == null) continue;
            v = v.trim();
            if (!v.isEmpty()) out.add(v);
        }
    }

    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
