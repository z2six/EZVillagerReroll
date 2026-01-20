package org.z2six.villageroverhaul.mixin.villagerCombat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(MoveControl.class)
public abstract class MoveControlEatSlowMixin {
    private static final String PD_EAT_SLOW_UNTIL = "ezvr_eat_slow_until";

    @Shadow protected Mob mob;

    @ModifyArg(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;setSpeed(F)V"),
            index = 0,
            require = 0
    )
    private float ezvr$scaleMoveControlSpeedWhileEating(float original) {
        try {
            if (!(this.mob instanceof Villager vill)) return original;
            if (vill.level() == null || vill.level().isClientSide()) return original;

            CompoundTag pd = vill.getPersistentData();
            long until = pd.getLong(PD_EAT_SLOW_UNTIL);
            if (until <= 0L) return original;

            long now = vill.level().getGameTime();
            if (now >= until) return original;

            // Only apply during active item use; we still want the villager to move normally when the tag is stale.
            boolean using = false;
            try { using = vill.isUsingItem(); } catch (Throwable ignored) { using = false; }
            if (!using) return original;

            // Use a consistent "0.25x base movement speed" while eating.
            // Using `original * 0.25` can become effectively zero for some MoveControl operations (e.g. STRAFE already scales).
            float base = 0.0f;
            try { base = (float) vill.getAttributeValue(Attributes.MOVEMENT_SPEED); } catch (Throwable ignored) { base = 0.0f; }
            if (base <= 0.0f) return original * 0.25f;
            return base * 0.25f;
        } catch (Throwable ignored) {
            return original;
        }
    }
}
