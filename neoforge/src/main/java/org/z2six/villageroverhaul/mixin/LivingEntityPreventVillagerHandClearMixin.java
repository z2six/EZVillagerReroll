// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/LivingEntityPreventVillagerHandClearMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

@Mixin(LivingEntity.class)
public abstract class LivingEntityPreventVillagerHandClearMixin {

    @Inject(method = "setItemInHand", at = @At("HEAD"), cancellable = true)
    private void ezvr$blockVillagerHandClear(InteractionHand hand, ItemStack stack, CallbackInfo ci) {
        try {
            if (hand == null) return;
            // Only care about attempts to CLEAR (EMPTY)
            if (stack == null || !stack.isEmpty()) return;

            LivingEntity self = (LivingEntity) (Object) this;
            if (!(self instanceof Villager vill)) return;

            if (vill.level() == null || vill.level().isClientSide()) return;

            if (!VillagerBrain.shouldBlockHandClear(vill, hand)) return;

            // Block the clear call entirely.
            ci.cancel();

            // Debug only (avoid spam at INFO)
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Blocked AI hand clear via setItemInHand({}, EMPTY) (villager={})",
                    hand, vill.getUUID());

        } catch (Throwable ignored) {}
    }
}
