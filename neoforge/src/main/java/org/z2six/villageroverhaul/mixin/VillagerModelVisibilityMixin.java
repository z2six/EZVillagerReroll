// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/VillagerModelVisibilityMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side villager model visibility control.
 *
 * Enforced at:
 *  - setupAnim(TAIL) for whichever signature matches
 *  - renderToBuffer(HEAD) for multiple signatures
 *
 * IMPORTANT PERFORMANCE NOTE:
 * - This model instance is reused across many villagers, so do NOT log per-render.
 * - Any logging is DEBUG-only and throttled + per-UUID cached.
 */
@Mixin(VillagerModel.class)
public abstract class VillagerModelVisibilityMixin {

    @Unique private boolean ezvr$initLogged = false;
    @Unique private boolean ezvr$resolved = false;

    @Unique private ModelPart ezvr$crossedArmsPart = null;
    @Unique private String ezvr$crossedArmsPath = null;

    @Unique private ModelPart ezvr$robePart = null;
    @Unique private String ezvr$robePath = null;
    @Unique private String ezvr$robeKey = null;

    // Current entity context when renderToBuffer is called (no entity param).
    @Unique private AbstractVillager ezvr$ctxVillager = null;

    // Per-villager state to prevent spam (model instance is shared).
    @Unique private final Map<UUID, Byte> ezvr$lastFlagsByUuid = new HashMap<>();
    @Unique private final Map<UUID, Integer> ezvr$lastLogTickByUuid = new HashMap<>();

    // Debug throttling: log at most once per villager every N ticks when flags change.
    @Unique private static final int EZVR_DEBUG_LOG_INTERVAL_TICKS = 40; // ~2s

    // -------------------------------------------------------------------------
    // setupAnim hooks (multiple signatures, require=0)
    // -------------------------------------------------------------------------

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ezvr$setupAnimEntityTail(Entity entity,
                                          float limbSwing,
                                          float limbSwingAmount,
                                          float ageInTicks,
                                          float netHeadYaw,
                                          float headPitch,
                                          CallbackInfo ci) {
        ezvr$applyFromEntity(entity, "setupAnim(Entity,TAIL)");
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/npc/AbstractVillager;FFFFF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ezvr$setupAnimAbstractTail(AbstractVillager entity,
                                            float limbSwing,
                                            float limbSwingAmount,
                                            float ageInTicks,
                                            float netHeadYaw,
                                            float headPitch,
                                            CallbackInfo ci) {
        ezvr$applyFromEntity(entity, "setupAnim(AbstractVillager,TAIL)");
    }

    // -------------------------------------------------------------------------
    // renderToBuffer hooks (multiple signatures, require=0)
    // -------------------------------------------------------------------------

    @Inject(
            method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderToBufferHead4(Object poseStack,
                                          Object vertexConsumer,
                                          int packedLight,
                                          int packedOverlay,
                                          CallbackInfo ci) {
        ezvr$applyFromContext("renderToBuffer(4,HEAD)");
    }

    @Inject(
            method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderToBufferHead5(Object poseStack,
                                          Object vertexConsumer,
                                          int packedLight,
                                          int packedOverlay,
                                          int packedColor,
                                          CallbackInfo ci) {
        ezvr$applyFromContext("renderToBuffer(5,HEAD)");
    }

    @Inject(
            method = "renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$renderToBufferHeadLegacy(Object poseStack,
                                               Object vertexConsumer,
                                               int packedLight,
                                               int packedOverlay,
                                               float red,
                                               float green,
                                               float blue,
                                               float alpha,
                                               CallbackInfo ci) {
        ezvr$applyFromContext("renderToBuffer(legacy,HEAD)");
    }

    // -------------------------------------------------------------------------
    // Core apply logic
    // -------------------------------------------------------------------------

    @Unique
    private void ezvr$applyFromEntity(Object entity, String phase) {
        try {
            if (!(entity instanceof AbstractVillager villager)) return;
            ezvr$ctxVillager = villager;
            ezvr$apply(villager, phase);
        } catch (Throwable t) {
            // keep soft; avoid INFO spam
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VisibilityMixin applyFromEntity failed (soft): {}", t.toString());
            }
        }
    }

    @Unique
    private void ezvr$applyFromContext(String phase) {
        try {
            if (ezvr$ctxVillager == null) return;
            ezvr$apply(ezvr$ctxVillager, phase);
        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VisibilityMixin applyFromContext failed (soft): {}", t.toString());
            }
        }
    }

    @Unique
    private void ezvr$apply(AbstractVillager villager, String phase) {
        try {
            if (villager == null) return;

            if (!ezvr$initLogged) {
                ezvr$initLogged = true;
                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] VillagerModelVisibilityMixin ACTIVE (modelInstance={})",
                        System.identityHashCode(this)
                );
            }

            // Resolve parts once per model instance
            if (!ezvr$resolved) {
                ezvr$resolved = true;

                ModelPart root = ezvr$tryCallRoot(this);
                if (root == null) {
                    VillagerOverhaul.LOG().warn(
                            "[VillagerOverhaul] [client] VisibilityMixin: FAILED to call root() (modelInstance={})",
                            System.identityHashCode(this)
                    );
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

                // Robe/outer layer: prefer "jacket" (confirmed), fallback "bodywear"
                ezvr$resolveRobe(root);

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] VisibilityMixin resolved: crossedArmsFound={}, crossedArmsPath='{}', robeFound={}, robeKey='{}', robePath='{}'",
                        (ezvr$crossedArmsPart != null), String.valueOf(ezvr$crossedArmsPath),
                        (ezvr$robePart != null), String.valueOf(ezvr$robeKey), String.valueOf(ezvr$robePath)
                );
            }

            // Read flags (server authoritative via SynchedEntityData)
            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            boolean showRobe = VillagerRenderFlags.renderBodywear(flags);
            boolean showCrossedArms = !VillagerRenderFlags.renderCustomArms(flags);

            // Enforce
            if (ezvr$crossedArmsPart != null) ezvr$crossedArmsPart.visible = showCrossedArms;
            if (ezvr$robePart != null) ezvr$robePart.visible = showRobe;

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

                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] [client] Visibility ({}): entity={}, flags={}, showRobe={}, showCrossedArms={}, armsVis={}, robeVis={}, robeKey='{}'",
                                phase,
                                id,
                                (int) flags,
                                showRobe,
                                showCrossedArms,
                                (ezvr$crossedArmsPart != null && ezvr$crossedArmsPart.visible),
                                (ezvr$robePart != null && ezvr$robePart.visible),
                                String.valueOf(ezvr$robeKey)
                        );
                    }
                }
            }

        } catch (Throwable t) {
            if (VillagerOverhaul.LOG().isDebugEnabled()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VisibilityMixin apply failed (soft): {}", t.toString());
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
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VisibilityMixin resolveRobe failed (soft): {}", t.toString());
            }
        }
    }

    // -------------------------------------------------------------------------
    // root() lookup (no @Shadow)
    // -------------------------------------------------------------------------

    @Unique
    private static ModelPart ezvr$tryCallRoot(Object model) {
        try {
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            if (out instanceof ModelPart mp) return mp;
        } catch (Throwable ignored) {}
        return null;
    }

    // -------------------------------------------------------------------------
    // Recursive name search (reflection children map)
    // -------------------------------------------------------------------------

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
