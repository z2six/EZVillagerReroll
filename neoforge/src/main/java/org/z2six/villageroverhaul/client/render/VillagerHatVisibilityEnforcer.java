// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/VillagerHatVisibilityEnforcer.java
package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.geom.ModelPart;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Best-effort per-villager enforcement for hiding the villager hat part:
 * target: root.head.hat
 *
 * This exists because vanilla re-toggles hat visibility (head/hat/hat_rim) frequently.
 * We apply at multiple hooks (setupAnim TAIL, hatVisible TAIL, renderToBuffer HEAD).
 */
public final class VillagerHatVisibilityEnforcer {

    private VillagerHatVisibilityEnforcer() {}

    public static final class ResolvedHat {
        public final ModelPart headPart;
        public final ModelPart hatPart;
        public final String headPath;
        public final String hatPath;

        ResolvedHat(ModelPart headPart, ModelPart hatPart, String headPath, String hatPath) {
            this.headPart = headPart;
            this.hatPart = hatPart;
            this.headPath = headPath;
            this.hatPath = hatPath;
        }
    }

    /** Resolve root.head.hat best-effort using reflection access to children maps. */
    public static ResolvedHat resolve(ModelPart root) {
        try {
            if (root == null) return null;

            String[] headPathOut = new String[1];
            ModelPart head = findFirstByName(root, "head", headPathOut);

            if (head == null) {
                return new ResolvedHat(null, null, null, null);
            }

            // Prefer direct child "hat" under head
            String[] hatPathOut = new String[1];
            ModelPart hat = findFirstByName(head, "hat", hatPathOut);

            // If not found, return head-only (safe)
            return new ResolvedHat(head, hat, headPathOut[0], hatPathOut[0]);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] HatEnforcer.resolve failed (soft): {}", t.toString());
            return null;
        }
    }

    /** Apply visibility to the resolved hat part based on server flags. */
    public static void apply(ResolvedHat resolved, byte flags) {
        try {
            if (resolved == null) return;
            if (resolved.hatPart == null) return;

            boolean shouldRenderHat = VillagerRenderFlags.renderHat(flags);
            setVisibleBestEffort(resolved.hatPart, shouldRenderHat);
        } catch (Throwable t) {
            // soft
        }
    }

    private static void setVisibleBestEffort(ModelPart part, boolean visible) {
        try {
            if (part == null) return;

            // vanilla field
            try {
                part.visible = visible;
            } catch (Throwable ignored) {}

            // extra booleans (best-effort; mirrors ClientPartVisibilityRules "nuke" approach)
            trySetBoolean(part, "skipDraw", !visible);
            trySetBoolean(part, "hidden", !visible);

        } catch (Throwable ignored) {}
    }

    private static void trySetBoolean(ModelPart part, String fieldName, boolean value) {
        try {
            Field f = ModelPart.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (f.getType() == boolean.class) {
                f.setBoolean(part, value);
            }
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Small recursive lookup helpers (reflection children map)
    // -------------------------------------------------------------------------

    private static ModelPart findFirstByName(ModelPart root, String wanted, String[] outPath) {
        try {
            if (outPath != null && outPath.length > 0) outPath[0] = null;
            if (root == null || wanted == null || wanted.isBlank()) return null;

            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
            return findRec(root, wanted, "root", visited, 0, outPath);

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ModelPart findRec(ModelPart node,
                                     String wanted,
                                     String path,
                                     IdentityHashMap<ModelPart, Boolean> visited,
                                     int depth,
                                     String[] outPath) {
        try {
            if (node == null) return null;
            if (visited.put(node, Boolean.TRUE) != null) return null;
            if (depth > 64) return null;

            Map<String, ModelPart> children = getChildrenMap(node);
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

                ModelPart found = findRec(v, wanted, path + "." + k, visited, depth + 1, outPath);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> getChildrenMap(ModelPart part) {
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
