// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerCombat/LivingEntityHurtBlockedTickMixin.java
package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtBlockedTickMixin {

    // Must match the key used by VillagerManualShieldBlocker
    private static final String PD_BLOCKED_TICK = "ezvr_blocked_tick";

    @Inject(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void ezvr$cancelHurtWhenBlocked(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        try {
            LivingEntity self = (LivingEntity) (Object) this;
            if (!(self instanceof Villager vill)) return;

            if (vill.level() == null || vill.level().isClientSide()) return;

            long now = vill.level().getGameTime();
            long blockedTick = vill.getPersistentData().getLong(PD_BLOCKED_TICK);

            if (blockedTick != now) return;

            // Fully cancel the hurt processing. This prevents:
            // - hurt sound
            // - red flash
            // - stopUsingItem side effects
            // - any “micro knockback” behavior tied to hurt processing
            cir.setReturnValue(false);
        } catch (Throwable ignored) {}
    }
}
