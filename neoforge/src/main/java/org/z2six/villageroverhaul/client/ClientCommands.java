// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/ClientCommands.java
package org.z2six.villageroverhaul.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
import org.z2six.villageroverhaul.client.render.ClientPartVisibilityRules;
import org.z2six.villageroverhaul.client.render.VillagerHolsteredLoadoutLayer;
import org.z2six.villageroverhaul.network.modes.PacketVillagerEatTest;
import org.z2six.villageroverhaul.network.modes.PacketVillagerForceBlock;

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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] ClientCommands registered on NeoForge EVENT bus.");
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

            /**
             * Usage:
             *   /vo_partvis <needle> <true|false>
             *   /vo_partvis clear
             *   /vo_partvis list
             *
             * NOTE: needle is a single token (no spaces). Use dot paths like root.head, root.head.hat, etc.
             */
            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_partvis")
                    .executes(ctx -> {
                        clientMsg("Usage: /vo_partvis <needle> <true|false>  OR  /vo_partvis clear  OR  /vo_partvis list");
                        return 0;
                    })
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear")
                            .executes(ctx -> {
                                ClientPartVisibilityRules.clearAll();
                                clientMsg("Cleared all part visibility rules.");
                                return 1;
                            }))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list")
                            .executes(ctx -> {
                                String s = ClientPartVisibilityRules.describeRules();
                                clientMsg(s.isEmpty() ? "No partvis rules set." : s);
                                return 1;
                            }))
                    // IMPORTANT: needle must NOT be greedyString, otherwise it eats the boolean and causes “incomplete command”
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("needle", StringArgumentType.string())
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("visible", BoolArgumentType.bool())
                                    .executes(ctx -> setPartVisibilityRule(
                                            StringArgumentType.getString(ctx, "needle"),
                                            BoolArgumentType.getBool(ctx, "visible")
                                    )))));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_blocktest")
                    .executes(ctx -> forceBlockTest(200))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("ticks", IntegerArgumentType.integer(1, 600))
                            .executes(ctx -> forceBlockTest(IntegerArgumentType.getInteger(ctx, "ticks")))));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_eat_test")
                    .executes(ctx -> eatTest()));

            /**
             * Usage:
             *   /vo_waist get
             *   /vo_waist reset
             *   /vo_waist set <tx|ty|tz|rx|ry|rz|spin|roll> <value>
             *   /vo_waist add <tx|ty|tz|rx|ry|rz|spin|roll> <delta>
             *   /vo_waist axis <x|y|z>
             *   /vo_waist rollaxis <x|y|z>
             */
            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_waist")
                    .executes(ctx -> {
                        clientMsg("Usage: /vo_waist get | reset | set <tx|ty|tz|rx|ry|rz|spin|roll> <value> | add <tx|ty|tz|rx|ry|rz|spin|roll> <delta> | axis <x|y|z> | rollaxis <x|y|z>");
                        clientMsg("Current: " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("get")
                            .executes(ctx -> waistGet()))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reset")
                            .executes(ctx -> waistReset()))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("axis")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("axis", StringArgumentType.word())
                                    .executes(ctx -> waistAxis(StringArgumentType.getString(ctx, "axis")))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("rollaxis")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("axis", StringArgumentType.word())
                                    .executes(ctx -> waistRollAxis(StringArgumentType.getString(ctx, "axis")))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("set")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("param", StringArgumentType.word())
                                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Float>argument("value", FloatArgumentType.floatArg())
                                            .executes(ctx -> waistSet(
                                                    StringArgumentType.getString(ctx, "param"),
                                                    FloatArgumentType.getFloat(ctx, "value"),
                                                    false
                                            )))))
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("add")
                            .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, String>argument("param", StringArgumentType.word())
                                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Float>argument("delta", FloatArgumentType.floatArg())
                                            .executes(ctx -> waistSet(
                                                    StringArgumentType.getString(ctx, "param"),
                                                    FloatArgumentType.getFloat(ctx, "delta"),
                                                    true
                                            ))))));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_holster_tweak")
                    .executes(ctx -> openHolsterTweak()));

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] Registered client commands: /vo_modeldump, /vo_partvis, /vo_blocktest, /vo_eat_test, /vo_waist, /vo_holster_tweak");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] [client] RegisterClientCommandsEvent failed.", t);
        }
    }

    // =========================================================================================
    // Command impls
    // =========================================================================================

    private static int dumpVillagerModel(int maxDepth, int maxLines) {
        try {
            if (!clientHasOp()) {
                clientMsg("You must be an operator to use /vo_modeldump.");
                return 0;
            }
            Villager target = findTargetVillager();
            if (target == null) {
                clientMsg("No villager targeted/found (look at one or stand near one).");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_modeldump: no villager targeted/found.");
                return 0;
            }

            VillagerModel<Villager> model = resolveVillagerModelFor(target);
            if (model == null) {
                clientMsg("Failed to resolve VillagerModel for targeted villager (see log).");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_modeldump: failed to resolve VillagerModel for villager={}", target.getUUID());
                return 0;
            }

            ModelPart root = tryCallRoot(model);
            if (root == null) {
                clientMsg("Failed to call VillagerModel.root() (see log).");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_modeldump: VillagerModel.root() returned null for villager={}", target.getUUID());
                return 0;
            }

            VillagerOverhaul.LOG().debug("================================================================================");
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VO MODEL DUMP for villager={} entityId={} depth={} maxLines={}",
                    target.getUUID(), target.getId(), maxDepth, maxLines);
            VillagerOverhaul.LOG().debug("================================================================================");

            int lines = dumpTree(root, maxDepth, maxLines);

            VillagerOverhaul.LOG().debug("================================================================================");
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] VO MODEL DUMP END (lines={})", lines);
            VillagerOverhaul.LOG().debug("================================================================================");

            clientMsg("Dumped villager model to log (" + lines + " lines). Search for \"VO MODEL DUMP\".");
            return 1;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_modeldump failed (soft): {}", t.toString());
            clientMsg("Model dump failed (see log).");
            return 0;
        }
    }

    private static int eatTest() {
        try {
            if (!clientHasOp()) {
                clientMsg("You must be an operator to use /vo_eat_test.");
                return 0;
            }
            Villager target = findTargetVillager();
            if (target == null) {
                clientMsg("No villager found (look at one or stand closer).");
                return 0;
            }
            ClientNetwork.sendToServer(new PacketVillagerEatTest(target.getId()));
            clientMsg("Requested eat test for villager id=" + target.getId());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_eat_test failed: " + t);
            return 0;
        }
    }

    private static int setPartVisibilityRule(String needleRaw, boolean visible) {
        try {
            if (!clientHasOp()) {
                clientMsg("You must be an operator to use /vo_partvis.");
                return 0;
            }
            String needle = (needleRaw == null) ? "" : needleRaw.trim();
            if (needle.isEmpty()) {
                clientMsg("Usage: /vo_partvis <needle> <true|false>");
                return 0;
            }

            // Persist the rule (the mixin will enforce it every frame)
            ClientPartVisibilityRules.setRule(needle, visible);

            // Apply immediately as well (so the user sees it instantly without waiting a tick)
            int changedNow = 0;
            Villager target = findTargetVillager();
            if (target != null) {
                VillagerModel<Villager> model = resolveVillagerModelFor(target);
                if (model != null) {
                    ModelPart root = tryCallRoot(model);
                    if (root != null) {
                        changedNow = ClientPartVisibilityRules.applyToRoot(root);
                    }
                }
            }

            clientMsg("Rule set: " + needle + " -> visible=" + visible + " (enforced every frame). changedNow=" + changedNow);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_partvis rule needle='{}' visible={} changedNow={}", needle, visible, changedNow);
            return 1;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] /vo_partvis failed (soft): {}", t.toString());
            clientMsg("partvis failed (see log).");
            return 0;
        }
    }

    private static int waistGet() {
        try {
            clientMsg("Waist: " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_waist get failed: " + t);
            return 0;
        }
    }

    private static int waistReset() {
        try {
            VillagerHolsteredLoadoutLayer.ezvr$resetWaistTweak();
            clientMsg("Waist reset. " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_waist reset failed: " + t);
            return 0;
        }
    }

    private static int waistAxis(String axis) {
        try {
            boolean ok = VillagerHolsteredLoadoutLayer.ezvr$setWaistSpinAxis(axis);
            if (!ok) {
                clientMsg("Invalid axis '" + axis + "'. Use: x y z");
                return 0;
            }
            clientMsg("Waist updated. " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_waist axis failed: " + t);
            return 0;
        }
    }

    private static int waistRollAxis(String axis) {
        try {
            boolean ok = VillagerHolsteredLoadoutLayer.ezvr$setWaistRollAxis(axis);
            if (!ok) {
                clientMsg("Invalid axis '" + axis + "'. Use: x y z");
                return 0;
            }
            clientMsg("Waist updated. " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_waist rollaxis failed: " + t);
            return 0;
        }
    }

    private static int waistSet(String param, float value, boolean additive) {
        try {
            boolean ok = VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(param, value, additive);
            if (!ok) {
                clientMsg("Unknown param '" + param + "'. Use: tx ty tz rx ry rz spin roll");
                return 0;
            }
            clientMsg("Waist updated. " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString());
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_waist set/add failed: " + t);
            return 0;
        }
    }

    private static int openHolsterTweak() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return 0;
            mc.setScreen(new HolsterTweakScreen(mc.screen));
            return 1;
        } catch (Throwable t) {
            clientMsg("vo_holster_tweak failed: " + t);
            return 0;
        }
    }

    private static int forceBlockTest(int ticks) {
        try {
            if (!clientHasOp()) {
                clientMsg("You must be an operator to use /vo_blocktest.");
                return 0;
            }
            Villager target = findTargetVillager();
            if (target == null) {
                clientMsg("No villager targeted/found (look at one or stand near one).");
                return 0;
            }
            ClientNetwork.sendToServer(new PacketVillagerForceBlock(target.getId(), ticks));
            clientMsg("Force block requested for " + ticks + " ticks.");
            return 1;
        } catch (Throwable t) {
            clientMsg("Force block failed (see log).");
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
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] Resolved VillagerModel via rendererField={} in {}",
                                f.getName(), c.getName());
                        return (VillagerModel<Villager>) vm;
                    }
                }
                c = c.getSuperclass();
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] Failed to resolve VillagerModel from renderer={} (class={})",
                    r, r.getClass().getName());
            return null;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] resolveVillagerModelFor failed (soft): {}", t.toString());
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
    // Tree dump helpers (unchanged)
    // =========================================================================================

    private static int dumpTree(ModelPart root, int maxDepth, int maxLines) {
        int lines = 0;
        try {
            IdentityHashMap<ModelPart, Boolean> visited = new IdentityHashMap<>();
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

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] PART path='{}' visible={} childCount={} rot=({}, {}, {}) pos=({}, {}, {})",
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
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] (stopped: reached maxLines={})", maxLines);
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [client] dumpTree failed (soft): {}", t.toString());
        }
        return lines;
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

    private static boolean clientHasOp() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return false;
            return mc.player.hasPermissions(2);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
