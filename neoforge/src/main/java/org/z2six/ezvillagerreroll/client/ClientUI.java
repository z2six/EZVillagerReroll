// MainFile: src/main/java/org/z2six/ezvillagerreroll/client/ClientUI.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.mixin.MerchantMenuAccessor;
import org.z2six.ezvillagerreroll.network.ClientSyncedConfig;
import org.z2six.ezvillagerreroll.network.ClientTooltipCache;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketRequestReroll;
import org.z2six.ezvillagerreroll.network.PacketTooltipData;
import org.z2six.ezvillagerreroll.network.PacketTooltipQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ClientUI {

    private static final long TOOLTIP_REFRESH_DEBOUNCE_MS = 750;
    private static final Map<Screen, Button> REROLL_BUTTONS = new WeakHashMap<>();

    public static void registerRuntimeClientEvents() {
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenClosed);
        EZVillagerReroll.LOG().info("[EZVR] ClientUI.registerRuntimeClientEvents(): handlers added");
    }

    private static void onScreenInitPost(final ScreenEvent.Init.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            // bake client config lazily (in case user changes without restart)
            ClientConfig.bake();

            int left = (screen.width - 276) / 2;
            int top = (screen.height - 166) / 2;

            int baseX = left + 276 - 22;
            int baseY = top + 6;

            int x = baseX + ClientConfig.buttonOffsetX;
            int y = baseY + ClientConfig.buttonOffsetY;

            int w = 18, h = 18;

            Button reroll = Button.builder(Component.literal("↻"), btn -> {
                        try {
                            Network.sendToServer(new PacketRequestReroll());
                            EZVillagerReroll.LOG().debug("[EZVR] Client clicked reroll button; sent PacketRequestReroll");
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] Client send reroll packet failed", t);
                        }

                        try {
                            btn.setFocused(false);
                            Screen scr = Minecraft.getInstance().screen;
                            if (scr != null && scr.getFocused() == btn) scr.setFocused(null);
                        } catch (Throwable ignored) {}
                    })
                    .pos(x, y).size(w, h)
                    .createNarration(s -> Component.translatable("ezvr.ui.reroll"))
                    .build();

            e.addListener(reroll);
            REROLL_BUTTONS.put(screen, reroll);

            EZVillagerReroll.LOG().info("[EZVR] Reroll button added to MerchantScreen at ({},{}), base=({},{}), offset=({},{}).",
                    x, y, baseX, baseY, ClientConfig.buttonOffsetX, ClientConfig.buttonOffsetY
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenInitPost exception", t);
        }
    }

    private static void onScreenRenderPost(final ScreenEvent.Render.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;
            Button btn = REROLL_BUTTONS.get(screen);
            if (btn == null) return;

            if (btn.isMouseOver(e.getMouseX(), e.getMouseY())) {
                if (ClientTooltipCache.ageMs() > TOOLTIP_REFRESH_DEBOUNCE_MS) {
                    trySendTooltipQuery(screen);
                }

                List<Component> lines = buildTooltipLines(ClientTooltipCache.get());
                if (lines.isEmpty()) {
                    // If we haven't received anything yet, show sync state
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) {
                        lines = List.of(Component.literal("Syncing… (cfg v" + cfg.version + ")"));
                    } else {
                        lines = List.of(Component.literal("Syncing…"));
                    }
                }

                GuiGraphics gg = e.getGuiGraphics();
                gg.renderComponentTooltip(Minecraft.getInstance().font, lines, e.getMouseX(), e.getMouseY());
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenRenderPost exception", t);
        }
    }

    private static void onScreenClosed(final ScreenEvent.Closing e) {
        try {
            REROLL_BUTTONS.remove(e.getScreen());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] onScreenClosed exception", t);
        }
    }

    private static void trySendTooltipQuery(MerchantScreen screen) {
        try {
            if (screen.getMenu() instanceof MerchantMenu menu) {
                var trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Entity ent) {
                    Network.sendToServer(new PacketTooltipQuery(ent.getId()));
                } else if (trader instanceof AbstractVillager av) {
                    Network.sendToServer(new PacketTooltipQuery(av.getId()));
                } else {
                    Network.sendToServer(new PacketTooltipQuery(-1));
                }
                EZVillagerReroll.LOG().debug("[EZVR] Sent tooltip query for trader={}", trader == null ? "null" : trader.getClass().getName());
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] Client send tooltip query failed", t);
        }
    }

    private static List<Component> buildTooltipLines(PacketTooltipData d) {
        List<Component> lines = new ArrayList<>();
        if (d == null) return lines;

        lines.add(Component.translatable("ezvr.ui.reroll"));

        if (d.cost.scaledCost <= 0 || d.cfg.freeMode) {
            lines.add(Component.literal("Cost: Free"));
        } else {
            String itemName = "Unknown Item";
            try {
                ResourceLocation id = d.cost.item;
                if (id != null) {
                    var item = BuiltInRegistries.ITEM.get(id);
                    if (item != null) itemName = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
                } else if (d.cost.itemOrTag != null && d.cost.itemOrTag.startsWith("#")) {
                    itemName = d.cost.itemOrTag; // tag literal
                }
            } catch (Throwable ignored) {}

            String affordMark = d.afford.canAfford ? "✔" : "✖";
            String src = switch (d.afford.source == null ? "none" : d.afford.source) {
                case "wallet" -> " (from wallet)";
                case "inventory" -> " (from inventory)";
                case "both" -> " (wallet/inventory)";
                default -> "";
            };
            lines.add(Component.literal("Cost: " + d.cost.scaledCost + " × " + itemName + " " + affordMark + src));
        }

        lines.add(Component.literal("Villager: L" + d.villager.level + " (" + d.villager.xp + " XP)"));

        if (d.cost.nextCostIfUsed != null)
            lines.add(Component.literal("Next cost: " + d.cost.nextCostIfUsed));
        if (d.cost.maxCostPossible != null)
            lines.add(Component.literal("Max cost: " + d.cost.maxCostPossible));

        if (d.cfg.capEnabled && d.cap.enabled && d.cap.cap > 0) {
            if (d.cap.remaining >= 0) {
                lines.add(Component.literal("Daily uses: " + d.cap.remaining + " / " + d.cap.cap));
            } else {
                lines.add(Component.literal("Daily uses: — / " + d.cap.cap));
            }
        }

        // Optional: show server cfg version/hash for debugging packs
        if (d.cfg.version > 0) {
            lines.add(Component.literal("Config: v" + d.cfg.version + " (hash " + d.cfg.hash + ")"));
        }

        return lines;
    }

    private ClientUI() {}
}
