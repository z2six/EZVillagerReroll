package org.z2six.villageroverhaul.mixin.villagerRendering;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.client.render.VillagerSeatRenderUtil;

@Mixin(VillagerModel.class)
public abstract class VillagerModelPassengerPoseMixin {

    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ezvr$setupPassengerLegPose(Entity entity,
                                            float limbSwing,
                                            float limbSwingAmount,
                                            float ageInTicks,
                                            float netHeadYaw,
                                            float headPitch,
                                            CallbackInfo ci) {
        if (!(entity instanceof AbstractVillager)) return;
        if (!(entity instanceof Villager villager) || !VillagerSeatRenderUtil.shouldApplySeatPassengerPose(villager)) {
            resetPassengerLegSpread();
            return;
        }

        this.rightLeg.xRot = -1.4137167F;
        this.rightLeg.yRot = (float) (Math.PI / 10.0D);
        this.rightLeg.zRot = 0.07853982F;
        this.leftLeg.xRot = -1.4137167F;
        this.leftLeg.yRot = (float) (-Math.PI / 10.0D);
        this.leftLeg.zRot = -0.07853982F;
    }

    private void resetPassengerLegSpread() {
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = 0.0F;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = 0.0F;
    }
}
