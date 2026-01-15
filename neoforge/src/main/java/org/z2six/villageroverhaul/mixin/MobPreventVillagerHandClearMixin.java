// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/MobPreventVillagerHandClearMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

@Mixin(Mob.class)
public abstract class MobPreventVillagerHandClearMixin {

    @Inject(method = "setItemSlot", at = @At("HEAD"), cancellable = true)
    private void ezvr$blockVillagerSlotClear(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        try {
            if (slot == null) return;
            if (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND) return;

            // Only care about attempts to CLEAR (EMPTY)
            if (stack == null || !stack.isEmpty()) return;

            Mob self = (Mob) (Object) this;
            if (!(self instanceof Villager vill)) return;

            if (vill.level() == null || vill.level().isClientSide()) return;

            InteractionHand hand = (slot == EquipmentSlot.MAINHAND) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

            if (!VillagerBrain.shouldBlockHandClear(vill, hand)) return;

            // Block the clear call entirely.
            ci.cancel();

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Blocked AI hand clear via Mob#setItemSlot({}, EMPTY) (villager={})",
                    slot, vill.getUUID());

        } catch (Throwable ignored) {}
    }
}
