// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/VillagerRendererVisibilityMixin.java
package org.z2six.villageroverhaul.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Primarily diagnostic: confirms we can inject into the actual inherited render signature in 1.21.1.
 * (Your previous Villager-typed descriptor often won't match, so it never ran.)
 *
 * We DON'T rely on this to hide parts; VillagerModelVisibilityMixin is the authoritative one (TAIL of setupAnim).
 */
@Mixin(VillagerRenderer.class)
public abstract class VillagerRendererVisibilityMixin {

    @Unique private boolean ezvr$initLogged = false;
    @Unique private boolean ezvr$resolved = false;

    @Unique private VillagerModel<?> ezvr$model = null;

    @Unique private String ezvr$armsPath = null;
    @Unique private String ezvr$bodywearPath = null;

    // This is the inherited signature you actually get at runtime (LivingEntity param).
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderLiving(LivingEntity entity,
                                   float entityYaw,
                                   float partialTick,
                                   PoseStack poseStack,
                                   MultiBufferSource buffer,
                                   int packedLight,
                                   CallbackInfo ci) {
        try {
            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] VillagerRendererVisibilityMixin ACTIVE (rendererInstance={})",
                        System.identityHashCode(this)
                );
            }

            if (!(entity instanceof Villager)) return;

            if (!ezvr$resolved) {
                ezvr$resolved = true;

                ezvr$model = ezvr$findVillagerModelFromRenderer(this);
                if (ezvr$model == null) {
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] RendererVisibilityMixin: FAILED to find VillagerModel field on renderer");
                    return;
                }

                ModelPart root = ezvr$tryCallRoot(ezvr$model);
                if (root == null) {
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] RendererVisibilityMixin: FAILED to call model.root()");
                    return;
                }

                String[] armsPathOut = new String[1];
                ModelPart arms = ezvr$findFirstByName(root, "arms", armsPathOut);
                if (arms != null) ezvr$armsPath = armsPathOut[0];

                String[] bwPathOut = new String[1];
                ModelPart bw = ezvr$findFirstByName(root, "bodywear", bwPathOut);
                if (bw != null) ezvr$bodywearPath = bwPathOut[0];

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] RendererVisibilityMixin resolved paths: armsPath='{}', bodywearPath='{}'",
                        String.valueOf(ezvr$armsPath),
                        String.valueOf(ezvr$bodywearPath)
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] RendererVisibilityMixin failed (soft): {}", t.toString());
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (no @Shadow, no nested classes)
    // -------------------------------------------------------------------------

    @Unique
    private static ModelPart ezvr$tryCallRoot(Object model) {
        try {
            if (model == null) return null;
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            return (out instanceof ModelPart mp) ? mp : null;
        } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private static VillagerModel<?> ezvr$findVillagerModelFromRenderer(Object renderer) {
        try {
            if (renderer == null) return null;

            Class<?> c = renderer.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    f.setAccessible(true);
                    Object v = f.get(renderer);
                    if (v instanceof VillagerModel<?> vm) return vm;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private static ModelPart ezvr$findFirstByName(ModelPart root, String wanted, String[] outPath) {
        try {
            if (outPath != null && outPath.length > 0) outPath[0] = null;
            if (root == null || wanted == null || wanted.isBlank()) return null;

            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
            return ezvr$findRec(root, wanted, "root", visited, 0, outPath);

        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static ModelPart ezvr$findRec(ModelPart node,
                                          String wanted,
                                          String path,
                                          IdentityHashMap<ModelPart, Boolean> visited,
                                          int depth,
                                          String[] outPath) {
        try {
            if (node == null) return null;
            if (visited.put(node, Boolean.TRUE) != null) return null;
            if (depth > 64) return null;

            Map<String, ModelPart> children = ezvr$getChildrenMap(node);
            if (children == null || children.isEmpty()) return null;

            ModelPart direct = children.get(wanted);
            if (direct != null) {
                if (outPath != null && outPath.length > 0) outPath[0] = path + "." + wanted;
                return direct;
            }

            for (Map.Entry<String, ModelPart> e : children.entrySet()) {
                String k = e.getKey();
                ModelPart v = e.getValue();
                if (k == null || v == null) continue;

                ModelPart found = ezvr$findRec(v, wanted, path + "." + k, visited, depth + 1, outPath);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @SuppressWarnings("unchecked")
    @Unique
    private static Map<String, ModelPart> ezvr$getChildrenMap(ModelPart part) {
        try {
            for (Field f : ModelPart.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(part);
                if (!(v instanceof Map<?, ?> m)) continue;

                if (!m.isEmpty()) {
                    Object anyKey = m.keySet().iterator().next();
                    Object anyVal = m.values().iterator().next();
                    if (anyKey instanceof String && anyVal instanceof ModelPart) {
                        return (Map<String, ModelPart>) m;
                    }
                } else {
                    return (Map<String, ModelPart>) m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
