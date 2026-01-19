package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobYawLockMixin {
    private static final String PD_LOCK_YAW_UNTIL = "ezvr_lock_yaw_until";
    private static final String PD_LOCK_YAW = "ezvr_lock_yaw";

    @Inject(method = "aiStep", at = @At("TAIL"), require = 0)
    private void ezvr$lockYawDuringBackpedal(CallbackInfo ci) {
        try {
            Mob self = (Mob) (Object) this;
            if (!(self instanceof Villager vill)) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            CompoundTag pd = vill.getPersistentData();
            long until = pd.getLong(PD_LOCK_YAW_UNTIL);
            if (until <= 0L) return;

            long now = vill.level().getGameTime();
            if (now >= until) return;

            float yaw = pd.getFloat(PD_LOCK_YAW);

            vill.setYRot(yaw);
            vill.yRotO = yaw;
            vill.setYHeadRot(yaw);
            vill.setYBodyRot(yaw);
        } catch (Throwable ignored) {}
    }
}

