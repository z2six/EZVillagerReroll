// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/server/ServerCommands.java
package org.z2six.villageroverhaul.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.PacketOpenArmorEditorScreen;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.util.ArrayList;
import java.util.List;

public final class ServerCommands {

    private ServerCommands() {}

    public static void register(IEventBus bus) {
        try {
            if (bus == null) return;
            bus.addListener(ServerCommands::onRegisterCommands);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ServerCommands registered on NeoForge EVENT bus.");
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed to register ServerCommands.", t);
        }
    }

    private static void onRegisterCommands(RegisterCommandsEvent e) {
        try {
            final CommandDispatcher<CommandSourceStack> d = e.getDispatcher();

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_takeheld")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> takeHeld(ctx.getSource(), 16.0))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Double>argument(
                                    "radius", DoubleArgumentType.doubleArg(1.0, 256.0))
                            .executes(ctx -> takeHeld(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "radius")))));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_fixgenprices")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> fixGenerosityPrices(ctx.getSource(), 32.0))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Double>argument(
                                    "radius", DoubleArgumentType.doubleArg(1.0, 256.0))
                            .executes(ctx -> fixGenerosityPrices(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "radius")))));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_renamesinglenames")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> toggleSingleNameRenaming(ctx.getSource())));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_armoreditor")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> openArmorEditor(ctx.getSource())));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_spawndwarf")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> spawnDwarf(ctx.getSource())));

            d.register(LiteralArgumentBuilder.<CommandSourceStack>literal("vo_resetfactions")
                    .requires(src -> src != null && src.hasPermission(2))
                    .executes(ctx -> resetFactions(ctx.getSource(), 32.0))
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<CommandSourceStack, Double>argument(
                                    "radius", DoubleArgumentType.doubleArg(1.0, 512.0))
                            .executes(ctx -> resetFactions(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "radius")))));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] RegisterCommandsEvent failed (server commands may be missing).", t);
        }
    }

    private static int resetFactions(CommandSourceStack source, double radius) {
        try {
            if (source == null) return 0;
            if (!(source.getEntity() instanceof ServerPlayer sp)) {
                source.sendFailure(Component.literal("Player-only command."));
                return 0;
            }
            if (!(sp.level() instanceof ServerLevel level)) return 0;

            double r = Math.max(1.0, Math.min(512.0, radius));
            AABB box = sp.getBoundingBox().inflate(r, r, r);
            List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box, v -> true);
            int changed = 0;
            for (Villager vill : villagers) {
                if (vill instanceof org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess acc) {
                    acc.ezvr$setFaction(VillagerFactionService.FACTION_HUMAN);
                    changed++;
                }
            }

            int count = changed;
            source.sendSuccess(() -> Component.literal("Reset faction to human for " + count + " villager(s)."), true);
            return changed;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int spawnDwarf(CommandSourceStack source) {
        try {
            if (source == null) return 0;
            if (!(source.getEntity() instanceof ServerPlayer sp)) {
                source.sendFailure(Component.literal("Player-only command."));
                return 0;
            }
            if (!(sp.level() instanceof ServerLevel level)) return 0;

            BlockPos pos = sp.blockPosition();
            Villager vill = EntityType.VILLAGER.spawn(level, v -> {
                if (v instanceof org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess acc) {
                    acc.ezvr$setFaction(VillagerFactionService.FACTION_DWARF);
                }
            }, pos, MobSpawnType.COMMAND, true, false);

            if (vill == null) {
                source.sendFailure(Component.literal("Failed to spawn dwarf villager."));
                return 0;
            }

            source.sendSuccess(() -> Component.literal("Spawned dwarf villager."), true);
            return 1;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int openArmorEditor(CommandSourceStack source) {
        try {
            if (source == null) return 0;
            if (!(source.getEntity() instanceof ServerPlayer sp)) {
                source.sendFailure(Component.literal("Player-only command."));
                return 0;
            }
            sp.connection.send(new ClientboundCustomPayloadPacket(new PacketOpenArmorEditorScreen()));
            source.sendSuccess(() -> Component.literal("Opening Villager Overhaul armor editor."), false);
            return 1;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int takeHeld(CommandSourceStack source, double radius) {
        try {
            if (source == null) return 0;
            if (!(source.getEntity() instanceof ServerPlayer sp)) {
                source.sendFailure(Component.literal("Player-only command."));
                return 0;
            }
            if (!(sp.level() instanceof ServerLevel level)) return 0;

            Villager vill = findNearestVillager(sp, level, radius);
            if (vill == null) {
                sp.displayClientMessage(Component.literal("No villager nearby."), true);
                return 0;
            }

            ItemStack main = vill.getMainHandItem();
            ItemStack off = vill.getOffhandItem();

            List<ItemStack> toMove = new ArrayList<>(2);
            if (main != null && !main.isEmpty()) toMove.add(main.copy());
            if (off != null && !off.isEmpty()) toMove.add(off.copy());

            if (toMove.isEmpty()) {
                sp.displayClientMessage(Component.literal("Nearest villager is not holding anything."), true);
                return 0;
            }

            if (!canFitAll(sp, toMove)) {
                sp.displayClientMessage(Component.literal("Inventory is full."), true);
                return 0;
            }

            // Add first, clear after.
            for (ItemStack s : toMove) {
                if (s == null || s.isEmpty()) continue;
                ItemStack moving = s.copy();
                sp.getInventory().add(moving);
                if (!moving.isEmpty()) {
                    sp.displayClientMessage(Component.literal("Inventory is full."), true);
                    return 0;
                }
            }

            try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY, "vo_takeheld_clear_main"); } catch (Throwable ignored) {}
            try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY, "vo_takeheld_clear_off"); } catch (Throwable ignored) {}

            vill.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);

            sp.displayClientMessage(Component.literal("Moved villager held items to your inventory."), true);
            return 1;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int fixGenerosityPrices(CommandSourceStack source, double radius) {
        try {
            if (source == null) return 0;
            if (!(source.getEntity() instanceof ServerPlayer sp)) {
                source.sendFailure(Component.literal("Player-only command."));
                return 0;
            }
            if (!(sp.level() instanceof ServerLevel level)) return 0;

            double r = Math.max(1.0, Math.min(256.0, radius));
            AABB box = sp.getBoundingBox().inflate(r, r, r);

            List<Villager> list = level.getEntitiesOfClass(Villager.class, box, v -> true);
            if (list.isEmpty()) {
                sp.displayClientMessage(Component.literal("No villagers nearby."), true);
                return 0;
            }

            int villagers = 0;
            int offersTouched = 0;

            for (Villager v : list) {
                if (v == null) continue;
                villagers++;
                offersTouched += VillagerGenerosityOfferService.forceRecalculateFromGenerosityOnly(v);
            }

            sp.displayClientMessage(Component.literal("Recalculated Generosity prices for " + villagers + " villagers (" + offersTouched + " offers)."), true);
            return villagers;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static int toggleSingleNameRenaming(CommandSourceStack source) {
        try {
            if (source == null) return 0;

            boolean enabled = VillagerNamingEvents.toggleRenameSingleNamedVillagers();
            if (!enabled) {
                source.sendSuccess(() -> Component.literal("Single-name villager renaming disabled until the next toggle. It will remain disabled after restart."), false);
                return 0;
            }

            int renamed = 0;
            if (source.getServer() != null) {
                for (ServerLevel level : source.getServer().getAllLevels()) {
                    AABB box = level.getWorldBorder().getCollisionShape().bounds();
                    List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box, villager -> true);
                    for (Villager villager : villagers) {
                        if (villager == null) continue;
                        if (VillagerNamingEvents.renameVillagerIfNeeded(villager)) {
                            renamed++;
                        }
                    }
                }
            }

            int renamedCount = renamed;
            source.sendSuccess(
                    () -> Component.literal("Single-name villager renaming enabled for this server session. Renamed " + renamedCount + " loaded villagers."),
                    true
            );
            return renamed;
        } catch (Throwable t) {
            try {
                if (source != null) source.sendFailure(Component.literal("Command failed: " + t.getClass().getSimpleName()));
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    private static Villager findNearestVillager(ServerPlayer sp, ServerLevel level, double radius) {
        try {
            if (sp == null || level == null) return null;
            double r = Math.max(1.0, Math.min(256.0, radius));
            AABB box = sp.getBoundingBox().inflate(r, r, r);

            List<Villager> list = level.getEntitiesOfClass(Villager.class, box, v -> true);
            if (list.isEmpty()) return null;

            Villager best = null;
            double bestD2 = Double.MAX_VALUE;
            var p = sp.position();
            for (Villager v : list) {
                if (v == null) continue;
                double d2 = v.position().distanceToSqr(p);
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = v;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean canFitAll(ServerPlayer sp, List<ItemStack> stacks) {
        try {
            if (sp == null) return false;
            if (stacks == null || stacks.isEmpty()) return true;

            var inv = sp.getInventory();
            if (inv == null) return false;

            List<ItemStack> slots = new ArrayList<>(inv.items.size() + inv.offhand.size());
            for (ItemStack s : inv.items) slots.add(s == null ? ItemStack.EMPTY : s.copy());
            for (ItemStack s : inv.offhand) slots.add(s == null ? ItemStack.EMPTY : s.copy());

            for (ItemStack in : stacks) {
                if (in == null || in.isEmpty()) continue;
                if (!simulateAdd(slots, in.copy(), inv.getMaxStackSize())) return false;
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean simulateAdd(List<ItemStack> slots, ItemStack stack, int invMax) {
        try {
            if (slots == null) return false;
            if (stack == null || stack.isEmpty()) return true;

            int remaining = Math.max(0, stack.getCount());
            if (remaining <= 0) return true;

            int limit = Math.min(Math.max(1, invMax), Math.max(1, stack.getMaxStackSize()));

            // Merge into existing stacks.
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                ItemStack cur = slots.get(i);
                if (cur == null || cur.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(cur, stack)) continue;
                int space = limit - cur.getCount();
                if (space <= 0) continue;
                int add = Math.min(space, remaining);
                cur.setCount(cur.getCount() + add);
                remaining -= add;
            }

            // Fill empty slots.
            for (int i = 0; i < slots.size() && remaining > 0; i++) {
                ItemStack cur = slots.get(i);
                if (cur != null && !cur.isEmpty()) continue;
                int add = Math.min(limit, remaining);
                ItemStack placed = stack.copy();
                placed.setCount(add);
                slots.set(i, placed);
                remaining -= add;
            }

            return remaining <= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
