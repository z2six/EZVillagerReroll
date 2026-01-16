// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientCommands.java
package org.z2six.villageroverhaul.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ClientCommands {

    private ClientCommands() {}

    public static void register(IEventBus bus) {
        try {
            if (bus == null) return;
            bus.addListener(ClientCommands::onRegisterClientCommands);
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] ClientCommands registered on NeoForge EVENT bus.");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] [client] Failed to register ClientCommands.", t);
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent e) {
        try {
            final CommandDispatcher<CommandSourceStack> d = e.getDispatcher();

            // /vo_modeldump [depth] [maxLines]
            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_modeldump")
                    .executes(ctx -> dumpVillagerModel(6, 500))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("depth", IntegerArgumentType.integer(0, 64))
                            .executes(ctx -> dumpVillagerModel(IntegerArgumentType.getInteger(ctx, "depth"), 500))
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("maxLines", IntegerArgumentType.integer(10, 5000))
                                    .executes(ctx -> dumpVillagerModel(
                                            IntegerArgumentType.getInteger(ctx, "depth"),
                                            IntegerArgumentType.getInteger(ctx, "maxLines")
                                    )))));

            // /vo_partvis <needle> <true|false>
            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_partvis")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("needle", StringArgumentType.greedyString())
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("visible", BoolArgumentType.bool())
                                    .executes(ctx -> setPartVisibility(
                                            StringArgumentType.getString(ctx, "needle"),
                                            BoolArgumentType.getBool(ctx, "visible")
                                    )))));

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] Registered client commands: /vo_modeldump, /vo_partvis");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] [client] RegisterClientCommandsEvent failed.", t);
        }
    }

    // =========================================================================================
    // Command impls
    // =========================================================================================

    private static int dumpVillagerModel(int maxDepth, int maxLines) {
        try {
            Villager target = findTargetVillager();
            if (target == null) {
                clientMsg("No villager targeted/found (look at one or stand near one).");
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_modeldump: no villager targeted/found.");
                return 0;
            }

            VillagerModel<Villager> model = resolveVillagerModelFor(target);
            if (model == null) {
                clientMsg("Failed to resolve VillagerModel for targeted villager (see log).");
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_modeldump: failed to resolve VillagerModel for villager={}", target.getUUID());
                return 0;
            }

            ModelPart root = tryCallRoot(model);
            if (root == null) {
                clientMsg("Failed to call VillagerModel.root() (see log).");
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_modeldump: VillagerModel.root() returned null for villager={}", target.getUUID());
                return 0;
            }

            VillagerOverhaul.LOG().info("================================================================================");
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VO MODEL DUMP for villager={} entityId={} depth={} maxLines={}",
                    target.getUUID(), target.getId(), maxDepth, maxLines);
            VillagerOverhaul.LOG().info("================================================================================");

            int lines = dumpTree(root, maxDepth, maxLines);

            VillagerOverhaul.LOG().info("================================================================================");
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] VO MODEL DUMP END (lines={})", lines);
            VillagerOverhaul.LOG().info("================================================================================");

            clientMsg("Dumped villager model to log (" + lines + " lines). Search for \"VO MODEL DUMP\".");
            return 1;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_modeldump failed (soft): {}", t.toString());
            clientMsg("Model dump failed (see log).");
            return 0;
        }
    }

    private static int setPartVisibility(String needleRaw, boolean visible) {
        try {
            String needle = (needleRaw == null) ? "" : needleRaw.trim();
            if (needle.isEmpty()) {
                clientMsg("Usage: /vo_partvis <needle> <true|false>");
                return 0;
            }

            Villager target = findTargetVillager();
            if (target == null) {
                clientMsg("No villager targeted/found (look at one or stand near one).");
                return 0;
            }

            VillagerModel<Villager> model = resolveVillagerModelFor(target);
            if (model == null) {
                clientMsg("Failed to resolve VillagerModel for targeted villager (see log).");
                return 0;
            }

            ModelPart root = tryCallRoot(model);
            if (root == null) {
                clientMsg("Failed to call VillagerModel.root() (see log).");
                return 0;
            }

            int changed = applyVisibilityByNeedle(root, needle, visible);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_partvis needle='{}' visible={} changedParts={} (NOTE: renderer model instance is shared)",
                    needle, visible, changed);

            clientMsg("Set visible=" + visible + " for " + changed + " parts matching: " + needle + " (see log).");
            return changed > 0 ? 1 : 0;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_partvis failed (soft): {}", t.toString());
            clientMsg("partvis failed (see log).");
            return 0;
        }
    }

    // =========================================================================================
    // Target selection + model resolution
    // =========================================================================================

    private static Villager findTargetVillager() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;

            LocalPlayer player = mc.player;
            if (player == null) return null;

            // 1) Crosshair target
            HitResult hr = mc.hitResult;
            if (hr instanceof EntityHitResult ehr) {
                Entity e = ehr.getEntity();
                if (e instanceof Villager v) return v;
            }

            // 2) Nearest within radius
            final double radius = 8.0;
            AABB box = player.getBoundingBox().inflate(radius);
            Villager nearest = null;
            double best = Double.MAX_VALUE;

            for (Villager v : player.level().getEntitiesOfClass(Villager.class, box)) {
                if (v == null) continue;
                double d2 = v.distanceToSqr(player);
                if (d2 < best) {
                    best = d2;
                    nearest = v;
                }
            }
            return nearest;

        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static VillagerModel<Villager> resolveVillagerModelFor(Villager villager) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;

            EntityRenderDispatcher disp = mc.getEntityRenderDispatcher();
            if (disp == null) return null;

            EntityRenderer<? super Villager> r = disp.getRenderer(villager);
            if (r == null) return null;

            // Scan renderer fields for VillagerModel
            Class<?> c = r.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f == null) continue;
                    f.setAccessible(true);
                    Object v = f.get(r);
                    if (v instanceof VillagerModel<?> vm) {
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] Resolved VillagerModel via rendererField={} in {}",
                                f.getName(), c.getName());
                        return (VillagerModel<Villager>) vm;
                    }
                }
                c = c.getSuperclass();
            }

            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] Failed to resolve VillagerModel from renderer={} (class={})",
                    r, r.getClass().getName());
            return null;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] resolveVillagerModelFor failed (soft): {}", t.toString());
            return null;
        }
    }

    private static ModelPart tryCallRoot(Object model) {
        try {
            if (model == null) return null;
            Method m = model.getClass().getMethod("root");
            Object out = m.invoke(model);
            return (out instanceof ModelPart mp) ? mp : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // =========================================================================================
    // Tree dump / apply helpers (reflection to access ModelPart children map)
    // =========================================================================================

    private static int dumpTree(ModelPart root, int maxDepth, int maxLines) {
        int lines = 0;
        try {
            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();

            // Non-recursive stack: (part, path, depth)
            Deque<Object[]> stack = new ArrayDeque<>();
            stack.push(new Object[]{root, "root", 0});

            while (!stack.isEmpty() && lines < maxLines) {
                Object[] it = stack.pop();
                ModelPart part = (ModelPart) it[0];
                String path = (String) it[1];
                int depth = (int) it[2];

                if (part == null) continue;
                if (visited.put(part, Boolean.TRUE) != null) continue;

                Map<String, ModelPart> children = getChildrenMap(part);
                int childCount = (children == null) ? -1 : children.size();

                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] PART path='{}' visible={} childCount={} rot=({}, {}, {}) pos=({}, {}, {})",
                        path,
                        safeVisible(part),
                        childCount,
                        safeF(part, "xRot"), safeF(part, "yRot"), safeF(part, "zRot"),
                        safeF(part, "x"), safeF(part, "y"), safeF(part, "z")
                );
                lines++;

                if (depth >= maxDepth) continue;
                if (children == null || children.isEmpty()) continue;

                for (Map.Entry<String, ModelPart> e : children.entrySet()) {
                    String k = e.getKey();
                    ModelPart v = e.getValue();
                    if (k == null || v == null) continue;
                    stack.push(new Object[]{v, path + "." + k, depth + 1});
                }
            }

            if (lines >= maxLines) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] (stopped: reached maxLines={})", maxLines);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] dumpTree failed (soft): {}", t.toString());
        }
        return lines;
    }

    private static int applyVisibilityByNeedle(ModelPart root, String needle, boolean visible) {
        int changed = 0;
        try {
            String n = needle.toLowerCase();

            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
            Deque<Object[]> stack = new ArrayDeque<>();
            stack.push(new Object[]{root, "root"});

            while (!stack.isEmpty()) {
                Object[] it = stack.pop();
                ModelPart part = (ModelPart) it[0];
                String path = (String) it[1];

                if (part == null) continue;
                if (visited.put(part, Boolean.TRUE) != null) continue;

                boolean match = path.toLowerCase().contains(n);
                if (match) {
                    boolean before = safeVisible(part);
                    try {
                        part.visible = visible;
                        changed++;
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_partvis matched path='{}' vis:{}->{}",
                                path, before, part.visible);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] /vo_partvis failed to set visible on path='{}' (soft): {}",
                                path, t.toString());
                    }
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

        } catch (Throwable t) {
            VillagerOverhaul.LOG().info("[VillagerOverhaul] [client] applyVisibilityByNeedle failed (soft): {}", t.toString());
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> getChildrenMap(ModelPart part) {
        try {
            if (part == null) return null;

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

    private static boolean safeVisible(ModelPart p) {
        try {
            return p != null && p.visible;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float safeF(ModelPart p, String field) {
        try {
            if (p == null || field == null) return 0.0f;
            Field f = ModelPart.class.getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(p);
            if (v instanceof Float ff) return ff;
            if (v instanceof Number n) return n.floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private static void clientMsg(String msg) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            LocalPlayer p = mc.player;
            if (p == null) return;
            p.sendSystemMessage(Component.literal("[VillagerOverhaul] " + msg));
        } catch (Throwable ignored) {}
    }
}
