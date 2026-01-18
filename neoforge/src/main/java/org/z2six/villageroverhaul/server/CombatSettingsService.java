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
        if (per != null) return per;
        if (vill == null || !(vill.level() instanceof ServerLevel level)) return new CombatSettings();
        return getGlobal(level);
    }

    public static boolean shouldTrigger(Villager vill, VillagerBrain.CombatMode mode, LivingEntity attacker, LivingEntity target) {
        return checkTrigger(vill, mode, attacker, target).ok;
    }

    public static TriggerCheck checkTrigger(Villager vill, VillagerBrain.CombatMode mode, LivingEntity attacker, LivingEntity target) {
        try {
            if (vill == null) return new TriggerCheck(false, "no_villager");
            if (attacker == null || target == null) return new TriggerCheck(false, "missing_attacker_or_target");

            CombatSettings settings = getEffectiveSettings(vill);
            CombatSettings.ModeSettings general = settings.general;
            CombatSettings.ModeSettings specific = settings.getForMode(mode);

            UUID owner = RecruitService.getRecruiterUuid(vill);
            if (owner != null) {
                if (owner.equals(target.getUUID()) && !(general.triggerWhenOwnerAttacked || specific.triggerWhenOwnerAttacked)) {
                    return new TriggerCheck(false, "owner_attacked_disabled");
                }
                if (owner.equals(attacker.getUUID()) && !(general.triggerWhenOwnerAttacks || specific.triggerWhenOwnerAttacks)) {
                    return new TriggerCheck(false, "owner_attacks_disabled");
                }
            }

            String attackedId = safeEntityId(target);
            String attacksId = safeEntityId(attacker);
            if (attackedId.isEmpty() || attacksId.isEmpty()) return new TriggerCheck(false, "missing_entity_id");

            Set<String> wlAttacked = union(general.whitelistAttacked, specific.whitelistAttacked);
            Set<String> wlAttacks = union(general.whitelistAttacks, specific.whitelistAttacks);
            Set<String> blAttacked = union(general.blacklistAttacked, specific.blacklistAttacked);
            Set<String> blAttacks = union(general.blacklistAttacks, specific.blacklistAttacks);

            if (blAttacked.contains(attackedId)) return new TriggerCheck(false, "blacklist_attacked");
            if (blAttacks.contains(attacksId)) return new TriggerCheck(false, "blacklist_attacks");

            if (!wlAttacked.isEmpty() && !wlAttacked.contains(attackedId)) return new TriggerCheck(false, "whitelist_attacked_missing");
            if (!wlAttacks.isEmpty() && !wlAttacks.contains(attacksId)) return new TriggerCheck(false, "whitelist_attacks_missing");

            return new TriggerCheck(true, "ok");
        } catch (Throwable t) {
            return new TriggerCheck(false, "exception");
        }
    }

    public static final class TriggerCheck {
        public final boolean ok;
        public final String reason;
        TriggerCheck(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason == null ? "" : reason;
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

    private static Set<String> union(Iterable<String> a, Iterable<String> b) {
        Set<String> out = new HashSet<>();
        if (a != null) {
            for (String s : a) if (s != null && !s.isBlank()) out.add(s.trim());
        }
        if (b != null) {
            for (String s : b) if (s != null && !s.isBlank()) out.add(s.trim());
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
