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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side villager model visibility control.
 *
 * Correct approach:
 * - Apply visibility AFTER vanilla setupAnim runs (TAIL), because vanilla may reset .visible during setupAnim.
 * - No @Shadow (no refmap).
 * - Find ModelPart children via reflection.
 *
 * IMPORTANT: In 1.21.1 EntityModel#setupAnim uses Entity as the first param.
 * DO NOT use Object here: mixin validates descriptors strictly.
 */
@Mixin(VillagerModel.class)
public abstract class VillagerModelVisibilityMixin {

    @Unique private boolean ezvr$initLogged = false;

    @Unique private boolean ezvr$resolved = false;

    @Unique private ModelPart ezvr$armsPart = null;
    @Unique private String ezvr$armsPath = null;

    @Unique private ModelPart ezvr$bodywearPart = null;
    @Unique private String ezvr$bodywearPath = null;

    // model instance renders multiple villagers; keep minimal log state
    @Unique private UUID ezvr$lastUuid = null;
    @Unique private byte ezvr$lastFlags = (byte) 0x7F;

    /**
     * Primary hook: matches the actual runtime signature in 1.21.1.
     * We then filter to AbstractVillager inside.
     */
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
            at = @At("TAIL"),
            require = 0
    )
    private void ezvr$setupAnimEntity(Entity entity,
                                      float limbSwing,
                                      float limbSwingAmount,
                                      float ageInTicks,
                                      float netHeadYaw,
                                      float headPitch,
                                      CallbackInfo ci) {
        ezvr$apply(entity);
    }

    // -------------------------------------------------------------------------
    // Apply visibility based on synced flags
    // -------------------------------------------------------------------------

    @Unique
    private void ezvr$apply(Entity entity) {
        try {
            if (!(entity instanceof AbstractVillager villager)) return;

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
                    VillagerOverhaul.LOG().info(
                            "[VillagerOverhaul] [client] VisibilityMixin: FAILED to call root() (modelInstance={})",
                            System.identityHashCode(this)
                    );
                    return;
                }

                // Crossed arms: "arms" preferred, fallback "bone"
                String[] armsPathOut = new String[1];
                ModelPart arms = ezvr$findFirstByName(root, "arms", armsPathOut);
                if (arms != null) {
                    ezvr$armsPart = arms;
                    ezvr$armsPath = armsPathOut[0];
                } else {
                    String[] bonePathOut = new String[1];
                    ModelPart bone = ezvr$findFirstByName(root, "bone", bonePathOut);
                    ezvr$armsPart = bone;
                    ezvr$armsPath = bonePathOut[0];
                }

                // Robe quad
                String[] bwPathOut = new String[1];
                ezvr$bodywearPart = ezvr$findFirstByName(root, "bodywear", bwPathOut);
                ezvr$bodywearPath = bwPathOut[0];

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] VisibilityMixin resolved: armsFound={}, armsPath='{}', bodywearFound={}, bodywearPath='{}'",
                        (ezvr$armsPart != null), String.valueOf(ezvr$armsPath),
                        (ezvr$bodywearPart != null), String.valueOf(ezvr$bodywearPath)
                );
            }

            // Read flags (server authoritative via SynchedEntityData)
            byte flags = VillagerRenderFlags.defaultFlags();
            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                flags = acc.ezvr$getRenderFlags();
            }

            boolean showBodywear = VillagerRenderFlags.renderBodywear(flags);
            boolean showCrossedArms = !VillagerRenderFlags.renderCustomArms(flags);

            boolean prevArmsVis = (ezvr$armsPart != null) && ezvr$armsPart.visible;
            boolean prevBodywearVis = (ezvr$bodywearPart != null) && ezvr$bodywearPart.visible;

            // TAIL of setupAnim -> overrides vanilla final values for this frame
            if (ezvr$armsPart != null) ezvr$armsPart.visible = showCrossedArms;
            if (ezvr$bodywearPart != null) ezvr$bodywearPart.visible = showBodywear;

            // Log changes per villager+flags
            UUID id = villager.getUUID();
            if (id != null && (!id.equals(ezvr$lastUuid) || ezvr$lastFlags != flags)) {
                ezvr$lastUuid = id;
                ezvr$lastFlags = flags;

                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] Visibility applied entity={}, flags={}, showBodywear={}, showCrossedArms={}, armsVis:{}->{} bodywearVis:{}->{}",
                        id,
                        (int) flags,
                        showBodywear,
                        showCrossedArms,
                        prevArmsVis,
                        (ezvr$armsPart != null && ezvr$armsPart.visible),
                        prevBodywearVis,
                        (ezvr$bodywearPart != null && ezvr$bodywearPart.visible)
                );
            }

            // Low-noise heartbeat so you can confirm it keeps running even if flags don't change
            int t = villager.tickCount;
            if ((t % 80) == 0) { // ~4 seconds
                VillagerOverhaul.LOG().info(
                        "[VillagerOverhaul] [client] Visibility heartbeat entity={}, flags={}, armsVisible={}, bodywearVisible={}",
                        id,
                        (int) flags,
                        (ezvr$armsPart != null && ezvr$armsPart.visible),
                        (ezvr$bodywearPart != null && ezvr$bodywearPart.visible)
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VisibilityMixin apply failed (soft): {}", t.toString());
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
    // Recursive name search
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
