package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
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

    private static final String PD_CIRCLE_UNTIL = "ezvr_circle_until";
    private static final String PD_CIRCLE_SPEED = "ezvr_circle_speed";
    private static final String PD_CIRCLE_DIR = "ezvr_circle_dir";
    private static final String PD_CIRCLE_ZZA = "ezvr_circle_zza";

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
            long now = vill.level().getGameTime();

            long backUntil = pd.getLong(PD_BACKPEDAL_UNTIL);
            if (backUntil > now) {
                float speed = pd.getFloat(PD_BACKPEDAL_SPEED);
                if (speed <= 0.0f) speed = 0.5f;

                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

                // If we recently had a high-velocity nav move (e.g. run-away), damp it so backpedal doesn't "inherit" speed.
                try {
                    Vec3 dm = vill.getDeltaMovement();
                    vill.setDeltaMovement(dm.x * 0.15, dm.y, dm.z * 0.15);
                } catch (Throwable ignored) {}

                // Emulate holding "S": move backwards relative to facing direction.
                vill.setSpeed(speed);
                vill.zza = -1.0f;
                vill.xxa = 0.0f;
                vill.yya = 0.0f;
                return;
            }

            long circleUntil = pd.getLong(PD_CIRCLE_UNTIL);
            if (circleUntil > now) {
                float speed = pd.getFloat(PD_CIRCLE_SPEED);
                if (speed <= 0.0f) speed = 0.35f;

                float dir = pd.getFloat(PD_CIRCLE_DIR);
                if (dir == 0.0f) dir = 1.0f;

                float zza = pd.getFloat(PD_CIRCLE_ZZA);

                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

                // Same damping for circling to avoid runaway velocity being preserved.
                try {
                    Vec3 dm = vill.getDeltaMovement();
                    vill.setDeltaMovement(dm.x * 0.15, dm.y, dm.z * 0.15);
                } catch (Throwable ignored) {}

                // Strafe around target while keeping facing handled elsewhere (yaw lock).
                vill.setSpeed(speed);
                vill.zza = zza;
                // Full strafe input is extremely fast on mobs; scale it down for a slow "circle".
                vill.xxa = dir * 0.35f;
                vill.yya = 0.0f;
            }

        } catch (Throwable ignored) {}
    }
}
