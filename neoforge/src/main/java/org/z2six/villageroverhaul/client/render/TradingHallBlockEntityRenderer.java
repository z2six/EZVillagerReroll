package org.z2six.villageroverhaul.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.z2six.villageroverhaul.block.entity.TradingHallBlockEntity;

public final class TradingHallBlockEntityRenderer implements BlockEntityRenderer<TradingHallBlockEntity> {

    private static final ItemStack EMERALD_STACK = new ItemStack(Items.EMERALD);
    private static final float EMERALD_SCALE = 0.29F;
    private static final EmeraldRenderPose[] PILE = new EmeraldRenderPose[] {
            new EmeraldRenderPose(-0.17D, 1.014D, -0.12D, 12.0F, 0),
            new EmeraldRenderPose(-0.06D, 1.016D, -0.16D, -23.0F, 1),
            new EmeraldRenderPose(0.07D, 1.013D, -0.15D, 31.0F, 2),
            new EmeraldRenderPose(0.17D, 1.015D, -0.08D, -38.0F, 3),
            new EmeraldRenderPose(-0.19D, 1.016D, 0.00D, 44.0F, 4),
            new EmeraldRenderPose(-0.08D, 1.014D, -0.01D, -8.0F, 5),
            new EmeraldRenderPose(0.06D, 1.017D, 0.00D, 17.0F, 6),
            new EmeraldRenderPose(0.18D, 1.015D, 0.03D, -29.0F, 7),
            new EmeraldRenderPose(-0.15D, 1.013D, 0.13D, 26.0F, 8),
            new EmeraldRenderPose(-0.03D, 1.015D, 0.15D, -14.0F, 9),
            new EmeraldRenderPose(0.10D, 1.014D, 0.16D, 36.0F, 10),
            new EmeraldRenderPose(0.18D, 1.016D, 0.10D, -19.0F, 11),

            new EmeraldRenderPose(-0.11D, 1.028D, -0.07D, 22.0F, 12),
            new EmeraldRenderPose(0.00D, 1.030D, -0.10D, -33.0F, 13),
            new EmeraldRenderPose(0.11D, 1.029D, -0.03D, 15.0F, 14),
            new EmeraldRenderPose(-0.10D, 1.031D, 0.05D, -11.0F, 15),
            new EmeraldRenderPose(0.02D, 1.034D, 0.02D, 41.0F, 16),
            new EmeraldRenderPose(0.12D, 1.030D, 0.08D, -27.0F, 17),
            new EmeraldRenderPose(0.00D, 1.040D, 0.00D, 7.0F, 18),

            new EmeraldRenderPose(-0.04D, 1.043D, -0.01D, -21.0F, 19),
            new EmeraldRenderPose(0.05D, 1.046D, 0.03D, 29.0F, 20),
            new EmeraldRenderPose(0.00D, 1.052D, 0.00D, -37.0F, 21)
    };

    public TradingHallBlockEntityRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(TradingHallBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (blockEntity == null || !blockEntity.hasStoredEmeralds()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getItemRenderer() == null) return;

        int renderCount = getRenderedEmeraldCount(blockEntity.getStoredEmeralds());
        for (int i = 0; i < renderCount; i++) {
            EmeraldRenderPose emerald = PILE[i];
            poseStack.pushPose();
            poseStack.translate(0.5D + emerald.x(), emerald.y(), 0.5D + emerald.z());
            poseStack.mulPose(Axis.YP.rotationDegrees(emerald.yawDegrees()));
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
            poseStack.scale(EMERALD_SCALE, EMERALD_SCALE, EMERALD_SCALE);
            mc.getItemRenderer().renderStatic(
                    EMERALD_STACK,
                    ItemDisplayContext.FIXED,
                    packedLight,
                    OverlayTexture.NO_OVERLAY,
                    poseStack,
                    buffer,
                    blockEntity.getLevel(),
                    emerald.seed()
            );
            poseStack.popPose();
        }
    }

    private static int getRenderedEmeraldCount(int storedEmeralds) {
        if (storedEmeralds <= 0) return 0;
        int stackCount = (storedEmeralds + 63) / 64;
        return Math.max(1, Math.min(PILE.length, stackCount));
    }

    private record EmeraldRenderPose(double x, double y, double z, float yawDegrees, int seed) {}
}
