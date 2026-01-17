// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/ClientPartVisibilityRules.java
package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.geom.ModelPart;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientPartVisibilityRules {

    private ClientPartVisibilityRules() {}

    private static final Object LOCK = new Object();
    // insertion order matters; last match wins
    private static final LinkedHashMap<String, Boolean> RULES = new LinkedHashMap<>();

    // cached reflective children map field
    private static volatile Field CACHED_CHILDREN_FIELD = null;
    private static volatile boolean CHILDREN_FIELD_SCANNED = false;

    public static void setRule(String needleRaw, boolean visible) {
        try {
            String needle = (needleRaw == null) ? "" : needleRaw.trim();
            if (needle.isEmpty()) return;

            synchronized (LOCK) {
                RULES.put(needle, visible);
            }
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] rule set: needle='{}' visible={}", needle, visible);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] setRule failed (soft): {}", t.toString());
        }
    }

    public static void clearAll() {
        try {
            synchronized (LOCK) {
                RULES.clear();
            }
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] cleared all rules.");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] clearAll failed (soft): {}", t.toString());
        }
    }

    public static boolean hasAny() {
        try {
            synchronized (LOCK) {
                return !RULES.isEmpty();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String describeRules() {
        try {
            Map<String, Boolean> snap = snapshotRules();
            if (snap.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("PartVis: ");
            boolean first = true;
            for (Map.Entry<String, Boolean> e : snap.entrySet()) {
                if (!first) sb.append(" | ");
                first = false;
                sb.append("'").append(e.getKey()).append("'=").append(e.getValue());
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static Map<String, Boolean> snapshotRules() {
        try {
            synchronized (LOCK) {
                if (RULES.isEmpty()) return Map.of();
                return new LinkedHashMap<>(RULES);
            }
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /**
     * Apply current rules to a model instance (best-effort).
     * Used by the mixin every frame and optionally by the command for immediate feedback.
     */
    public static int applyToModel(Object model) {
        try {
            if (model == null) return 0;
            Map<String, Boolean> rules = snapshotRules();
            if (rules.isEmpty()) return 0;

            ModelPart root = tryResolveRoot(model);
            if (root == null) return 0;

            return applyAll(root, rules);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] applyToModel failed (soft): {}", t.toString());
            return 0;
        }
    }

    /**
     * Apply current rules to a known root ModelPart.
     */
    public static int applyToRoot(ModelPart root) {
        try {
            if (root == null) return 0;
            Map<String, Boolean> rules = snapshotRules();
            if (rules.isEmpty()) return 0;
            return applyAll(root, rules);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] [partvis] applyToRoot failed (soft): {}", t.toString());
            return 0;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------------------------

    private static int applyAll(ModelPart root, Map<String, Boolean> rules) {
        int changed = 0;

        IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, "root"});

        while (!stack.isEmpty()) {
            Object[] it = stack.pop();
            ModelPart part = (ModelPart) it[0];
            String path = (String) it[1];

            if (part == null || path == null) continue;
            if (visited.put(part, Boolean.TRUE) != null) continue;

            Boolean desired = resolveDesired(path, rules);
            if (desired != null) {
                // 1) The normal field
                try {
                    boolean before = part.visible;
                    if (before != desired) {
                        part.visible = desired;
                        changed++;
                    }
                } catch (Throwable ignored) {}

                // 2) Extra “nuke” booleans if they exist on this MC version/mapping
                // (best-effort: does nothing if fields don't exist)
                changed += trySetExtraBoolean(part, "skipDraw", !desired) ? 1 : 0;
                changed += trySetExtraBoolean(part, "hidden", !desired) ? 1 : 0;
            }

            Map<String, ModelPart> children = getChildrenMap(part);
            if (children == null || children.isEmpty()) continue;

            for (Map.Entry<String, ModelPart> e : children.entrySet()) {
                String k = e.getKey();
                ModelPart v = e.getValue();
                if (k == null || v == null) continue;
                stack.push(new Object[]{v, path + "." + k});
            }
        }

        return changed;
    }

    private static Boolean resolveDesired(String path, Map<String, Boolean> rules) {
        try {
            String pl = path.toLowerCase();
            Boolean desired = null; // last match wins
            for (Map.Entry<String, Boolean> e : rules.entrySet()) {
                String needle = e.getKey();
                if (needle == null) continue;
                String nl = needle.toLowerCase();
                if (nl.isEmpty()) continue;
                if (pl.contains(nl)) desired = e.getValue();
            }
            return desired;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean trySetExtraBoolean(ModelPart part, String fieldName, boolean value) {
        try {
            Field f = ModelPart.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            if (f.getType() == boolean.class) {
                boolean before = f.getBoolean(part);
                if (before != value) {
                    f.setBoolean(part, value);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> getChildrenMap(ModelPart part) {
        try {
            if (part == null) return null;

            Field f = getChildrenField();
            if (f == null) return null;

            Object v = f.get(part);
            if (!(v instanceof Map<?, ?> m)) return null;

            // verify key/value shape when non-empty
            if (!m.isEmpty()) {
                Object anyKey = m.keySet().iterator().next();
                Object anyVal = m.values().iterator().next();
                if (!(anyKey instanceof String) || !(anyVal instanceof ModelPart)) return null;
            }

            return (Map<String, ModelPart>) m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field getChildrenField() {
        try {
            if (CHILDREN_FIELD_SCANNED) return CACHED_CHILDREN_FIELD;
            CHILDREN_FIELD_SCANNED = true;

            for (Field f : ModelPart.class.getDeclaredFields()) {
                if (f == null) continue;
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                // best-effort: the first Map field that looks like children
                CACHED_CHILDREN_FIELD = f;
                return f;
            }
        } catch (Throwable ignored) {}
        return CACHED_CHILDREN_FIELD;
    }

    /**
     * Robust root discovery across model types.
     */
    private static ModelPart tryResolveRoot(Object model) {
        if (model == null) return null;

        try {
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            if (out instanceof ModelPart mp) return mp;
        } catch (Throwable ignored) {}

        try {
            Method m = model.getClass().getMethod("getRootPart");
            Object out = m.invoke(model);
            if (out instanceof ModelPart mp) return mp;
        } catch (Throwable ignored) {}

        try {
            Class<?> c = model.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(model);
                    if (v instanceof ModelPart mp) return mp;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return null;
    }
}