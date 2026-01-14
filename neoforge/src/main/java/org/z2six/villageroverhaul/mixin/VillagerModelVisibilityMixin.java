// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/mixin/VillagerModelVisibilityMixin.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
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
import java.util.Map;

/**
 * Applies VillagerBrain decisions to vanilla villager model part visibility:
 * - "arms" (crossed arms bone): visible when NOT rendering custom humanoid arms
 * - "bodywear" (robe): visible only when villager has NO chest AND NO legs item equipped
 *
 * IMPORTANT:
 * - VillagerModel parts are usually nested. We must search recursively.
 * - root() is not guaranteed to be public; use getDeclaredMethod + accessible.
 */
@Mixin(VillagerModel.class)
public abstract class VillagerModelVisibilityMixin {

    @Unique private boolean ezvr$resolvedParts = false;
    @Unique private boolean ezvr$loggedResolve = false;

    @Unique private ModelPart ezvr$armsPart = null;
    @Unique private ModelPart ezvr$bodywearPart = null;

    // Cached reflection access for ModelPart children map
    @Unique private static Field EZVR_MODEL_PART_CHILDREN_FIELD = null;
    @Unique private static boolean EZVR_CHILDREN_FIELD_LOOKED_UP = false;

    // --- Signature variant 1: AbstractVillager (most common) ---
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/npc/AbstractVillager;FFFFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$setupAnimAbstract(AbstractVillager villager,
                                        float limbSwing,
                                        float limbSwingAmount,
                                        float ageInTicks,
                                        float netHeadYaw,
                                        float headPitch,
                                        CallbackInfo ci) {
        ezvr$applyVisibility(villager);
    }

    // --- Signature variant 2: Villager (fallback if mappings/environment differ) ---
    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/npc/Villager;FFFFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private void ezvr$setupAnimVillager(Villager villager,
                                        float limbSwing,
                                        float limbSwingAmount,
                                        float ageInTicks,
                                        float netHeadYaw,
                                        float headPitch,
                                        CallbackInfo ci) {
        ezvr$applyVisibility(villager);
    }

    @Unique
    private void ezvr$applyVisibility(Object villagerObj) {
        try {
            if (!(villagerObj instanceof AbstractVillager av)) return;

            byte flags;
            if (av instanceof VillagerOverhaulRenderAccess acc) flags = acc.ezvr$getRenderFlags();
            else flags = VillagerRenderFlags.defaultFlags();

            boolean showBodywear = VillagerRenderFlags.renderBodywear(flags);
            boolean showCrossedArms = !VillagerRenderFlags.renderCustomArms(flags);

            if (!ezvr$resolvedParts) {
                ezvr$resolvedParts = true;

                ModelPart root = ezvr$tryGetRootPart();
                if (root != null) {
                    // More candidate names, and we search recursively now.
                    ezvr$armsPart = ezvr$findPartByNameRecursive(root,
                            "arms", "crossed_arms", "crossedArms", "villager_arms");
                    ezvr$bodywearPart = ezvr$findPartByNameRecursive(root,
                            "bodywear", "body_wear", "robe", "clothes", "jacket", "coat");
                }

                if (!ezvr$loggedResolve) {
                    ezvr$loggedResolve = true;
                    String who = (av instanceof Villager v) ? v.getUUID().toString() : av.getStringUUID();
                    VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerModelVisibilityMixin resolved parts (villager={}): armsPart={}, bodywearPart={}",
                            who,
                            (ezvr$armsPart != null),
                            (ezvr$bodywearPart != null));
                    if (ezvr$armsPart == null || ezvr$bodywearPart == null) {
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] NOTE: If parts are null, their bone names may differ in this version/model.");
                    }
                }
            }

            ezvr$setVisibleSafe(ezvr$armsPart, showCrossedArms);
            ezvr$setVisibleSafe(ezvr$bodywearPart, showBodywear);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerModelVisibilityMixin applyVisibility failed (soft): {}", t.toString());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Root resolving
    // -----------------------------------------------------------------------------------------

    @Unique
    private ModelPart ezvr$tryGetRootPart() {
        // 1) Prefer a "root()" method (often present but not necessarily public)
        try {
            Method m;
            try {
                m = this.getClass().getDeclaredMethod("root");
            } catch (NoSuchMethodException ignored) {
                m = this.getClass().getMethod("root"); // fallback
            }
            m.setAccessible(true);
            Object out = m.invoke(this);
            if (out instanceof ModelPart mp) return mp;
        } catch (Throwable ignored) {}

        // 2) Fallback: find first ModelPart field in class hierarchy
        try {
            Class<?> c = this.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof ModelPart mp) return mp;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return null;
    }

    // -----------------------------------------------------------------------------------------
    // Recursive part search (reflection on ModelPart children map)
    // -----------------------------------------------------------------------------------------

    @Unique
    private static ModelPart ezvr$findPartByNameRecursive(ModelPart root, String... targetNames) {
        try {
            if (root == null || targetNames == null || targetNames.length == 0) return null;

            // Direct check: root itself might match in odd setups
            for (String n : targetNames) {
                if (n != null && !n.isBlank() && ezvr$partNameEquals(root, n)) return root;
            }

            return ezvr$dfsFind(root, targetNames, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static ModelPart ezvr$dfsFind(ModelPart node, String[] names, int depth) {
        try {
            if (node == null) return null;
            if (depth > 64) return null; // sanity cap

            // Try getChild by name fast-path (works if direct children)
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                try {
                    ModelPart child = node.getChild(n);
                    if (child != null) return child;
                } catch (Throwable ignored) {}
            }

            // Reflect children map and DFS
            Map<String, ModelPart> children = ezvr$getChildrenMap(node);
            if (children == null || children.isEmpty()) return null;

            for (ModelPart child : children.values()) {
                if (child == null) continue;

                for (String n : names) {
                    if (n == null || n.isBlank()) continue;
                    if (ezvr$partNameEquals(child, n)) return child;
                }

                ModelPart deeper = ezvr$dfsFind(child, names, depth + 1);
                if (deeper != null) return deeper;
            }

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> ezvr$getChildrenMap(ModelPart part) {
        try {
            if (part == null) return null;

            if (!EZVR_CHILDREN_FIELD_LOOKED_UP) {
                EZVR_CHILDREN_FIELD_LOOKED_UP = true;

                // In Mojang mappings, ModelPart typically has a Map<String, ModelPart> children field.
                // Name can vary; we search for the first Map-typed field that looks like it.
                for (Field f : ModelPart.class.getDeclaredFields()) {
                    if (f == null) continue;
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);

                    // Heuristic: attempt to read; if it's a Map with ModelPart values, accept.
                    Object v = f.get(part);
                    if (v instanceof Map<?, ?> m) {
                        Object anyVal = m.values().stream().findFirst().orElse(null);
                        if (anyVal == null || anyVal instanceof ModelPart) {
                            EZVR_MODEL_PART_CHILDREN_FIELD = f;
                            break;
                        }
                    }
                }
            }

            if (EZVR_MODEL_PART_CHILDREN_FIELD == null) return null;

            Object out = EZVR_MODEL_PART_CHILDREN_FIELD.get(part);
            if (out instanceof Map<?, ?> m) return (Map<String, ModelPart>) m;

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static boolean ezvr$partNameEquals(ModelPart part, String name) {
        // ModelPart doesn't expose its name; this is only useful if we later store names.
        // For now this always returns false; kept for future improvements.
        // We primarily find by getChild(name) and recursive traversal.
        return false;
    }

    @Unique
    private static void ezvr$setVisibleSafe(ModelPart part, boolean visible) {
        try {
            if (part == null) return;
            part.visible = visible;
        } catch (Throwable ignored) {}
    }
}
