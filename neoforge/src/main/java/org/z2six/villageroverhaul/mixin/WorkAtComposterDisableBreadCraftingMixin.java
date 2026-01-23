package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WorkAtComposter.class)
public final class WorkAtComposterDisableBreadCraftingMixin {

    // Some modpacks/mixins appear to produce a null CallbackInfo for cancellable Injects on makeBread (crash).
    // Redirecting the call site is a safer way to disable wheat->bread auto crafting.
    @Redirect(
            method = "useWorkstation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/behavior/WorkAtComposter;makeBread(Lnet/minecraft/world/entity/npc/Villager;)V"
            )
    )
    private void villageroverhaul$skipAutoBreadCrafting(Villager villager) {
        // no-op
    }
}
