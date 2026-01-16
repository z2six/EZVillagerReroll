// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerVanillaCrossedArmsItemLayerGate.java
package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ListIterator;

/**
 * Not an event.
 *
 * Utility responsible for installing a per-villager "gate" around vanilla layer(s)
 * that render the held item on the villager chest when using vanilla crossed arms.
 *
 * Decision source of truth: server-side VillagerBrain sets FLAG_RENDER_CUSTOM_ARMS.
 *
 * Enforcement:
 *  - If custom arms => DO NOT render vanilla crossed-arms item layer.
 *  - If vanilla crossed arms => DO render it.
 */
public final class VillagerVanillaCrossedArmsItemLayerGate {

    private VillagerVanillaCrossedArmsItemLayerGate() {}

    /**
     * Installs a per-villager gate by wrapping matching vanilla layer(s) in-place.
     *
     * @return number of layers wrapped
     */
    public static int install(LivingEntityRenderer<Villager, VillagerModel<Villager>> villagerRenderer) {
        int wrapped = 0;

        try {
            if (villagerRenderer == null) return 0;

            List<?> layersList = findLayersList(villagerRenderer);
            if (layersList == null) return 0;

            @SuppressWarnings("unchecked")
            List<Object> layers = (List<Object>) layersList;

            // Attempt to load CrossedArmsItemLayer class (if present under MojMap name)
            Class<?> crossedLayerClass = null;
            try {
                crossedLayerClass = Class.forName("net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer");
            } catch (Throwable ignored) {
                crossedLayerClass = null;
            }

            ListIterator<Object> it = layers.listIterator();
            while (it.hasNext()) {
                Object layer = it.next();
                if (layer == null) continue;

                if (!(layer instanceof RenderLayer<?, ?> rl)) continue;

                // Don't double-wrap
                if (layer instanceof GateVanillaCrossedArmsItemLayer) continue;

                if (isCrossedArmsItemLayer(layer, crossedLayerClass)) {
                    @SuppressWarnings("unchecked")
                    RenderLayer<Villager, VillagerModel<Villager>> cast =
                            (RenderLayer<Villager, VillagerModel<Villager>>) rl;

                    it.set(new GateVanillaCrossedArmsItemLayer(villagerRenderer, cast));
                    wrapped++;
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] VillagerVanillaCrossedArmsItemLayerGate.install failed (soft): {}", t.toString());
        }

        return wrapped;
    }

    private static List<?> findLayersList(LivingEntityRenderer<?, ?> renderer) {
        try {
            Class<?> c = renderer.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    if (!List.class.isAssignableFrom(f.getType())) continue;

                    f.setAccessible(true);
                    Object v = f.get(renderer);
                    if (!(v instanceof List<?> list)) continue;

                    // Heuristic: list is empty or contains RenderLayer
                    if (list.isEmpty()) return list;
                    Object first = list.get(0);
                    if (first instanceof RenderLayer<?, ?>) return list;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isCrossedArmsItemLayer(Object layer, Class<?> crossedLayerClassOrNull) {
        try {
            if (layer == null) return false;

            // 1) Strong check: class-based
            if (crossedLayerClassOrNull != null && crossedLayerClassOrNull.isInstance(layer)) {
                return true;
            }

            // 2) Name-based fallback (handles subclasses too)
            String name = layer.getClass().getName();
            if (name == null) return false;

            return name.contains("CrossedArmsItemLayer")
                    || name.contains("VillagerItem")
                    || name.contains("VillagerHeldItem");

        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Wrapper layer:
     * - If VillagerBrain says "custom arms" -> CANCEL vanilla chest-held-item layer
     * - Else -> DELEGATE to vanilla layer normally
     */
    private static final class GateVanillaCrossedArmsItemLayer extends RenderLayer<Villager, VillagerModel<Villager>> {

        private final RenderLayer<Villager, VillagerModel<Villager>> delegate;

        GateVanillaCrossedArmsItemLayer(RenderLayerParent<Villager, VillagerModel<Villager>> parent,
                                        RenderLayer<Villager, VillagerModel<Villager>> delegate) {
            super(parent);
            this.delegate = delegate;
        }

        @Override
        public void render(com.mojang.blaze3d.vertex.PoseStack poseStack,
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
                if (villager == null) return;
                if (delegate == null) return;

                byte flags = VillagerRenderFlags.defaultFlags();
                if (villager instanceof VillagerOverhaulRenderAccess acc) {
                    flags = acc.ezvr$getRenderFlags();
                }

                // The decision is server-side: custom arms => do NOT render crossed-arms chest item layer.
                if (!VillagerRenderFlags.renderVanillaCrossedArmsItemLayer(flags)) {
                    return;
                }

                delegate.render(poseStack, buffer, packedLight, villager,
                        limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);

            } catch (Throwable ignored) {}
        }
    }
}
