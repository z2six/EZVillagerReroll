package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

/**
 * Same as LivingEntityPreventVillagerHandEquipMixin, but covers Mob#setItemSlot paths.
 */
@Mixin(Mob.class)
public abstract class MobPreventVillagerHandEquipMixin {

    @Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
    private void ezvr$blockVillagerSlotEquip(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        try {
            if (slot == null) return;
            if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;
            if (stack == null || stack.isEmpty()) return; // clears handled separately

            Mob self = (Mob) (Object) this;
            if (!(self instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            InteractionHand hand = (slot == EquipmentSlot.MAINHAND) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            if (!VillagerBrain.shouldBlockHandSet(vill, hand, stack)) return;
            ci.cancel();
        } catch (Throwable ignored) {}
    }
}

