package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

/**
 * Prevent vanilla AI from equipping "visual" items into villager hands (bonemeal/emerald/etc).
 * We only allow equips when VillagerBrain has explicitly allowed a specific set.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPreventVillagerHandEquipMixin {

    @Inject(method = "setItemInHand", at = @At("HEAD"), cancellable = true)
    private void ezvr$blockVillagerHandEquip(InteractionHand hand, ItemStack stack, CallbackInfo ci) {
        try {
            if (hand == null) return;
            if (stack == null || stack.isEmpty()) return; // clears handled separately

            LivingEntity self = (LivingEntity) (Object) this;
            if (!(self instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            if (!VillagerBrain.shouldBlockHandSet(vill, hand, stack)) return;
            ci.cancel();
        } catch (Throwable ignored) {}
    }
}

