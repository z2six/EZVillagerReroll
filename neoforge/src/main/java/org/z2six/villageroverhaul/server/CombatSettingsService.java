// neoforge\src\main\java\org\z2six\villageroverhaul\server\CombatSettingsService.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.combat.CombatSettings;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class CombatSettingsService {

    private static final String TAG_COMBAT_SETTINGS = "ezvr_combat_settings";

    private CombatSettingsService() {}

    public static CombatSettings getGlobal(ServerLevel level) {
        CombatSettingsSavedData data = getSavedData(level);
        return data == null ? new CombatSettings() : data.getSettings();
    }

    public static void setGlobal(ServerLevel level, CombatSettings settings) {
        CombatSettingsSavedData data = getSavedData(level);
        if (data == null) return;
        data.setSettings(settings);
    }

    public static CombatSettings getPerVillager(Villager vill) {
        try {
            if (vill == null) return null;
            CompoundTag pd = vill.getPersistentData();
            if (pd == null) return null;
            if (!pd.contains(TAG_COMBAT_SETTINGS, Tag.TAG_COMPOUND)) return null;
            return CombatSettings.fromTag(pd.getCompound(TAG_COMBAT_SETTINGS));
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setPerVillager(Villager vill, CombatSettings settings) {
        try {
            if (vill == null) return;
            CompoundTag pd = vill.getPersistentData();
            if (pd == null) return;
            CombatSettings s = (settings == null) ? new CombatSettings() : settings;
            pd.put(TAG_COMBAT_SETTINGS, s.toTag());
        } catch (Throwable ignored) {}
    }

    public static CombatSettings getEffectiveSettings(Villager vill) {
        CombatSettings per = getPerVillager(vill);
        return per == null ? new CombatSettings() : per;
    }

    public static boolean shouldTrigger(Villager vill, VillagerBrain.CombatMode mode, LivingEntity attacker, LivingEntity target) {
        return checkTrigger(vill, mode, attacker, target).ok;
    }

    public static TriggerCheck checkTrigger(Villager vill, VillagerBrain.CombatMode mode, LivingEntity attacker, LivingEntity target) {
        try {
            if (vill == null) return new TriggerCheck(false, "no_villager", "");
            if (attacker == null || target == null) return new TriggerCheck(false, "missing_attacker_or_target", "");

            CombatSettings settings = getPerVillager(vill);
            if (settings == null) return new TriggerCheck(false, "no_settings", "");
            CombatSettings.ModeSettings specific = settings.getForMode(mode);

            UUID owner = RecruitService.getRecruiterUuid(vill);

            if (owner != null && owner.equals(target.getUUID())) {
                if (!specific.ownerAttacked.enabled) return new TriggerCheck(false, "owner_attacked_disabled", "owner_attacked");
                return checkLists("owner_attacked", specific.ownerAttacked, attacker);
            }

            if (owner != null && owner.equals(attacker.getUUID())) {
                if (!specific.ownerAttacks.enabled) return new TriggerCheck(false, "owner_attacks_disabled", "owner_attacks");
                return checkLists("owner_attacks", specific.ownerAttacks, target);
            }

            if (specific.entityAttacks.enabled) {
                return checkLists("entity_attacks", specific.entityAttacks, attacker);
            }

            if (specific.entityAttacked.enabled) {
                return checkLists("entity_attacked", specific.entityAttacked, target);
            }

            return new TriggerCheck(false, "no_trigger_enabled", "");
        } catch (Throwable t) {
            return new TriggerCheck(false, "exception", "");
        }
    }

    public static final class TriggerCheck {
        public final boolean ok;
        public final String reason;
        public final String trigger;
        TriggerCheck(boolean ok, String reason, String trigger) {
            this.ok = ok;
            this.reason = reason == null ? "" : reason;
            this.trigger = trigger == null ? "" : trigger;
        }
    }

    private static String safeEntityId(LivingEntity e) {
        try {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static TriggerCheck checkLists(String label, CombatSettings.TriggerSettings settings, LivingEntity entity) {
        if (settings == null) return new TriggerCheck(false, label + "_missing_settings", label);
        String id = safeEntityId(entity);
        if (id.isEmpty()) return new TriggerCheck(false, label + "_missing_entity_id", label);

        Set<String> wl = normalize(settings.whitelist);
        Set<String> bl = normalize(settings.blacklist);

        if (bl.contains(id)) return new TriggerCheck(false, label + "_blacklist", label);
        if (!wl.isEmpty() && !wl.contains(id)) return new TriggerCheck(false, label + "_whitelist_missing", label);

        return new TriggerCheck(true, "ok", label);
    }

    private static Set<String> normalize(Iterable<String> items) {
        Set<String> out = new HashSet<>();
        if (items == null) return out;
        for (String s : items) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim().toLowerCase());
        }
        return out;
    }

    private static CombatSettingsSavedData getSavedData(ServerLevel level) {
        try {
            if (level == null) return null;
            return level.getDataStorage().computeIfAbsent(
                    new net.minecraft.world.level.saveddata.SavedData.Factory<>(CombatSettingsSavedData::new, CombatSettingsSavedData::load),
                    CombatSettingsSavedData.DATA_NAME
            );
        } catch (Throwable t) {
            return null;
        }
    }
}
