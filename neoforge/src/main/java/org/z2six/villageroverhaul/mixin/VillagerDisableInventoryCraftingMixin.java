// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/VillagerDisableInventoryCraftingMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevent villagers from "crafting" items directly from their inventory.
 *
 * In vanilla 1.21.x this is primarily bread crafting (wheat -> bread) done in {@link WorkAtComposter#makeBread}.
 *
 * Safety goals:
 * - Fail-soft if method name/signature changes (require=0).
 * - Avoid @Overwrite / @Redirect to reduce mod conflicts.
 */
@Mixin(WorkAtComposter.class)
public abstract class VillagerDisableInventoryCraftingMixin {

    @Inject(
            method = "makeBread(Lnet/minecraft/world/entity/npc/Villager;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$disableMakeBread(Villager villager, CallbackInfo ci) {
        ci.cancel();
    }
}

