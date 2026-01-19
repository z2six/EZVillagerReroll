package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobBackpedalInputMixin {
    private static final String PD_BACKPEDAL_UNTIL = "ezvr_backpedal_until";
    private static final String PD_BACKPEDAL_SPEED = "ezvr_backpedal_speed";

    @Inject(
            method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;aiStep()V", shift = At.Shift.BEFORE),
            require = 0
    )
    private void ezvr$applyBackpedalMovementInputs(CallbackInfo ci) {
        try {
            Mob self = (Mob) (Object) this;
            if (!(self instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            CompoundTag pd = vill.getPersistentData();
            long until = pd.getLong(PD_BACKPEDAL_UNTIL);
            if (until <= 0L) return;

            long now = vill.level().getGameTime();
            if (now >= until) return;

            float speed = pd.getFloat(PD_BACKPEDAL_SPEED);
            if (speed <= 0.0f) speed = 0.5f;

            try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

            // Emulate holding "S": move backwards relative to facing direction.
            vill.setSpeed(speed);
            vill.zza = -1.0f;
            vill.xxa = 0.0f;
            vill.yya = 0.0f;

        } catch (Throwable ignored) {}
    }
}

