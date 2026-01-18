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

    private static final String K_GENERAL = "general";
    private static final String K_FLEE = "flee";
    private static final String K_DEFEND = "defend";
    private static final String K_AGGRESSIVE = "aggressive";

    public final ModeSettings general = new ModeSettings();
    public final ModeSettings flee = new ModeSettings();
    public final ModeSettings defend = new ModeSettings();
    public final ModeSettings aggressive = new ModeSettings();

    public ModeSettings getForMode(VillagerBrain.CombatMode mode) {
        if (mode == null) return general;
        return switch (mode) {
            case FLEE -> flee;
            case DEFEND -> defend;
            case AGGRESSIVE -> aggressive;
            default -> general;
        };
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put(K_GENERAL, general.toTag());
        tag.put(K_FLEE, flee.toTag());
        tag.put(K_DEFEND, defend.toTag());
        tag.put(K_AGGRESSIVE, aggressive.toTag());
        return tag;
    }

    public static CombatSettings fromTag(CompoundTag tag) {
        CombatSettings out = new CombatSettings();
        if (tag == null) return out;

        if (tag.contains(K_GENERAL, Tag.TAG_COMPOUND)) out.general.readFrom(tag.getCompound(K_GENERAL));
        if (tag.contains(K_FLEE, Tag.TAG_COMPOUND)) out.flee.readFrom(tag.getCompound(K_FLEE));
        if (tag.contains(K_DEFEND, Tag.TAG_COMPOUND)) out.defend.readFrom(tag.getCompound(K_DEFEND));
        if (tag.contains(K_AGGRESSIVE, Tag.TAG_COMPOUND)) out.aggressive.readFrom(tag.getCompound(K_AGGRESSIVE));

        return out;
    }

    public static final class ModeSettings {
        private static final String K_OWNER_ATTACKED = "owner_attacked";
        private static final String K_OWNER_ATTACKS = "owner_attacks";
        private static final String K_WL_ATTACKED = "wl_attacked";
        private static final String K_WL_ATTACKS = "wl_attacks";
        private static final String K_BL_ATTACKED = "bl_attacked";
        private static final String K_BL_ATTACKS = "bl_attacks";

        public boolean triggerWhenOwnerAttacked = false;
        public boolean triggerWhenOwnerAttacks = false;

        public final List<String> whitelistAttacked = new ArrayList<>();
        public final List<String> whitelistAttacks = new ArrayList<>();
        public final List<String> blacklistAttacked = new ArrayList<>();
        public final List<String> blacklistAttacks = new ArrayList<>();

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean(K_OWNER_ATTACKED, triggerWhenOwnerAttacked);
            tag.putBoolean(K_OWNER_ATTACKS, triggerWhenOwnerAttacks);
            tag.put(K_WL_ATTACKED, writeStringList(whitelistAttacked));
            tag.put(K_WL_ATTACKS, writeStringList(whitelistAttacks));
            tag.put(K_BL_ATTACKED, writeStringList(blacklistAttacked));
            tag.put(K_BL_ATTACKS, writeStringList(blacklistAttacks));
            return tag;
        }

        public void readFrom(CompoundTag tag) {
            if (tag == null) return;

            triggerWhenOwnerAttacked = tag.getBoolean(K_OWNER_ATTACKED);
            triggerWhenOwnerAttacks = tag.getBoolean(K_OWNER_ATTACKS);

            whitelistAttacked.clear();
            whitelistAttacks.clear();
            blacklistAttacked.clear();
            blacklistAttacks.clear();

            readStringList(tag, K_WL_ATTACKED, whitelistAttacked);
            readStringList(tag, K_WL_ATTACKS, whitelistAttacks);
            readStringList(tag, K_BL_ATTACKED, blacklistAttacked);
            readStringList(tag, K_BL_ATTACKS, blacklistAttacks);
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
}
