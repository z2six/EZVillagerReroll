package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

    private static final String PD_EAT_SLOW_UNTIL = "ezvr_eat_slow_until";

    @Unique
    private long ezvr$lastManualInputAt = Long.MIN_VALUE;

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

                boolean eatingSlow = false;
                try {
                    long eatUntil = pd.getLong(PD_EAT_SLOW_UNTIL);
                    eatingSlow = eatUntil > now;
                } catch (Throwable ignored) {}

                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

                // If we recently had a high-velocity nav move (e.g. run-away), damp it so backpedal doesn't "inherit" speed.
                try {
                    Vec3 dm = vill.getDeltaMovement();
                    double horiz = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    double target = speed;
                    if (eatingSlow) {
                        try { target = (double) ((float) vill.getAttributeValue(Attributes.MOVEMENT_SPEED) * 0.25f); } catch (Throwable ignored) {}
                    }
                    // Only damp when we're carrying excessive momentum; always damping causes "snail movement".
                    double maxAllowed = Math.max(0.05, target * 1.4);
                    if (horiz > maxAllowed) {
                        double k = eatingSlow ? 0.35 : 0.15;
                        vill.setDeltaMovement(dm.x * k, dm.y, dm.z * k);
                    }
                } catch (Throwable ignored) {}

                // Emulate holding "S": move backwards relative to facing direction.
                if (eatingSlow) {
                    float base = (float) vill.getAttributeValue(Attributes.MOVEMENT_SPEED);
                    vill.setSpeed(base * 0.25f);
                } else {
                    vill.setSpeed(speed);
                }
                vill.zza = -1.0f;
                vill.xxa = 0.0f;
                vill.yya = 0.0f;
                ezvr$lastManualInputAt = now;
                return;
            }

            long circleUntil = pd.getLong(PD_CIRCLE_UNTIL);
            if (circleUntil > now) {
                float speed = pd.getFloat(PD_CIRCLE_SPEED);
                if (speed <= 0.0f) speed = 0.35f;

                boolean eatingSlow = false;
                try {
                    long eatUntil = pd.getLong(PD_EAT_SLOW_UNTIL);
                    eatingSlow = eatUntil > now;
                } catch (Throwable ignored) {}

                float dir = pd.getFloat(PD_CIRCLE_DIR);
                if (dir == 0.0f) dir = 1.0f;

                float zza = pd.getFloat(PD_CIRCLE_ZZA);

                try { vill.getNavigation().stop(); } catch (Throwable ignored) {}

                // Same damping for circling to avoid runaway velocity being preserved.
                try {
                    Vec3 dm = vill.getDeltaMovement();
                    double horiz = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    double target = speed;
                    if (eatingSlow) {
                        try { target = (double) ((float) vill.getAttributeValue(Attributes.MOVEMENT_SPEED) * 0.25f); } catch (Throwable ignored) {}
                    }
                    double maxAllowed = Math.max(0.05, target * 1.4);
                    if (horiz > maxAllowed) {
                        double k = eatingSlow ? 0.35 : 0.15;
                        vill.setDeltaMovement(dm.x * k, dm.y, dm.z * k);
                    }
                } catch (Throwable ignored) {}

                // Strafe around target while keeping facing handled elsewhere (yaw lock).
                if (eatingSlow) {
                    float base = (float) vill.getAttributeValue(Attributes.MOVEMENT_SPEED);
                    vill.setSpeed(base * 0.25f);
                } else {
                    vill.setSpeed(speed);
                }
                vill.zza = zza;
                // Full strafe input is extremely fast on mobs; scale it down for a slow "circle".
                // While eating we already clamp speed, so don't double-nerf the input.
                float strafeScale = eatingSlow ? 1.0f : 0.35f;
                vill.xxa = dir * strafeScale;
                vill.yya = 0.0f;
                ezvr$lastManualInputAt = now;
                return;
            }

            // If we applied manual inputs very recently but they are no longer active, clear them so they
            // don't "stick" and cause sideways gliding after combat ends (or circling stops).
            if (ezvr$lastManualInputAt != Long.MIN_VALUE && (now - ezvr$lastManualInputAt) <= 2L) {
                vill.zza = 0.0f;
                vill.xxa = 0.0f;
                vill.yya = 0.0f;

                // Also remove small leftover horizontal momentum so the villager returns to normal navigation quickly.
                try {
                    Vec3 dm = vill.getDeltaMovement();
                    double horiz = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
                    if (horiz < 0.08) {
                        vill.setDeltaMovement(0.0, dm.y, 0.0);
                    }
                } catch (Throwable ignored) {}
            }

        } catch (Throwable ignored) {}
    }
}
