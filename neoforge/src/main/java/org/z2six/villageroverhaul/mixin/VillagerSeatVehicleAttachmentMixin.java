package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.z2six.villageroverhaul.VillagerSeatCompat;

@Mixin(Entity.class)
public abstract class VillagerSeatVehicleAttachmentMixin {

    @Inject(method = "getVehicleAttachmentPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void ezvr$usePlayerSeatAttachmentForVillagers(Entity vehicle, CallbackInfoReturnable<Vec3> cir) {
        try {
            if (!((Object) this instanceof Villager)) return;
            if (!VillagerSeatCompat.isLikelySeatEntity(vehicle)) return;

            Vec3 vanilla = cir.getReturnValue();
            if (vanilla == null) return;

            double y = VillagerSeatCompat.adjustSeatVehicleAttachmentY(vanilla.y);
            if (y == vanilla.y) return;
            cir.setReturnValue(new Vec3(vanilla.x, y, vanilla.z));
        } catch (Throwable ignored) {}
    }
}
