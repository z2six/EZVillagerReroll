// neoforge\src\main\java\org\z2six\villageroverhaul\api\VillagerOverhaulRenderAccess.java
package org.z2six.villageroverhaul.api;

import net.minecraft.world.item.ItemStack;

/**
 * Implemented via mixin on Villager to expose synced render decisions.
 *
 * IMPORTANT: This interface MUST NOT live in the mixin package,
 * otherwise Mixin will throw IllegalClassLoadError if referenced directly.
 */
public interface VillagerOverhaulRenderAccess {
    byte ezvr$getRenderFlags();
    void ezvr$setRenderFlags(byte flags);

    byte ezvr$getReleaseAlpha();
    void ezvr$setReleaseAlpha(byte alpha);

    byte ezvr$getFaction();
    void ezvr$setFaction(byte faction);

    byte ezvr$getGenderId();
    void ezvr$setGenderId(byte genderId);

    /**
     * Server-synced "combat loadout" items for client rendering while the villager is not actively holding them.
     * These are cosmetic only; server authority remains in {@code VillagerCombatLoadoutService}.
     */
    ItemStack ezvr$getCombatLoadoutMain();
    ItemStack ezvr$getCombatLoadoutOff();
    void ezvr$setCombatLoadoutMain(ItemStack stack);
    void ezvr$setCombatLoadoutOff(ItemStack stack);
}
