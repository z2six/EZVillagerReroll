// neoforge\src\main\java\org\z2six\villageroverhaul\client\render\VillagerHumanoidHeldItemLayer.java
package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.VillagerFactionService;

public final class VillagerHumanoidHeldItemLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

    private static final boolean ENABLE_ITEM_TRANSFORM = true;

    private final VillagerCombatArmsModel armsModel;
    private final DwarfVillagerModel dwarfModel;

    public VillagerHumanoidHeldItemLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                         VillagerCombatArmsModel armsModel) {
        this(parent, armsModel, null);
    }

    public VillagerHumanoidHeldItemLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                         VillagerCombatArmsModel armsModel,
                                         DwarfVillagerModel dwarfModel) {
        super(parent);
        this.armsModel = armsModel;
        this.dwarfModel = dwarfModel;
    }

    @Override
    public void render(PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight,
                       Villager villager,
                       float limbSwing,
                       float limbSwingAmount,
                       float partialTick,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch) {
        try {
            if (villager == null || poseStack == null || buffer == null) return;

            HeldItemRenderAnchor anchor = renderAnchor(villager);
            if (anchor == HeldItemRenderAnchor.NONE) return;
            if (anchor == HeldItemRenderAnchor.CUSTOM_ARMS && armsModel == null) return;
            if (anchor == HeldItemRenderAnchor.DWARF_ARMS && dwarfModel == null) return;

            ItemStack main = villager.getMainHandItem();
            ItemStack off  = villager.getOffhandItem();

            if (main != null && !main.isEmpty()) {
                HumanoidArm mainArm = villager.getMainArm();
                renderOneHand(villager, main, mainArm, anchor, poseStack, buffer, packedLight);
            }

            if (off != null && !off.isEmpty()) {
                HumanoidArm offArm = (villager.getMainArm() == HumanoidArm.RIGHT) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
                renderOneHand(villager, off, offArm, anchor, poseStack, buffer, packedLight);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerHumanoidHeldItemLayer.render failed (soft): {}", t.toString());
        }
    }

    private void renderOneHand(Villager villager,
                               ItemStack stack,
                               HumanoidArm arm,
                               HeldItemRenderAnchor anchor,
                               PoseStack poseStack,
                               MultiBufferSource buffer,
                               int packedLight) {
        try {
            if (stack == null || stack.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            var itemRenderer = mc.getItemRenderer();
            if (itemRenderer == null) return;

            // Which hand transform to use
            ItemDisplayContext ctx = (arm == HumanoidArm.RIGHT)
                    ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;

            poseStack.pushPose();
            try {
                translateToHand(anchor, arm, poseStack);

                if (ENABLE_ITEM_TRANSFORM) {
                    ArmorEditorProfile modelProfile = anchor == HeldItemRenderAnchor.DWARF_ARMS
                            ? ArmorEditorProfile.DWARF
                            : ArmorEditorProfile.VILLAGER;
                    WeaponEditorTransform tx = WeaponEditorState.heldTransform(
                            modelProfile,
                            WeaponEditorState.heldProfileFor(stack)
                    );
                    applyWeaponTransform(poseStack, tx);
                }

                // Render the item model (weapon/tool/etc) in third-person hand context
                // Signature is stable in 1.21.x MojMap:
                // renderStatic(entity, stack, context, leftHand, poseStack, buffer, level, light, overlay, seed)
                boolean leftHand = (arm == HumanoidArm.LEFT);

                itemRenderer.renderStatic(
                        villager,
                        stack,
                        ctx,
                        leftHand,
                        poseStack,
                        buffer,
                        villager.level(),
                        packedLight,
                        OverlayTexture.NO_OVERLAY,
                        villager.getId()
                );

            } finally {
                poseStack.popPose();
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] renderOneHand failed (soft): {}", t.toString());
        }
    }

    private static HeldItemRenderAnchor renderAnchor(Villager v) {
        try {
            boolean dwarf = VillagerFactionService.isDwarf(v);
            boolean customArms = false;
            if (v instanceof VillagerOverhaulRenderAccess acc) {
                customArms = VillagerRenderFlags.renderCustomArms(acc.ezvr$getRenderFlags());
            }
            return HeldItemRenderPolicy.anchorFor(dwarf, customArms);
        } catch (Throwable ignored) {
            return HeldItemRenderAnchor.NONE;
        }
    }

    private void translateToHand(HeldItemRenderAnchor anchor, HumanoidArm arm, PoseStack poseStack) {
        if (anchor == HeldItemRenderAnchor.DWARF_ARMS) {
            dwarfModel.translateToHand(arm, poseStack);
        } else {
            armsModel.translateToHand(arm, poseStack);
        }
    }

    private static void applyWeaponTransform(PoseStack poseStack, WeaponEditorTransform tx) {
        if (poseStack == null || tx == null) return;
        poseStack.translate(tx.tx(), tx.ty(), tx.tz());
        if (tx.rxDeg() != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(tx.rxDeg()));
        if (tx.ryDeg() != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(tx.ryDeg()));
        if (tx.rzDeg() != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(tx.rzDeg()));
        if (tx.sx() != 1.0f || tx.sy() != 1.0f || tx.sz() != 1.0f) {
            poseStack.scale(tx.sx(), tx.sy(), tx.sz());
        }
    }
}
