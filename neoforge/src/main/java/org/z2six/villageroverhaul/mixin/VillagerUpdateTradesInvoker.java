// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/VillagerUpdateTradesInvoker.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker to access Villager#updateTrades(), which is protected.
 */
@Mixin(Villager.class)
public interface VillagerUpdateTradesInvoker {
    @Invoker("updateTrades")
    void villageroverhaul$invokeUpdateTrades();
}
