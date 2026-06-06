package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

import java.lang.reflect.Field;
import java.util.Locale;

public final class ArmorEditorRenderContext {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private ArmorEditorRenderContext() {}

    private static final class State {
        int depth;
        ArmorEditorSettings.PartKey selectedPart;
    }

    public static void push(ArmorEditorSettings.PartKey selectedPart) {
        State s = STATE.get();
        s.depth++;
        s.selectedPart = selectedPart;
    }

    public static void pop() {
        State s = STATE.get();
        s.depth = Math.max(0, s.depth - 1);
        if (s.depth == 0) {
            s.selectedPart = null;
        }
    }

    public static boolean active() {
        return STATE.get().depth > 0 && STATE.get().selectedPart != null;
    }

    public static ArmorEditorSettings.PartKey selectedPart() {
        return STATE.get().selectedPart;
    }

    public static boolean visible(ArmorEditorSettings.PartKey part) {
        if (!active()) return true;
        ArmorEditorSettings.PartKey selected = selectedPart();
        if (part == null) return true;
        if (selected == ArmorEditorSettings.PartKey.HEAD && part == ArmorEditorSettings.PartKey.HAT) return true;
        return selected == part;
    }

    public static void applyToHumanoidModel(HumanoidModel<?> model) {
        if (!active() || model == null) return;
        try {
            if (model.head != null) model.head.visible = visible(ArmorEditorSettings.PartKey.HEAD);
            if (model.hat != null) model.hat.visible = visible(ArmorEditorSettings.PartKey.HAT);
            if (model.body != null) model.body.visible = visible(ArmorEditorSettings.PartKey.BODY);
            if (model.rightArm != null) model.rightArm.visible = visible(ArmorEditorSettings.PartKey.RIGHT_ARM);
            if (model.leftArm != null) model.leftArm.visible = visible(ArmorEditorSettings.PartKey.LEFT_ARM);
            if (model.rightLeg != null) model.rightLeg.visible = visible(ArmorEditorSettings.PartKey.RIGHT_LEG);
            if (model.leftLeg != null) model.leftLeg.visible = visible(ArmorEditorSettings.PartKey.LEFT_LEG);
        } catch (Throwable ignored) {}
    }

    public static void applyToVillagerModel(Object model) {
        if (!active() || model == null) return;
        try {
            Class<?> c = model.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object value = f.get(model);
                        if (!(value instanceof ModelPart part)) continue;
                        ArmorEditorSettings.PartKey key = classifyPartField(f.getName());
                        if (key != null) part.visible = visible(key);
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    public static void restoreCoreVillagerModelParts(Object model) {
        if (model == null) return;
        try {
            Class<?> c = model.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!ModelPart.class.isAssignableFrom(f.getType())) continue;
                        if (!isCoreVillagerModelField(f.getName())) continue;
                        f.setAccessible(true);
                        Object value = f.get(model);
                        if (value instanceof ModelPart part) {
                            part.visible = true;
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isCoreVillagerModelField(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.isBlank()) return false;
        if (n.equals("head") || n.endsWith(".head")) return true;
        if (n.equals("body") || n.endsWith(".body")) return true;
        if (n.contains("right") && n.contains("leg")) return true;
        return n.contains("left") && n.contains("leg");
    }

    private static ArmorEditorSettings.PartKey classifyPartField(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (n.isBlank()) return null;
        if (n.contains("right") && n.contains("leg")) return ArmorEditorSettings.PartKey.RIGHT_LEG;
        if (n.contains("left") && n.contains("leg")) return ArmorEditorSettings.PartKey.LEFT_LEG;
        if (n.contains("right") && n.contains("arm")) return ArmorEditorSettings.PartKey.RIGHT_ARM;
        if (n.contains("left") && n.contains("arm")) return ArmorEditorSettings.PartKey.LEFT_ARM;
        if (n.equals("head") || n.contains("head")) return ArmorEditorSettings.PartKey.HEAD;
        if (n.contains("hat")) return ArmorEditorSettings.PartKey.HAT;
        if (n.contains("body")) return ArmorEditorSettings.PartKey.BODY;
        if (n.equals("arms") || n.contains("arms")) return ArmorEditorSettings.PartKey.BODY;
        return null;
    }
}
