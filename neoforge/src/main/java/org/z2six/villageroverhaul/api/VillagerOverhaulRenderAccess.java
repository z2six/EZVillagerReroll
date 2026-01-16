// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/api/VillagerOverhaulRenderAccess.java
package org.z2six.villageroverhaul.api;

/**
 * Implemented via mixin on Villager to expose synced render decisions.
 *
 * IMPORTANT: This interface MUST NOT live in the mixin package,
 * otherwise Mixin will throw IllegalClassLoadError if referenced directly.
 */
public interface VillagerOverhaulRenderAccess {
    byte ezvr$getRenderFlags();
    void ezvr$setRenderFlags(byte flags);
}
