// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/villagerRendering/VillagerRendererVisibilityMixin.java
package org.z2six.villageroverhaul.mixin.villagerRendering;

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
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.client.render.VillagerHatVisibilityEnforcer;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative enforcement at the actual render entrypoint used in 1.21.1:
 *   render(LivingEntity;FF;PoseStack;MultiBufferSource;I)
 *
 * PERFORMANCE NOTE:
 * - Absolutely no per-render INFO logging.
 * - Optional DEBUG logging is per-UUID + throttled.
 */
@Mixin(VillagerRenderer.class)
public abstract class VillagerRendererVisibilityMixin {

    @Unique private boolean ezvr$initLogged = false;

    @Unique private boolean ezvr$resolved = false;
    @Unique private VillagerModel<?> ezvr$model = null;

    @Unique private ModelPart ezvr$crossedArmsPart = null;
    @Unique private String ezvr$crossedArmsPath = null;

    @Unique private ModelPart ezvr$robePart = null;
    @Unique private String ezvr$robePath = null;
    @Unique private String ezvr$robeKey = null;

    // NEW: Hat resolution (root.head.hat)
    @Unique private VillagerHatVisibilityEnforcer.ResolvedHat ezvr$hatResolved = null;

    // Per-villager state to prevent spam.
    @Unique private final Map<UUID, Byte> ezvr$lastFlagsByUuid = new HashMap<>();
    @Unique private final Map<UUID, Integer> ezvr$lastLogTickByUuid = new HashMap<>();
    @Unique private static final int EZVR_DEBUG_LOG_INTERVAL_TICKS = 40; // ~2s

    // This is the inherited signature you actually get at runtime (LivingEntity param).
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderLivingHead(LivingEntity entity,
                                       float entityYaw,
                                       float partialTick,
                                       PoseStack poseStack,
                                       MultiBufferSource buffer,
                                       int packedLight,
                                       CallbackInfo ci) {
        try {
            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] [client] VillagerRendererVisibilityMixin ACTIVE (rendererInstance={})",
                        System.identityHashCode(this)
                );
            }

            if (!(entity instanceof Villager villager)) return;

            // Resolve model/parts once per renderer instance
            if (!ezvr$resolved) {
                ezvr$resolved = true;

                ezvr$model = ezvr$findVillagerModelFromRenderer(this);
                if (ezvr$model == null) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] [client] RendererVisibilityMixin: FAILED to find VillagerModel field on renderer");
                    return;
                }

                ModelPart root = ezvr$tryCallRoot(ezvr$model);
                if (root == null) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] [client] RendererVisibilityMixin: FAILED to call model.root()");
                    return;
                }

                // Crossed arms: "arms" preferred, fallback "bone"
                String[] armsPathOut = new String[1];
                ModelPart arms = ezvr$findFirstByName(root, "arms", armsPathOut);
                if (arms != null) {
                    ezvr$crossedArmsPart = arms;
                    ezvr$crossedArmsPath = armsPathOut[0];
                } else {
                    String[] bonePathOut = new String[1];
                    ModelPart bone = ezvr$findFirstByName(root, "bone", bonePathOut);
                    ezvr$crossedArmsPart = bone;
                    ezvr$crossedArmsPath = bonePathOut[0];
                }

                // Robe: jacket confirmed by dump
                ezvr$resolveRobe(root);

                // NEW: Hat resolve (root.head.hat)
                ezvr$hatResolved = VillagerHatVisibilityEnforcer.resolve(root);

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] [client] RendererVisibilityMixin resolved: crossedArmsFound={}, crossedArmsPath='{}', robeFound={}, robeKey='{}', robePath='{}', hatFound={}, hatPath='{}'",
                        (ezvr$crossedArmsPart != null), String.valueOf(ezvr$crossedArmsPath),
                        (ezvr$robePart != null), String.valueOf(ezvr$robeKey), String.valueOf(ezvr$robePath),
                        (ezvr$hatResolved != null && ezvr$hatResolved.hatPart != null),
                        (ezvr$hatResolved == null ? "null" : String.valueOf(ezvr$hatResolved.hatPath))
                );
            }

            // Read flags
            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            boolean showRobe = VillagerRenderFlags.renderBodywear(flags);
            boolean showCrossedArms = !VillagerRenderFlags.renderCustomArms(flags);

            // Enforce right before draw (this is the magic)
            if (ezvr$crossedArmsPart != null) ezvr$crossedArmsPart.visible = showCrossedArms;
            if (ezvr$robePart != null) ezvr$robePart.visible = showRobe;

            // NEW: enforce hat visibility
            VillagerHatVisibilityEnforcer.apply(ezvr$hatResolved, flags);

            // DEBUG-only, throttled, per-UUID (NO INFO SPAM)
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                UUID id = villager.getUUID();
                if (id != null) {
                    Byte last = ezvr$lastFlagsByUuid.get(id);
                    int tick = villager.tickCount;
                    Integer lastTick = ezvr$lastLogTickByUuid.get(id);

                    boolean changed = (last == null) || (last.byteValue() != flags);
                    boolean allow = (lastTick == null) || (tick - lastTick) >= EZVR_DEBUG_LOG_INTERVAL_TICKS;

                    if (changed && allow) {
                        ezvr$lastFlagsByUuid.put(id, flags);
                        ezvr$lastLogTickByUuid.put(id, tick);

                        boolean hatVis = false;
                        try { hatVis = (ezvr$hatResolved != null && ezvr$hatResolved.hatPart != null && ezvr$hatResolved.hatPart.visible); } catch (Throwable ignored) {}

                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] [client] RendererVisibility: entity={}, flags={}, showRobe={}, showCrossedArms={}, crossedArmsVis={}, robeVis={}, robeKey='{}', hatVis={}",
                                id,
                                (int) flags,
                                showRobe,
                                showCrossedArms,
                                (ezvr$crossedArmsPart != null && ezvr$crossedArmsPart.visible),
                                (ezvr$robePart != null && ezvr$robePart.visible),
                                String.valueOf(ezvr$robeKey),
                                hatVis
                        );
                    }
                }
            }

        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] RendererVisibilityMixin failed (soft): {}", t.toString());
            }
        }
    }

    @Unique
    private void ezvr$resolveRobe(ModelPart root) {
        try {
            String[] out = new String[1];

            // Priority: jacket (confirmed), then bodywear, then robe/clothes.
            ModelPart p = ezvr$findFirstByName(root, "jacket", out);
            if (p != null) {
                ezvr$robePart = p;
                ezvr$robePath = out[0];
                ezvr$robeKey = "jacket";
                return;
            }

            p = ezvr$findFirstByName(root, "bodywear", out);
            if (p != null) {
                ezvr$robePart = p;
                ezvr$robePath = out[0];
                ezvr$robeKey = "bodywear";
                return;
            }

            p = ezvr$findFirstByName(root, "robe", out);
            if (p != null) {
                ezvr$robePart = p;
                ezvr$robePath = out[0];
                ezvr$robeKey = "robe";
                return;
            }

            p = ezvr$findFirstByName(root, "clothes", out);
            if (p != null) {
                ezvr$robePart = p;
                ezvr$robePath = out[0];
                ezvr$robeKey = "clothes";
                return;
            }

            ezvr$robePart = null;
            ezvr$robePath = null;
            ezvr$robeKey = null;

        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] RendererVisibilityMixin resolveRobe failed (soft): {}", t.toString());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (no @Shadow, no inner classes)
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
