// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/api/VillagerOverhaulSwingAccess.java
package org.z2six.villageroverhaul.api;

/**
 * Sync hook for "weapon swing happened" as a simple sequence counter.
 *
 * Why:
 * - Vanilla swing state for villagers can be inconsistent / not usable for custom render layers.
 * - We need a deterministic, server-authoritative signal that a swing occurred.
 *
 * Semantics:
 * - Server increments ezvr$swingSeq once per swing event.
 * - Client detects changes and plays a local swing animation over a short window.
 */
public interface VillagerOverhaulSwingAccess {

    int ezvr$getSwingSeq();

    void ezvr$setSwingSeq(int seq);

    /**
     * Which hand triggered the last swing sequence increment.
     *
     * Values:
     * - 0 = main hand
     * - 1 = off hand
     */
    byte ezvr$getSwingHand();

    void ezvr$setSwingHand(byte hand);
}
