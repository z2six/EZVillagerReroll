// neoforge\src\main\java\org\z2six\villageroverhaul\server\CombatSettingsSavedData.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.z2six.villageroverhaul.combat.CombatSettings;

public final class CombatSettingsSavedData extends SavedData {

    public static final String DATA_NAME = "villageroverhaul_combat_settings";

    private CombatSettings settings = new CombatSettings();

    public CombatSettings getSettings() {
        return settings;
    }

    public void setSettings(CombatSettings settings) {
        this.settings = (settings == null) ? new CombatSettings() : settings;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        tag.put("settings", settings.toTag());
        return tag;
    }

    public static CombatSettingsSavedData load(CompoundTag tag, HolderLookup.Provider lookup) {
        CombatSettingsSavedData data = new CombatSettingsSavedData();
        if (tag != null && tag.contains("settings", CompoundTag.TAG_COMPOUND)) {
            data.settings = CombatSettings.fromTag(tag.getCompound("settings"));
        }
        return data;
    }
}
