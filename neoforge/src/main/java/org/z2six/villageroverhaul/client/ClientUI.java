// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientUI.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.config.ClientConfig;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.mixin.MerchantScreenAccessor;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.tooltip.ClientTooltipCache;
import org.z2six.villageroverhaul.network.ClientTradeLockCache;
import org.z2six.villageroverhaul.network.autoReroll.PacketRequestReroll;
import org.z2six.villageroverhaul.network.autoReroll.PacketRerollCooldownQuery;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipData;
import org.z2six.villageroverhaul.network.tooltip.PacketTooltipQuery;
import org.z2six.villageroverhaul.network.trades.PacketTradeLocksQuery;
import org.z2six.villageroverhaul.network.ClientVillagerStatsCache;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeData;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsQuery;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitCostData;
import net.minecraft.world.entity.npc.Villager;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolInteractRequest;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolOpenGui;
import org.z2six.villageroverhaul.network.PacketOpenVillagerInventory;
import org.z2six.villageroverhaul.network.autoReroll.PacketSearchCatalogQuery;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitGateData;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitGateQuery;
import org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;

public final class ClientUI {

    private static final long TOOLTIP_REFRESH_DEBOUNCE_MS = 750;

    private static final long VILLAGER_STATS_REFRESH_DEBOUNCE_MS = 1500;

    private static final Map<Screen, Button> REROLL_BUTTONS = new WeakHashMap<>();
    private static final Map<Screen, CooldownOverlayWidget> COOLDOWN_OVERLAYS = new WeakHashMap<>();
    private static final Map<Screen, Button> STATS_BUTTONS = new WeakHashMap<>();

    // Commands palette state
    private static final Map<Screen, Boolean> COMMANDS_EXPANDED = new WeakHashMap<>();
    private static final Map<Screen, List<Button>> COMMANDS_SUB_BUTTONS = new WeakHashMap<>();

    private static final String VillagerOverhaul_TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    // Tooltip rendering tuning
    private static final int TIP_PAD_X = 6;
    private static final int TIP_PAD_Y = 6;
    private static final int TIP_LINE_GAP = 2;
    private static final int TIP_ICON_SIZE = 9;
    private static final int TIP_ICON_GAP = 3;
    private static final int TIP_Z = 400;

    private static final int COLOR_WHITE_OPAQUE = 0xFFFFFFFF;

    private static final Map<Screen, Button> INVENTORY_BUTTONS = new WeakHashMap<>();
    private static final Map<Screen, Button> COMMANDS_BUTTONS = new WeakHashMap<>();

    private static final long RECRUIT_STATE_STALE_MS = 3000;

    // Commands palette visuals
    private static final Map<Screen, CommandsBackdropWidget> COMMANDS_BACKDROPS = new WeakHashMap<>();
    private static final Map<Screen, List<RowHeaderIconWidget>> COMMANDS_HEADER_ICONS = new WeakHashMap<>();

    // Patrol
    private static final long MODE_STALE_MS = 1000;
    private static final Map<Integer, Long> MODE_AT = new WeakHashMap<>();
    private static final Map<Integer, String> MODE_ID = new WeakHashMap<>();
    private static final Map<Integer, Long> COMBAT_MODE_AT = new WeakHashMap<>();
    private static final Map<Integer, String> COMBAT_MODE_ID = new WeakHashMap<>();
    // Movement buttons per screen: key is "neutral"/"idle"/"follow"/"patrol"
    private static final Map<Screen, Map<String, Button>> MOVEMENT_BTNS = new WeakHashMap<>();
    // Combat buttons per screen: key is "flee"/"defend"/"aggressive"
    private static final Map<Screen, Map<String, Button>> COMBAT_BTNS = new WeakHashMap<>();

    private static final class RecruitStateSnap {
        final boolean recruited;
        final boolean canUseControls; // owner == this player
        final long atMs;
        RecruitStateSnap(boolean recruited, boolean canUseControls, long atMs) {
            this.recruited = recruited;
            this.canUseControls = canUseControls;
            this.atMs = atMs;
        }
    }

    private static final Map<Integer, RecruitStateSnap> RECRUIT_STATE = new HashMap<>();
    private static final Map<Integer, Long> UI_PAUSE_AT = new WeakHashMap<>();
    private static final long UI_PAUSE_KEEPALIVE_MS = 600;

    // Quick-actions open debounce
    private static int PENDING_QUICK_VILLAGER_ID = -1;
    private static long PENDING_QUICK_AT_MS = 0L;
    private static final long QUICK_OPEN_DELAY_MS = 120;
    private static final long QUICK_OPEN_TIMEOUT_MS = 800;

    public static void openVillagerInventory(MerchantScreen parent, int villagerEntityId) {
        try {
            if (villagerEntityId <= 0) return;

            // IMPORTANT:
            // Do NOT instantiate VillagerInventoryScreen directly.
            // This is a server-opened menu screen. We request it from the server and the client
            // will automatically open the registered screen when the menu arrives.
            ClientNetwork.sendToServer(new PacketOpenVillagerInventory(villagerEntityId));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openVillagerInventory failed", t);
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("Failed to open Villager Inventory (see log).").withStyle(ChatFormatting.RED),
                            true
                    );
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void acceptVillagerModeData(PacketVillagerModeData p) {
        try {
            if (p == null) return;
            MODE_ID.put(p.villagerEntityId(), p.modeId() == null ? "neutral" : p.modeId());
            MODE_AT.put(p.villagerEntityId(), System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    public static void acceptVillagerCombatModeData(PacketVillagerCombatModeData p) {
        try {
            if (p == null) return;
            COMBAT_MODE_ID.put(p.villagerEntityId(), p.combatModeId() == null ? "off" : p.combatModeId());
            COMBAT_MODE_AT.put(p.villagerEntityId(), System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    private static boolean isVillagerTrader(MerchantScreen screen) {
        try {
            int id = resolveTraderEntityId(screen);
            if (id <= 0) return false;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return false;

            return mc.level.getEntity(id) instanceof Villager;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean shouldRefreshRecruitState(int traderEntityId) {
        try {
            if (traderEntityId <= 0) return false;
            RecruitStateSnap snap = RECRUIT_STATE.get(traderEntityId);
            if (snap == null) return true;
            return (System.currentTimeMillis() - snap.atMs) > RECRUIT_STATE_STALE_MS;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void trySendRecruitStateQueryIfNeeded(MerchantScreen screen) {
        try {
            if (screen == null) return;
            if (!isVillagerTrader(screen)) return;

            int id = resolveTraderEntityId(screen);
            if (id <= 0) return;

            if (!shouldRefreshRecruitState(id)) return;

            ClientNetwork.sendToServer(new PacketRecruitGateQuery(id));
        } catch (Throwable ignored) {}
    }

    /**
     * Called from ClientNetworkHandlers when PacketRecruitCostData arrives.
     */
    public static void acceptRecruitCostData(PacketRecruitCostData p) {
        try {
            if (p == null) return;
            int id = p.villagerEntityId();
            if (id <= 0) return;

            // RecruitCostData does NOT contain "canUseControls".
            // Cache recruited state only; ownership defaults to false until we get RecruitGateData.
            RecruitStateSnap prev = RECRUIT_STATE.get(id);
            boolean canUse = prev != null && prev.canUseControls;
            RECRUIT_STATE.put(id, new RecruitStateSnap(p.alreadyRecruited(), canUse, System.currentTimeMillis()));
        } catch (Throwable ignored) {}
    }

    private static void setUiButtonsVisible(Screen screen, boolean controlsVisibleAndEnabled) {
        try {
            Button b;

            // Controls gated
            b = REROLL_BUTTONS.get(screen);
            if (b != null) { b.visible = controlsVisibleAndEnabled; b.active = controlsVisibleAndEnabled; }

            b = INVENTORY_BUTTONS.get(screen);
            if (b != null) { b.visible = controlsVisibleAndEnabled; b.active = controlsVisibleAndEnabled; }

            b = COMMANDS_BUTTONS.get(screen);
            if (b != null) { b.visible = controlsVisibleAndEnabled; b.active = controlsVisibleAndEnabled; }

            CooldownOverlayWidget ov = COOLDOWN_OVERLAYS.get(screen);
            if (ov != null) {
                ov.visible = controlsVisibleAndEnabled;
                if (!controlsVisibleAndEnabled) ov.active = false;
            }

            // Info is ALWAYS available
            b = STATS_BUTTONS.get(screen);
            if (b != null) { b.visible = true; b.active = true; }

            // If controls are gated off, always collapse palette
            if (!controlsVisibleAndEnabled) {
                collapseCommands(screen);
            }

        } catch (Throwable ignored) {}
    }

    public static void registerRuntimeClientEvents() {
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenInitPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenRenderPost);
        NeoForge.EVENT_BUS.addListener(ClientUI::onScreenClosed);

        // RMB on villager during PATROL_SETUP -> server decides if GUI should open
        NeoForge.EVENT_BUS.addListener(ClientUI::onPlayerInteractEntity);

        NeoForge.EVENT_BUS.addListener(ClientUI::onClientTickPost);

        VillagerOverhaul.LOG().info("[VillagerOverhaul] ClientUI.registerRuntimeClientEvents(): handlers added");
    }

    private static void onPlayerInteractEntity(final PlayerInteractEvent.EntityInteract e) {
        try {
            if (e == null) return;
            if (e.getLevel() == null || !e.getLevel().isClientSide()) return;
            if (e.getHand() != InteractionHand.MAIN_HAND) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            Entity target = e.getTarget();
            if (!(target instanceof Villager)) return;

            int id = target.getId();

            // Always ask server about patrol setup GUI eligibility (existing behavior)
            ClientNetwork.sendToServer(new PacketPatrolInteractRequest(id));

            // Also query our gate state so UI can show/hide controls
            try {
                ClientNetwork.sendToServer(new PacketRecruitGateQuery(id));
            } catch (Throwable ignored) {}

            // If no screen is currently open, schedule quick-actions overlay
            if (mc.screen == null) {
                PENDING_QUICK_VILLAGER_ID = id;
                PENDING_QUICK_AT_MS = System.currentTimeMillis();
            }

        } catch (Throwable ignored) {}
    }

    public static void acceptPatrolOpenGui(PacketPatrolOpenGui p) {
        try {
            if (p == null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Case A: server says open the setup GUI (only happens in PATROL_SETUP for owner)
            if (p.canOpen()) {
                mc.setScreen(new PatrolSetupScreen(p.villagerEntityId(), p.waypointCount()));
                return;
            }

            // Case B: we are on the PatrolBeginPromptScreen and we just needed the "has existing route" bit.
            if (mc.screen instanceof PatrolBeginPromptScreen prompt) {
                if (prompt.getVillagerEntityId() == p.villagerEntityId()) {
                    prompt.acceptServerState(p.hasPatrolData());
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] acceptPatrolOpenGui failed", t);
        }
    }

    public static Button getRerollButtonFor(Screen screen) {
        try {
            if (screen == null) return null;
            return REROLL_BUTTONS.get(screen);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ClientUI.getRerollButtonFor failed (soft): {}", t.toString());
            return null;
        }
    }

    public static Button getStatsButtonFor(Screen screen) {
        try {
            if (screen == null) return null;
            return STATS_BUTTONS.get(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void openSearchCatalogScreen(MerchantScreen parent, int villagerEntityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (parent == null) return;

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Opening search catalog UI (villagerEntityId={})", villagerEntityId);

            ClientNetwork.sendToServer(new PacketSearchCatalogQuery(villagerEntityId));
            mc.setScreen(new SearchCatalogScreen(parent));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openSearchCatalogScreen failed", t);
        }
    }

    public static void openVillagerStatsPlaceholder(MerchantScreen parent, int villagerEntityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || parent == null) return;

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Opening VillagerInfoScreen (villagerEntityId={})", villagerEntityId);
            mc.setScreen(new VillagerInfoScreen(parent, villagerEntityId));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openVillagerStatsPlaceholder failed", t);
        }
    }

    public static void openCombatSettings(Screen parent, int villagerEntityId) {
        try {
            if (villagerEntityId <= 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            ClientNetwork.sendToServer(new PacketCombatSettingsQuery(villagerEntityId, false));
            mc.setScreen(new CombatSettingsScreen(parent, villagerEntityId, false));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openCombatSettings failed", t);
        }
    }

    private static void onScreenInitPost(final ScreenEvent.Init.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            ClientConfig.bake();

            int left = (screen.width - 276) / 2;
            int top = (screen.height - 166) / 2;

            int baseX = left + 276 - 22;
            int baseY = top + 6;

            int x = baseX + ClientConfig.buttonOffsetX;
            int y = baseY + ClientConfig.buttonOffsetY;

            int w = 18, h = 18;

            // Ask server for recruited state (villager-only) so UI can decide visibility.
            trySendRecruitStateQueryIfNeeded(screen);

            // ask server for current villager modes (for highlight)
            trySendModeQueryIfNeeded(screen);
            trySendCombatModeQueryIfNeeded(screen);

            // --------------------------------
            // 1) REROLL (top)
            // --------------------------------
            Button reroll = Button.builder(Component.empty(), btn -> {
                        try {
                            int cid = resolveContainerId(screen);
                            if (cid >= 0 && ClientRerollCooldownCache.isCoolingDown(cid)) {
                                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client reroll click ignored: cooling down (containerId={})", cid);
                                return;
                            }

                            int optimisticTicks = 0;

                            try {
                                if (cid >= 0) {
                                    optimisticTicks = Math.max(0, ClientRerollCooldownCache.getLastKnownTotalCooldownTicks(cid));
                                }
                            } catch (Throwable ignored) {}

                            if (optimisticTicks <= 0) {
                                try {
                                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                                    if (cfg != null) optimisticTicks = Math.max(0, cfg.cooldownTicks);
                                } catch (Throwable ignored) {}
                            }

                            if (cid >= 0 && optimisticTicks > 0) {
                                ClientRerollCooldownCache.setOptimisticCooldown(cid, optimisticTicks);
                            }

                            ClientNetwork.sendToServer(new PacketRequestReroll());
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Client clicked reroll button; sent PacketRequestReroll");
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client send reroll packet failed", t);
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

            // --------------------------------
            // 2) INVENTORY, 3) COMMANDS, 4) INFO (stack below)
            // --------------------------------
            int stackBaseX = x;
            int stackBaseY = y + h + 2;

            int sx = stackBaseX + ClientConfig.statsButtonOffsetX;
            int sy = stackBaseY + ClientConfig.statsButtonOffsetY;

            // INVENTORY
            Button invBtn = Button.builder(Component.literal("⛨"), btn -> {
                        try {
                            int villagerEntityId = resolveTraderEntityId(screen);
                            openVillagerInventory(screen, villagerEntityId);
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] Inventory button click failed", t);
                        }

                        try {
                            btn.setFocused(false);
                            Screen scr = Minecraft.getInstance().screen;
                            if (scr != null && scr.getFocused() == btn) scr.setFocused(null);
                        } catch (Throwable ignored) {}
                    })
                    .pos(sx, sy).size(w, h)
                    .createNarration(s -> Component.literal("Inventory"))
                    .build();

            setSimpleTooltip(invBtn, "Villager Inventory");

            e.addListener(invBtn);
            INVENTORY_BUTTONS.put(screen, invBtn);

            // COMMANDS (toggle palette)
            Button cmdBtn = Button.builder(Component.literal("⚐"), btn -> {
                        try {

                            boolean controlsEnabled = isControlsUiEnabled(screen);
                            if (!controlsEnabled) {
                                collapseCommands(screen);
                                return;
                            }

                            boolean next = !isCommandsExpanded(screen);
                            setCommandsExpanded(screen, next);

                            // show/hide visuals
                            CommandsBackdropWidget backdrop = COMMANDS_BACKDROPS.get(screen);
                            if (backdrop != null) {
                                backdrop.visible = next;
                                backdrop.active = false;
                            }

                            List<Button> subs = COMMANDS_SUB_BUTTONS.get(screen);
                            setButtonsVisible(subs, next);

                            List<RowHeaderIconWidget> icons = COMMANDS_HEADER_ICONS.get(screen);
                            if (icons != null) {
                                for (RowHeaderIconWidget iw : icons) {
                                    if (iw == null) continue;
                                    iw.visible = next;
                                    iw.active = false;
                                }
                            }

                            updateCommandsMainButtonVisual(screen);

                            VillagerOverhaul.LOG().info("[VillagerOverhaul] Commands palette toggled expanded={} (villagerEntityId={})",
                                    next, resolveTraderEntityId(screen));

                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] Commands button click failed", t);
                        }

                        try {
                            btn.setFocused(false);
                            Screen scr = Minecraft.getInstance().screen;
                            if (scr != null && scr.getFocused() == btn) scr.setFocused(null);
                        } catch (Throwable ignored) {}
                    })
                    .pos(sx, sy + (h + 2)).size(w, h)
                    .createNarration(s -> Component.literal("Commands"))
                    .build();

            setSimpleTooltip(cmdBtn, "Commands");

            e.addListener(cmdBtn);
            COMMANDS_BUTTONS.put(screen, cmdBtn);

            // INFO (was your stats button)
            Button infoBtn = Button.builder(Component.literal("ⓘ"), btn -> {
                        try {
                            int villagerEntityId = resolveTraderEntityId(screen);
                            openVillagerStatsPlaceholder(screen, villagerEntityId);
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] Info button click failed", t);
                        }

                        try {
                            btn.setFocused(false);
                            Screen scr = Minecraft.getInstance().screen;
                            if (scr != null && scr.getFocused() == btn) scr.setFocused(null);
                        } catch (Throwable ignored) {}
                    })
                    .pos(sx, sy + 2 * (h + 2)).size(w, h)
                    .createNarration(s -> Component.literal("Info"))
                    .build();

            setSimpleTooltip(infoBtn, "Villager Info");

            e.addListener(infoBtn);
            STATS_BUTTONS.put(screen, infoBtn);

            // Cooldown overlay (covers reroll button area)
            CooldownOverlayWidget overlay = new CooldownOverlayWidget(x, y, w, h);
            overlay.active = false;
            overlay.visible = true;
            e.addListener(overlay);
            COOLDOWN_OVERLAYS.put(screen, overlay);

            // -------------------------------------------------
            // Commands palette (collapsed by default)
            // Two columns: Movement + Combat, vertically centered on cmdBtn
            // With header icons + shared dark backdrop + border.
            // -------------------------------------------------
            try {
                setCommandsExpanded(screen, false);
                updateCommandsMainButtonVisual(screen);

                int cmdX = cmdBtn.getX();
                int cmdY = cmdBtn.getY();
                int cmdCenterY = cmdY + (h / 2);

                final int gap = 2;
                final int headerGap = 2;

                // Backdrop style (match VillagerInfoScreen vibe)
                final int panelPad = 3;
                final int panelBorder = 1;

                // Backdrop origin is just to the right of the commands button,
                // then we place columns inside it with padding.
                int panelX = cmdX + w + gap;

                String[] movement = new String[] { "Neutral", "Idle", "Follow", "Patrol" };
                String[] combat   = new String[] { "Flee", "Defend", "Aggressive", "Settings" };

                int movementBlockH = movement.length * h + (movement.length - 1) * gap;
                int combatBlockH   = combat.length   * h + (combat.length   - 1) * gap;

                int movementStartY = cmdCenterY - (movementBlockH / 2);
                int combatStartY   = cmdCenterY - (combatBlockH / 2);

                int headerAY = movementStartY - (h + headerGap);
                int headerBY = combatStartY   - (h + headerGap);

                int topY = Math.min(headerAY, headerBY);
                int bottomY = Math.max(movementStartY + movementBlockH, combatStartY + combatBlockH);

                int contentW = (2 * w) + gap;          // two columns
                int panelW = (panelPad * 2) + contentW + (panelBorder * 2);
                int panelH = (panelPad * 2) + (bottomY - topY) + (panelBorder * 2);

                int panelY = topY - panelPad - panelBorder;

                // Content positions inside panel
                int contentX = panelX + panelBorder + panelPad;
                int colAX = contentX;
                int colBX = contentX + w + gap;

                // Backdrop widget (must be added before icons/buttons so it renders behind them)
                CommandsBackdropWidget backdrop = new CommandsBackdropWidget(panelX, panelY, panelW, panelH);
                backdrop.visible = false;
                backdrop.active = false;
                e.addListener(backdrop);
                COMMANDS_BACKDROPS.put(screen, backdrop);

                // Header icon widgets (non-buttons)
                List<RowHeaderIconWidget> headerIcons = new ArrayList<>(2);

                RowHeaderIconWidget movementIcon = new RowHeaderIconWidget(colAX, headerAY, w, h, new ItemStack(Items.LEATHER_BOOTS));
                movementIcon.visible = false;
                movementIcon.active = false;
                setSimpleTooltip(movementIcon, "Movement commands");
                e.addListener(movementIcon);
                headerIcons.add(movementIcon);

                RowHeaderIconWidget combatIcon = new RowHeaderIconWidget(colBX, headerBY, w, h, new ItemStack(Items.IRON_SWORD));
                combatIcon.visible = false;
                combatIcon.active = false;
                setSimpleTooltip(combatIcon, "Combat commands");
                e.addListener(combatIcon);
                headerIcons.add(combatIcon);

                COMMANDS_HEADER_ICONS.put(screen, headerIcons);

                // Sub buttons
                List<Button> subs = new ArrayList<>(movement.length + combat.length);

                // Movement column
                for (int i = 0; i < movement.length; i++) {
                    final String label = movement[i];
                    int by = movementStartY + i * (h + gap);

                    Button b = Button.builder(Component.literal(label.substring(0, 1)), bbtn -> {
                                try {
                                    int villagerEntityId = resolveTraderEntityId(screen);

                                    if ("Neutral".equalsIgnoreCase(label)) {
                                        ClientNetwork.sendToServer(new PacketVillagerCommand(
                                                villagerEntityId,
                                                PacketVillagerCommand.Command.NEUTRAL
                                        ));

                                        Minecraft mc = Minecraft.getInstance();
                                        if (mc != null && mc.player != null) {
                                            mc.player.displayClientMessage(
                                                    Component.literal("Command sent: Neutral").withStyle(ChatFormatting.YELLOW),
                                                    true
                                            );
                                        }

                                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Movement command: NEUTRAL (villagerEntityId={})", villagerEntityId);

                                    } else if ("Idle".equalsIgnoreCase(label)) {
                                        ClientNetwork.sendToServer(new PacketVillagerCommand(
                                                villagerEntityId,
                                                PacketVillagerCommand.Command.IDLE
                                        ));

                                        Minecraft mc = Minecraft.getInstance();
                                        if (mc != null && mc.player != null) {
                                            mc.player.displayClientMessage(
                                                    Component.literal("Command sent: Idle").withStyle(ChatFormatting.YELLOW),
                                                    true
                                            );
                                        }

                                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Movement command: IDLE (villagerEntityId={})", villagerEntityId);

                                    } else if ("Follow".equalsIgnoreCase(label)) {
                                        ClientNetwork.sendToServer(new PacketVillagerCommand(
                                                villagerEntityId,
                                                PacketVillagerCommand.Command.FOLLOW
                                        ));

                                        Minecraft mc = Minecraft.getInstance();
                                        if (mc != null && mc.player != null) {
                                            mc.player.displayClientMessage(
                                                    Component.literal("Command sent: Follow").withStyle(ChatFormatting.YELLOW),
                                                    true
                                            );
                                        }

                                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Movement command: FOLLOW (villagerEntityId={})", villagerEntityId);

                                    } else if ("Patrol".equalsIgnoreCase(label)) {
                                        Minecraft mc = Minecraft.getInstance();
                                        if (mc != null) {
                                            mc.setScreen(new PatrolBeginPromptScreen(screen, villagerEntityId));
                                        }

                                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Movement command: PATROL prompt opened (villagerEntityId={})", villagerEntityId);

                                    } else {
                                        VillagerOverhaul.LOG().info("[VillagerOverhaul] Movement command clicked: {} (villagerEntityId={})",
                                                label, villagerEntityId);
                                    }

                                } catch (Throwable t) {
                                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Movement command click failed: " + label, t);
                                }

                                // Requirement #4: any subcommand click collapses the palette
                                try {
                                    collapseCommands(screen);
                                } catch (Throwable ignored) {}

                                try {
                                    bbtn.setFocused(false);
                                    Screen scr = Minecraft.getInstance().screen;
                                    if (scr != null && scr.getFocused() == bbtn) scr.setFocused(null);
                                } catch (Throwable ignored) {}
                            })
                            .pos(colAX, by).size(w, h)
                            .createNarration(s -> Component.literal(label))
                            .build();

                    setSimpleTooltip(b, label);
                    b.visible = false;
                    b.active = false;

                    e.addListener(b);
                    subs.add(b);

                    // ============================================================
                    // store movement buttons for green highlight updates
                    // ============================================================
                    try {
                        MOVEMENT_BTNS
                                .computeIfAbsent(screen, s -> new java.util.HashMap<>())
                                .put(label.toLowerCase(java.util.Locale.ROOT), b);
                    } catch (Throwable ignored) {}
                }

                // Combat column
                for (int i = 0; i < combat.length; i++) {
                    final String label = combat[i];
                    int by = combatStartY + i * (h + gap);

                    boolean isSettings = "Settings".equalsIgnoreCase(label);
                    String glyph = isSettings ? "⛭" : label.substring(0, 1);

                    Button b = Button.builder(Component.literal(glyph), bbtn -> {
                                try {
                                    int villagerEntityId = resolveTraderEntityId(screen);
                                    if (isSettings) {
                                        openCombatSettings(screen, villagerEntityId);
                                        return;
                                    }
                                    if (villagerEntityId > 0) {
                                        String key = label.toLowerCase(java.util.Locale.ROOT);
                                        String current = COMBAT_MODE_ID.get(villagerEntityId);
                                        if (current == null) current = "off";

                                        boolean toggleOff = key.equals(current);
                                        PacketVillagerCombatCommand.Command cmd = toggleOff
                                                ? PacketVillagerCombatCommand.Command.OFF
                                                : switch (key) {
                                            case "flee" -> PacketVillagerCombatCommand.Command.FLEE;
                                            case "defend" -> PacketVillagerCombatCommand.Command.DEFEND;
                                            case "aggressive" -> PacketVillagerCombatCommand.Command.AGGRESSIVE;
                                            default -> PacketVillagerCombatCommand.Command.OFF;
                                        };
                                        ClientNetwork.sendToServer(new PacketVillagerCombatCommand(villagerEntityId, cmd));

                                        COMBAT_MODE_ID.put(villagerEntityId, toggleOff ? "off" : key);
                                        COMBAT_MODE_AT.put(villagerEntityId, System.currentTimeMillis());
                                        updateCombatButtonsVisual(screen);
                                    }

                                    VillagerOverhaul.LOG().info("[VillagerOverhaul] Combat command clicked: {} (villagerEntityId={})",
                                            label, villagerEntityId);
                                } catch (Throwable t) {
                                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Combat command click failed: " + label, t);
                                }

                                try {
                                    collapseCommands(screen);
                                } catch (Throwable ignored) {}

                                try {
                                    bbtn.setFocused(false);
                                    Screen scr = Minecraft.getInstance().screen;
                                    if (scr != null && scr.getFocused() == bbtn) scr.setFocused(null);
                                } catch (Throwable ignored) {}
                            })
                            .pos(colBX, by).size(w, h)
                            .createNarration(s -> Component.literal(label))
                            .build();

                    setSimpleTooltip(b, label);
                    b.visible = false;
                    b.active = false;

                    e.addListener(b);
                    subs.add(b);

                    // ============================================================
                    // store combat buttons for green highlight updates
                    // ============================================================
                    try {
                        if (!isSettings) {
                            COMBAT_BTNS
                                    .computeIfAbsent(screen, s -> new java.util.HashMap<>())
                                    .put(label.toLowerCase(java.util.Locale.ROOT), b);
                        }
                    } catch (Throwable ignored) {}
                }

                COMMANDS_SUB_BUTTONS.put(screen, subs);

            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed building commands palette", t);
            }

            // Queries (existing behavior)
            trySendTradeLocksQuery();
            trySendCooldownQuery();

            // Apply initial gating visibility (if villager + not recruited => hide/disable ALL 4 buttons)
            boolean controlsEnabled = isControlsUiEnabled(screen);
            setUiButtonsVisible(screen, controlsEnabled);

            // Ensure palette starts collapsed and visuals are correct
            collapseCommands(screen);

            // apply mode highlights once at init (will update again during render)
            try {
                updateMovementButtonsVisual(screen);
            } catch (Throwable ignored) {}
            try {
                updateCombatButtonsVisual(screen);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onScreenInitPost exception", t);
        }
    }

    private static void onScreenRenderPost(final ScreenEvent.Render.Post e) {
        try {
            if (!(e.getScreen() instanceof MerchantScreen screen)) return;

            // Keep trade-lock indicators as-is.
            renderTradeLockIndicators(e, screen);

            // Refresh recruit state occasionally (villager-only) and enforce visibility/active.
            trySendRecruitStateQueryIfNeeded(screen);

            boolean controlsEnabled = isControlsUiEnabled(screen);
            setUiButtonsVisible(screen, controlsEnabled);

            // ============================================================
            // keep movement/combat highlights in sync with server
            // ============================================================
            trySendModeQueryIfNeeded(screen);
            trySendCombatModeQueryIfNeeded(screen);
            updateMovementButtonsVisual(screen);
            updateCombatButtonsVisual(screen);

            boolean expanded = controlsEnabled && isCommandsExpanded(screen);

            // Sync palette visibility (subs + header icons + backdrop)
            try {
                List<Button> subs = COMMANDS_SUB_BUTTONS.get(screen);
                setButtonsVisible(subs, expanded);

                List<RowHeaderIconWidget> icons = COMMANDS_HEADER_ICONS.get(screen);
                if (icons != null) {
                    for (RowHeaderIconWidget iw : icons) {
                        if (iw == null) continue;
                        iw.visible = expanded;
                        iw.active = false;
                    }
                }

                CommandsBackdropWidget backdrop = COMMANDS_BACKDROPS.get(screen);
                if (backdrop != null) {
                    backdrop.visible = expanded;
                    backdrop.active = false;
                }
            } catch (Throwable ignored) {}

            // Update main Commands button color (green only while expanded)
            updateCommandsMainButtonVisual(screen);

            // If not recruited, do not draw reroll glyph, tooltip, or enable cooldown overlay.
            if (!controlsEnabled) {
                // Info stays usable; we only skip reroll tooltip/cooldown drawing when controls are gated.
                return;
            }

            Button btn = REROLL_BUTTONS.get(screen);
            CooldownOverlayWidget overlay = COOLDOWN_OVERLAYS.get(screen);
            if (btn == null) return;

            int cid = resolveContainerId(screen);
            boolean cooling = (cid >= 0) && ClientRerollCooldownCache.isCoolingDown(cid);

            if (btn.active == cooling) btn.active = !cooling;
            if (overlay != null) overlay.active = cooling;

            try {
                GuiGraphics gg = e.getGuiGraphics();
                Font font = Minecraft.getInstance().font;

                final String glyph = "↻";
                final float scale = 1.65f;
                final int color = btn.active ? 0xFFFFFFFF : 0xFF777777;

                int bx = btn.getX();
                int by = btn.getY();
                int bw = btn.getWidth();
                int bh = btn.getHeight();

                int textW = font.width(glyph);
                int textH = font.lineHeight;

                float cx = bx + (bw / 2.0f);
                float cy = by + (bh / 2.0f);

                gg.pose().pushPose();
                gg.pose().translate(cx, cy, 500.0f);
                gg.pose().scale(scale, scale, 1.0f);
                gg.drawString(font, glyph, -textW / 2.0f, -textH / 2.0f, color, true);
                gg.pose().popPose();
            } catch (Throwable ignored) {}

            boolean hoverOverlay = overlay != null && overlay.active && overlay.isMouseOver(e.getMouseX(), e.getMouseY());
            boolean hoverButton = btn.isMouseOver(e.getMouseX(), e.getMouseY());

            if (hoverOverlay || hoverButton) {
                if (ClientTooltipCache.ageMs() > TOOLTIP_REFRESH_DEBOUNCE_MS) {
                    trySendTooltipQuery(screen);
                }

                PacketTooltipData snap = ClientTooltipCache.get();

                if (snap == null) {
                    List<Component> syncing = new ArrayList<>();
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) syncing.add(Component.literal("Syncing… (cfg v" + cfg.version + ")"));
                    else syncing.add(Component.literal("Syncing…"));
                    e.getGuiGraphics().renderComponentTooltip(Minecraft.getInstance().font, syncing, e.getMouseX(), e.getMouseY());
                    return;
                }

                TooltipRenderPlan plan = buildPrettyTooltipPlan(snap, screen);
                if (plan == null || plan.lines.isEmpty()) {
                    VillagerOverhaul.LOG().warn("[VillagerOverhaul] Tooltip render plan produced no lines (snap={}, screen={})",
                            snap, screen.getClass().getName());
                    List<Component> fallback = List.of(Component.literal("Tooltip error (see log)"));
                    e.getGuiGraphics().renderComponentTooltip(Minecraft.getInstance().font, fallback, e.getMouseX(), e.getMouseY());
                    return;
                }

                renderPrettyTooltip(
                        e.getGuiGraphics(),
                        Minecraft.getInstance().font,
                        plan,
                        e.getMouseX(), e.getMouseY(),
                        screen.width, screen.height
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onScreenRenderPost exception", t);
        }
    }

    private static void onScreenClosed(final ScreenEvent.Closing e) {
        try {
            try {
                Screen s = e.getScreen();
                if (s instanceof VillagerQuickActionsScreen qa) {
                    sendUiPauseNow(qa.getVillagerEntityId(), false);
                } else if (s instanceof CombatSettingsScreen cs && !cs.isGlobal()) {
                    sendUiPauseNow(cs.getVillagerEntityId(), false);
                }
            } catch (Throwable ignored) {}

            REROLL_BUTTONS.remove(e.getScreen());
            COOLDOWN_OVERLAYS.remove(e.getScreen());
            STATS_BUTTONS.remove(e.getScreen());
            INVENTORY_BUTTONS.remove(e.getScreen());
            COMMANDS_BUTTONS.remove(e.getScreen());

            COMMANDS_SUB_BUTTONS.remove(e.getScreen());
            COMMANDS_EXPANDED.remove(e.getScreen());
            COMMANDS_BACKDROPS.remove(e.getScreen());
            COMMANDS_HEADER_ICONS.remove(e.getScreen());

            // movement/combat highlight buttons cache
            MOVEMENT_BTNS.remove(e.getScreen());
            COMBAT_BTNS.remove(e.getScreen());

            if (e.getScreen() instanceof MerchantScreen ms) {
                int cid = resolveContainerId(ms);
                if (cid >= 0) {
                    ClientTradeLockCache.clearContainer(cid);
                    ClientRerollCooldownCache.clearContainer(cid);
                } else {
                    ClientTradeLockCache.clearAll();
                    ClientRerollCooldownCache.clearAll();
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onScreenClosed exception", t);
        }
    }

    private static void renderTradeLockIndicators(ScreenEvent.Render.Post e, MerchantScreen screen) {
        try {
            if (!isControlsUiEnabled(screen)) return;

            int cid = resolveContainerId(screen);
            if (cid < 0) return;

            long mask = ClientTradeLockCache.getMaskForContainer(cid);
            if (mask == 0L) return;

            int offerCount = safeOfferCount(screen);
            if (offerCount <= 0) return;

            int scrollOff = readScrollOffset(screen, offerCount);

            List<AbstractWidget> tradeButtons = findTradeOfferButtons(screen);
            if (tradeButtons.isEmpty()) return;

            GuiGraphics gg = e.getGuiGraphics();
            final int outlineColor = 0xFF66FF66;

            for (AbstractWidget w : tradeButtons) {
                if (w == null || !w.visible) continue;

                int rowIdx = readTradeButtonRowIndex(w);
                if (rowIdx < 0 || rowIdx > 63) continue;

                int absoluteIdx = scrollOff + rowIdx;
                if (absoluteIdx < 0 || absoluteIdx >= offerCount) continue;
                if ((mask & (1L << absoluteIdx)) == 0L) continue;

                int x = w.getX();
                int y = w.getY();
                int ww = w.getWidth();
                int hh = w.getHeight();
                if (ww <= 0 || hh <= 0) continue;

                try {
                    gg.renderOutline(x, y, ww, hh, outlineColor);
                } catch (Throwable t) {
                    gg.fill(x, y, x + ww, y + 1, outlineColor);
                    gg.fill(x, y + hh - 1, x + ww, y + hh, outlineColor);
                    gg.fill(x, y, x + 1, y + hh, outlineColor);
                    gg.fill(x + ww - 1, y, x + ww, y + hh, outlineColor);
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] renderTradeLockIndicators exception", t);
        }
    }

    private static List<AbstractWidget> findTradeOfferButtons(MerchantScreen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        try {
            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof AbstractWidget w)) continue;
                String cn = w.getClass().getName();
                if (VillagerOverhaul_TRADE_BUTTON_CLASS.equals(cn)) out.add(w);
            }
            out.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] findTradeOfferButtons failed", t);
        }
        return out;
    }

    private static int readTradeButtonRowIndex(AbstractWidget w) {
        try {
            Field f = null;
            Class<?> c = w.getClass();

            try {
                f = c.getDeclaredField("index");
            } catch (NoSuchFieldException ignored) {}

            if (f == null) {
                for (Field candidate : c.getDeclaredFields()) {
                    if (candidate.getType() != int.class) continue;
                    String n = candidate.getName();
                    if (n != null && (n.equals("index") || n.toLowerCase().contains("index"))) {
                        f = candidate;
                        break;
                    }
                }
            }

            if (f == null) return -1;

            f.setAccessible(true);
            return f.getInt(w);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int readScrollOffset(MerchantScreen screen, int offerCount) {
        try {
            int maxScroll = Math.max(0, offerCount - 7);

            try {
                int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
                return clamp(raw, 0, maxScroll);
            } catch (Throwable ignored) {}

            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null || !n.toLowerCase().contains("scroll")) continue;

                        f.setAccessible(true);
                        int v = f.getInt(screen);
                        if (v >= 0 && v <= maxScroll) return v;
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int resolveContainerId(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            return menu.containerId;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int safeOfferCount(MerchantScreen screen) {
        try {
            if (!(screen.getMenu() instanceof MerchantMenu menu)) return -1;
            var offers = menu.getOffers();
            return offers == null ? 0 : offers.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void trySendTooltipQuery(MerchantScreen screen) {
        try {
            int traderId = resolveTraderEntityId(screen);
            ClientNetwork.sendToServer(new PacketTooltipQuery(traderId));

            // also request villager stats so we can compute Generosity-adjusted manual cost.
            tryRequestVillagerStatsSnapshot(traderId);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client send tooltip query failed", t);
        }
    }

    private static void tryRequestVillagerStatsSnapshot(int traderEntityId) {
        try {
            if (traderEntityId <= 0) return;

            long age = Long.MAX_VALUE;
            try { age = ClientVillagerStatsCache.ageMs(traderEntityId); } catch (Throwable ignored) {}

            // Debounce requests; refresh if missing/stale.
            if (age > VILLAGER_STATS_REFRESH_DEBOUNCE_MS) {
                ClientNetwork.sendToServer(new PacketVillagerStatsQuery(traderEntityId));
            }
        } catch (Throwable ignored) {}
    }

    private static void trySendTradeLocksQuery() {
        try {
            ClientNetwork.sendToServer(new PacketTradeLocksQuery());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client send trade locks query failed", t);
        }
    }

    private static void trySendCooldownQuery() {
        try {
            ClientNetwork.sendToServer(new PacketRerollCooldownQuery());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Client send cooldown query failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Pretty tooltip (colors + emerald icons)
    // ---------------------------------------------------------------------

    private static final class TooltipIcon {
        final int lineIndex;
        final ItemStack stack;

        TooltipIcon(int lineIndex, ItemStack stack) {
            this.lineIndex = lineIndex;
            this.stack = stack;
        }
    }

    private static final class TooltipRenderPlan {
        final List<Component> lines = new ArrayList<>();
        final List<TooltipIcon> icons = new ArrayList<>();
    }

    private static TooltipRenderPlan buildPrettyTooltipPlan(PacketTooltipData d, MerchantScreen screen) {
        TooltipRenderPlan plan = new TooltipRenderPlan();

        plan.lines.add(Component.translatable("ezvr.ui.reroll").withStyle(ChatFormatting.GREEN));

        int cid = resolveContainerId(screen);

        int remainingTicks = 0;
        boolean cooling = false;
        try {
            if (cid >= 0) {
                remainingTicks = Math.max(0, ClientRerollCooldownCache.getRemainingTicks(cid));
                cooling = remainingTicks > 0;
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Tooltip cooldown remaining read failed (soft): {}", t.toString());
        }

        Component cooldownLine;
        if (cooling) {
            double sec = remainingTicks / 20.0;
            cooldownLine = Component.empty()
                    .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.format(java.util.Locale.ROOT, "%.1fs", sec)));
        } else {
            int cfgTicks = 0;

            if (cid >= 0) cfgTicks = ClientRerollCooldownCache.getLastKnownTotalCooldownTicks(cid);

            if (cfgTicks <= 0) {
                try {
                    ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
                    if (cfg != null) cfgTicks = Math.max(0, cfg.cooldownTicks);
                } catch (Throwable ignored) {}
            }

            if (cfgTicks > 0) {
                double sec = cfgTicks / 20.0;
                cooldownLine = Component.empty()
                        .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal(String.format(java.util.Locale.ROOT, "%.1fs", sec)))
                        .append(Component.literal(" (" + cfgTicks + "t)").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                cooldownLine = Component.empty()
                        .append(Component.literal("Cooldown: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("?"));
            }
        }
        plan.lines.add(cooldownLine);

        if (d == null || d.cost == null) {
            plan.lines.add(Component.literal("Syncing…"));
            return plan;
        }

        // ------------------------------
        // COST: apply Generosity to manual reroll price in tooltip
        // ------------------------------
        int baseCost = Math.max(0, d.cost.scaledCost);

        int traderId = resolveTraderEntityId(screen);
        double generosityPct = computeGenerosityPctForTrader(traderId); // positive = discount, negative = increase
        int finalCost = applyDiscountOrIncreasePct(baseCost, generosityPct);

        if (baseCost <= 0) {
            plan.lines.add(Component.empty()
                    .append(Component.literal("Cost: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("Free").withStyle(ChatFormatting.GREEN)));
        } else if (finalCost == baseCost || Math.abs(generosityPct) < 0.0001) {
            int lineIdx = plan.lines.size();
            plan.lines.add(Component.empty()
                    .append(Component.literal("Cost: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.valueOf(baseCost)))
                    .append(Component.literal(" × ")));
            plan.icons.add(new TooltipIcon(lineIdx, new ItemStack(Items.EMERALD)));
        } else {
            int lineIdx = plan.lines.size();

            ChatFormatting adjColor = (finalCost < baseCost) ? ChatFormatting.GREEN : ChatFormatting.RED;

            Component adjustedPart;
            if (finalCost <= 0) {
                adjustedPart = Component.literal("Free").withStyle(ChatFormatting.GREEN);
            } else {
                adjustedPart = Component.literal(String.valueOf(finalCost)).withStyle(adjColor);
            }

            plan.lines.add(Component.empty()
                    .append(Component.literal("Cost: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.valueOf(baseCost)).withStyle(ChatFormatting.RED, ChatFormatting.STRIKETHROUGH))
                    .append(Component.literal(" "))
                    .append(adjustedPart)
                    .append(Component.literal(" × ")));

            plan.icons.add(new TooltipIcon(lineIdx, new ItemStack(Items.EMERALD)));

            // --- Generosity line: custom label color 0xFF42D16C, value stays green/red ---
            String sign = (generosityPct > 0.0) ? "-" : "+";
            double shown = Math.abs(generosityPct);
            ChatFormatting pctColor = (generosityPct > 0.0) ? ChatFormatting.GREEN : ChatFormatting.RED;

            plan.lines.add(Component.empty()
                    .append(Component.literal(" Generosity: ")
                            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x42D16C))))
                    .append(Component.literal(sign + trimPct(shown) + "%").withStyle(pctColor)));
        }

        // ------------------------------
        // Daily cap line(s)
        // ------------------------------
        plan.lines.add(buildDailyCapLine(d));
        Component resetLine = buildDailyResetLine(d);
        if (resetLine != null) plan.lines.add(resetLine);

        // ------------------------------
        // Breakdown (NOW: grey + light grey only, like "Resets in")
        // ------------------------------
        int totalOffers = safeIntField(d.cost, "totalOffers");
        int lockedOffers = safeIntField(d.cost, "lockedOffers");
        int deductedLocks = safeIntField(d.cost, "deductibleLockedOffers");
        int freeOffers = safeIntField(d.cost, "freeOffers");
        int paidOffers = safeIntField(d.cost, "paidOffers");
        int costPerOffer = safeIntField(d.cost, "costPerOffer");

        plan.lines.add(Component.empty()
                .append(Component.literal(" Offers: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(Math.max(0, totalOffers))).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("   "))
                .append(Component.literal("Locked: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(Math.max(0, lockedOffers))).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("   "))
                .append(Component.literal("Deducted: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(Math.max(0, deductedLocks))).withStyle(ChatFormatting.GRAY))
        );

        int lineIdxFreePaid = plan.lines.size();
        plan.lines.add(Component.empty()
                .append(Component.literal(" Free: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(Math.max(0, freeOffers))).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("   "))
                .append(Component.literal("Paid: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(Math.max(0, paidOffers))).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("   "))
                .append(Component.literal("x" + Math.max(0, costPerOffer)).withStyle(ChatFormatting.GRAY))
        );

        // keep emerald icon if it is meaningful
        if (paidOffers > 0 && costPerOffer > 0) {
            plan.icons.add(new TooltipIcon(lineIdxFreePaid, new ItemStack(Items.EMERALD)));
        }

        // Affordability (unchanged)
        if (d.afford != null && finalCost > 0) {
            boolean can = d.afford.canAfford;
            String src = d.afford.source == null ? "none" : d.afford.source;
            Component aff = Component.literal(can ? ("Affordable (" + src + ")") : ("Not affordable (" + src + ")"))
                    .withStyle(can ? ChatFormatting.GREEN : ChatFormatting.RED);
            plan.lines.add(aff);
        } else if (finalCost <= 0) {
            plan.lines.add(Component.literal("Affordable").withStyle(ChatFormatting.GREEN));
        }

        return plan;
    }

    private static Component buildDailyCapLine(PacketTooltipData d) {
        try {
            if (d == null) {
                return Component.empty()
                        .append(Component.literal("Daily rerolls left: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
            }

            if (d.cap == null || !d.cap.enabled || d.cap.cap <= 0) {
                return Component.empty()
                        .append(Component.literal("Daily cap: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("Disabled").withStyle(ChatFormatting.DARK_GRAY));
            }

            int cap = Math.max(0, d.cap.cap);

            // IMPORTANT: remaining may be -1 if server couldn't resolve villager
            int remainingRaw = d.cap.remaining;
            if (remainingRaw < 0) {
                return Component.empty()
                        .append(Component.literal("Daily rerolls left: ").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(String.valueOf(cap)).withStyle(ChatFormatting.DARK_GRAY));
            }

            int remaining = Math.max(0, remainingRaw);

            ChatFormatting color;
            if (remaining <= 0) color = ChatFormatting.RED;
            else if (remaining == 1) color = ChatFormatting.YELLOW;
            else color = ChatFormatting.GREEN;

            return Component.empty()
                    .append(Component.literal("Daily rerolls left: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(String.valueOf(remaining)).withStyle(color))
                    .append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(String.valueOf(cap)).withStyle(ChatFormatting.DARK_GRAY));

        } catch (Throwable t) {
            return Component.empty()
                    .append(Component.literal("Daily rerolls left: ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("?").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static Component buildDailyResetLine(PacketTooltipData d) {
        try {
            if (d == null || d.cap == null) return null;
            if (!d.cap.enabled || d.cap.cap <= 0) return null;

            int ticks = d.cap.ticksUntilReset;

            // If server didn't supply it for some reason
            if (ticks < 0) return null;

            // 20 ticks = 1 real second
            String mmss = formatTicksToMinSec(ticks);

            return Component.empty()
                    .append(Component.literal("Resets in: ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(mmss).withStyle(ChatFormatting.GRAY));

        } catch (Throwable t) {
            return null;
        }
    }

    private static String formatTicksToMinSec(int ticks) {
        try {
            int t = Math.max(0, ticks);
            int totalSec = t / 20;

            int min = totalSec / 60;
            int sec = totalSec % 60;

            return String.format(java.util.Locale.ROOT, "%d:%02d", min, sec);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static double computeGenerosityPctForTrader(int traderEntityId) {
        try {
            // Need both stats + synced config bounds
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg == null) return 0.0;

            PacketVillagerStatsData stats = null;
            try { stats = ClientVillagerStatsCache.get(traderEntityId); } catch (Throwable ignored) {}

            if (stats == null || !stats.ok()) return 0.0;

            int points = stats.generosity(); // expected -100..100
            return pointsToPct(points, cfg.generosityMinPct, cfg.generosityMaxPct);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static double pointsToPct(int points, double minPct, double maxPct) {
        if (Double.isNaN(minPct)) minPct = 0.0;
        if (Double.isNaN(maxPct)) maxPct = 0.0;

        int p = points;
        if (p < -100) p = -100;
        if (p > 100) p = 100;

        double t = (p + 100.0) / 200.0; // 0..1
        return minPct + (maxPct - minPct) * t;
    }

    /**
     * Positive pct => discount (cheaper).
     * Negative pct => increase (more expensive).
     */
    private static int applyDiscountOrIncreasePct(int baseCost, double pct) {
        try {
            int base = Math.max(0, baseCost);
            if (base <= 0) return 0;

            if (Double.isNaN(pct)) pct = 0.0;

            // scale = 1 - pct/100
            double scale = 1.0 - (pct / 100.0);

            // Prevent negative prices if pct > 100
            if (scale < 0.0) scale = 0.0;

            double raw = base * scale;

            // Use round to be stable; if you want "always charge at least 1 when base>0 and scale>0",
            // switch to Math.ceil(raw).
            long rounded = Math.round(raw);

            if (rounded < 0L) rounded = 0L;
            if (rounded > Integer.MAX_VALUE) rounded = Integer.MAX_VALUE;

            return (int) rounded;
        } catch (Throwable t) {
            return Math.max(0, baseCost);
        }
    }

    private static String trimPct(double v) {
        // Show clean percent: 50 or 12.5 (not 12.500000)
        try {
            if (Double.isNaN(v)) return "0";
            if (Math.abs(v - Math.rint(v)) < 0.0001) return String.valueOf((int) Math.rint(v));
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        } catch (Throwable t) {
            return "0";
        }
    }

    private static int safeIntField(Object obj, String fieldName) {
        try {
            if (obj == null || fieldName == null) return 0;
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number n) return n.intValue();
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void renderPrettyTooltip(GuiGraphics gg, Font font, TooltipRenderPlan plan, int mouseX, int mouseY, int screenW, int screenH) {
        try {
            if (gg == null || font == null || plan == null || plan.lines.isEmpty()) return;

            int lineHeight = Math.max(9, font.lineHeight);
            int totalTextWidth = 0;

            boolean[] hasIcon = new boolean[plan.lines.size()];
            for (TooltipIcon ic : plan.icons) {
                if (ic == null) continue;
                if (ic.lineIndex >= 0 && ic.lineIndex < hasIcon.length) hasIcon[ic.lineIndex] = true;
            }

            for (int i = 0; i < plan.lines.size(); i++) {
                Component c = plan.lines.get(i);
                int w = font.width(c);
                if (hasIcon[i]) w += TIP_ICON_GAP + TIP_ICON_SIZE;
                if (w > totalTextWidth) totalTextWidth = w;
            }

            int tooltipW = TIP_PAD_X * 2 + totalTextWidth;
            int tooltipH = TIP_PAD_Y * 2 + (plan.lines.size() * lineHeight) + ((plan.lines.size() - 1) * TIP_LINE_GAP);

            int x = mouseX + 12;
            int y = mouseY - 12;

            if (x + tooltipW > screenW) x = mouseX - 12 - tooltipW;
            if (x < 4) x = 4;

            if (y + tooltipH > screenH) y = screenH - tooltipH - 6;
            if (y < 4) y = 4;

            renderTooltipBackgroundCompat(gg, x, y, tooltipW, tooltipH, TIP_Z);

            gg.pose().pushPose();
            gg.pose().translate(0.0D, 0.0D, (double) (TIP_Z + 5));

            int textX = x + TIP_PAD_X;
            int textY = y + TIP_PAD_Y;

            for (int i = 0; i < plan.lines.size(); i++) {
                int yy = textY + i * (lineHeight + TIP_LINE_GAP);
                Component line = plan.lines.get(i);

                gg.drawString(font, line, textX, yy, COLOR_WHITE_OPAQUE, true);

                if (hasIcon[i]) {
                    int textW = font.width(line);
                    int iconX = textX + textW + Math.max(0, TIP_ICON_GAP - 3);
                    int iconY = yy + Math.max(0, (lineHeight - TIP_ICON_SIZE) / 2) - 4;

                    for (TooltipIcon ic : plan.icons) {
                        if (ic == null) continue;
                        if (ic.lineIndex != i) continue;

                        ItemStack stack = ic.stack;
                        if (stack == null || stack.isEmpty()) continue;

                        try {
                            gg.renderItem(stack, iconX, iconY);
                            gg.renderItemDecorations(font, stack, iconX, iconY);
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] renderPrettyTooltip icon draw failed (soft): {}", t.toString());
                        }

                        iconX += TIP_ICON_SIZE + 2;
                    }
                }
            }

            gg.pose().popPose();

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] renderPrettyTooltip failed", t);
        }
    }

    private static void renderTooltipBackgroundCompat(GuiGraphics gg, int x, int y, int w, int h, int z) {
        try {
            Class<?> util = Class.forName("net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil");
            Method[] methods = util.getDeclaredMethods();

            for (Method m : methods) {
                if (!m.getName().toLowerCase().contains("rendertooltipbackground")) continue;
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();

                if (p.length == 6 && p[0] == GuiGraphics.class
                        && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class && p[5] == int.class) {
                    m.invoke(null, gg, x, y, w, h, z);
                    return;
                }
                if (p.length == 5 && p[0] == GuiGraphics.class
                        && p[1] == int.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                    m.invoke(null, gg, x, y, w, h);
                    return;
                }
            }

            fallbackTooltipBox(gg, x, y, w, h);

        } catch (Throwable t) {
            fallbackTooltipBox(gg, x, y, w, h);
        }
    }

    private static void fallbackTooltipBox(GuiGraphics gg, int x, int y, int w, int h) {
        try {
            int bg = 0xF0100010;
            int border1 = 0x505000FF;
            int border2 = 0x5028007F;

            gg.fill(x, y, x + w, y + h, bg);

            gg.fill(x, y, x + w, y + 1, border1);
            gg.fill(x, y + h - 1, x + w, y + h, border1);
            gg.fill(x, y, x + 1, y + h, border1);
            gg.fill(x + w - 1, y, x + w, y + h, border1);

            gg.fill(x + 1, y + 1, x + w - 1, y + 2, border2);
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------
    // Trader entity id resolver (FIXED: prioritize actual trader, not player)
    // ---------------------------------------------------------------------

    public static int resolveTraderEntityId(MerchantScreen screen) {
        try {
            if (screen == null) return -1;

            // 1) Best: ask the MerchantMenu for its trader via our accessor (avoids "player" fields on screen)
            try {
                if (screen.getMenu() instanceof MerchantMenu menu) {
                    Object trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                    if (trader instanceof net.minecraft.world.entity.Entity ent) {
                        return ent.getId();
                    }
                }
            } catch (Throwable ignored) {}

            // 2) Fallback: reflection search, but do NOT accept LocalPlayer as the "trader"
            try {
                MerchantMenu menu = (screen.getMenu() instanceof MerchantMenu mm) ? mm : null;
                if (menu != null) {
                    Integer id = reflectFindEntityIdPreferNonPlayer(menu);
                    if (id != null) return id;
                }
            } catch (Throwable ignored) {}

            try {
                Integer id = reflectFindEntityIdPreferNonPlayer(screen);
                if (id != null) return id;
            } catch (Throwable ignored) {}

            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Integer reflectFindEntityIdPreferNonPlayer(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                java.lang.reflect.Field[] fields = c.getDeclaredFields();
                for (java.lang.reflect.Field f : fields) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (v == null) continue;

                        if (v instanceof net.minecraft.world.entity.Entity ent) {
                            if (ent instanceof net.minecraft.client.player.LocalPlayer) continue;
                            return ent.getId();
                        }

                        if (!(v instanceof Number) && !(v instanceof String) && !(v.getClass().isPrimitive())) {
                            Integer nested = reflectFindEntityIdPreferNonPlayerShallow(v);
                            if (nested != null) return nested;
                        }
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer reflectFindEntityIdPreferNonPlayerShallow(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v instanceof net.minecraft.world.entity.Entity ent) {
                        if (ent instanceof net.minecraft.client.player.LocalPlayer) continue;
                        return ent.getId();
                    }
                } catch (Throwable ignored) {}
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class CooldownOverlayWidget extends AbstractWidget {

        CooldownOverlayWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            // intentionally empty
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput out) {
            // no narration
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            try {
                if (!this.active) return false;
                if (!this.isMouseOver(mouseX, mouseY)) return false;

                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CooldownOverlayWidget consumed click (button={})", button);
                return true;
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] CooldownOverlayWidget.mouseClicked failed (soft): {}", t.toString());
                return false;
            }
        }
    }

    // =====================================================================
    //  helper methods (paste anywhere inside ClientUI class)
    // =====================================================================

    private static boolean isCommandsExpanded(Screen screen) {
        try {
            if (screen == null) return false;
            Boolean b = COMMANDS_EXPANDED.get(screen);
            return b != null && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setCommandsExpanded(Screen screen, boolean expanded) {
        try {
            if (screen == null) return;
            COMMANDS_EXPANDED.put(screen, expanded);
        } catch (Throwable ignored) {}
    }

    private static void setButtonsVisible(List<Button> buttons, boolean visible) {
        try {
            if (buttons == null) return;
            for (Button b : buttons) {
                if (b == null) continue;
                b.visible = visible;
                b.active = visible;
            }
        } catch (Throwable ignored) {}
    }

    private static void collapseCommands(Screen screen) {
        try {
            setCommandsExpanded(screen, false);

            List<Button> subs = COMMANDS_SUB_BUTTONS.get(screen);
            setButtonsVisible(subs, false);

            List<RowHeaderIconWidget> icons = COMMANDS_HEADER_ICONS.get(screen);
            if (icons != null) {
                for (RowHeaderIconWidget iw : icons) {
                    if (iw == null) continue;
                    iw.visible = false;
                    iw.active = false;
                }
            }

            CommandsBackdropWidget backdrop = COMMANDS_BACKDROPS.get(screen);
            if (backdrop != null) {
                backdrop.visible = false;
                backdrop.active = false;
            }

            updateCommandsMainButtonVisual(screen);

        } catch (Throwable ignored) {}
    }

    private static void updateCommandsMainButtonVisual(Screen screen) {
        try {
            Button cmd = COMMANDS_BUTTONS.get(screen);
            if (cmd == null) return;

            boolean expanded = isCommandsExpanded(screen);

            // #58e766
            int greenRgb = 0x58E766;

            Component msg;
            if (expanded) {
                msg = Component.literal("⚐").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(greenRgb)));
            } else {
                msg = Component.literal("⚐"); // default/white
            }

            // Only update if changed (avoid churn)
            try {
                Component cur = cmd.getMessage();
                if (cur != null && cur.getString().equals(msg.getString())) {
                    // Still update style when toggling (string same always), so don't early return.
                }
            } catch (Throwable ignored) {}

            cmd.setMessage(msg);

        } catch (Throwable ignored) {}
    }

    /**
     * Simple tooltip for normal buttons.
     * (Reroll tooltip remains custom-rendered and should NOT use this.)
     */
    private static void setSimpleTooltip(AbstractWidget w, String text) {
        try {
            if (w == null || text == null) return;

            // net.minecraft.client.gui.components.Tooltip#create(Component)
            Class<?> tooltipClz = Class.forName("net.minecraft.client.gui.components.Tooltip");
            Method create = null;

            for (Method m : tooltipClz.getDeclaredMethods()) {
                if (!"create".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Component.class) {
                    create = m;
                    break;
                }
            }
            if (create == null) return;

            Object tooltip = create.invoke(null, Component.literal(text));

            // AbstractWidget#setTooltip(Tooltip)
            Method setTooltip = null;
            Class<?> c = w.getClass();
            while (c != null && c != Object.class && setTooltip == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"setTooltip".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && "net.minecraft.client.gui.components.Tooltip".equals(p[0].getName())) {
                        setTooltip = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (setTooltip == null) return;

            setTooltip.setAccessible(true);
            setTooltip.invoke(w, tooltip);

        } catch (Throwable ignored) {}
    }

    private static void sendUiPauseKeepalive(int villagerEntityId) {
        try {
            if (villagerEntityId <= 0) return;
            long now = System.currentTimeMillis();
            Long at = UI_PAUSE_AT.get(villagerEntityId);
            if (at != null && (now - at) < UI_PAUSE_KEEPALIVE_MS) return;
            sendUiPauseNow(villagerEntityId, true);
        } catch (Throwable ignored) {}
    }

    private static void sendUiPauseNow(int villagerEntityId, boolean paused) {
        try {
            if (villagerEntityId <= 0) return;
            ClientNetwork.sendToServer(new PacketVillagerUiPause(villagerEntityId, paused));
            if (paused) UI_PAUSE_AT.put(villagerEntityId, System.currentTimeMillis());
            else UI_PAUSE_AT.remove(villagerEntityId);
        } catch (Throwable ignored) {}
    }

    // ===================================
    // SHARED WIDGETS
    // ===================================

    private static final class CommandsBackdropWidget extends AbstractWidget {

        // Match VillagerInfoScreen style
        private static final int PANEL_BG = 0xCC0B0B0B;
        private static final int PANEL_BORDER = 0xFF3A3A3A;

        CommandsBackdropWidget(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            try {
                if (!this.visible) return;

                int x = getX();
                int y = getY();
                int w = this.width;
                int h = this.height;

                gg.fill(x, y, x + w, y + h, PANEL_BG);

                gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
                gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
                gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
                gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);

            } catch (Throwable ignored) {}
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput out) {
            // no narration
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }
    }

    private static final class RowHeaderIconWidget extends AbstractWidget {

        private final ItemStack stack;

        RowHeaderIconWidget(int x, int y, int w, int h, ItemStack stack) {
            super(x, y, w, h, Component.empty());
            this.stack = stack == null ? ItemStack.EMPTY : stack;
        }

        @Override
        protected void renderWidget(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            try {
                if (!this.visible) return;
                if (stack.isEmpty()) return;

                int x = getX();
                int y = getY();

                // Keep it visually within the 18x18 button footprint:
                // renderItem is effectively 16x16, so we center it.
                int ix = x + Math.max(0, (this.width - 16) / 2);
                int iy = y + Math.max(0, (this.height - 16) / 2);

                gg.renderItem(stack, ix, iy);
                gg.renderItemDecorations(Minecraft.getInstance().font, stack, ix, iy);

            } catch (Throwable ignored) {}
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput out) {
            // no narration
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }
    }

    private static void trySendModeQueryIfNeeded(MerchantScreen screen) {
        try {
            if (screen == null) return;

            int id = resolveTraderEntityId(screen);
            if (id <= 0) return;

            Long at = MODE_AT.get(id);
            long age = (at == null) ? Long.MAX_VALUE : (System.currentTimeMillis() - at);
            if (age < MODE_STALE_MS) return;

            ClientNetwork.sendToServer(new PacketVillagerModeQuery(id));
        } catch (Throwable ignored) {}
    }

    private static void trySendCombatModeQueryIfNeeded(MerchantScreen screen) {
        try {
            if (screen == null) return;

            int id = resolveTraderEntityId(screen);
            if (id <= 0) return;

            Long at = COMBAT_MODE_AT.get(id);
            long age = (at == null) ? Long.MAX_VALUE : (System.currentTimeMillis() - at);
            if (age < MODE_STALE_MS) return;

            ClientNetwork.sendToServer(new PacketVillagerCombatModeQuery(id));
        } catch (Throwable ignored) {}
    }

    private static void updateMovementButtonsVisual(MerchantScreen screen) {
        try {
            if (screen == null) return;

            int villId = resolveTraderEntityId(screen);
            if (villId <= 0) return;

            String mode = MODE_ID.get(villId);
            if (mode == null) mode = "neutral";

            // treat patrol_setup as patrol for highlight
            String activeKey = switch (mode) {
                case "idle" -> "idle";
                case "follow" -> "follow";
                case "patrol", "patrol_setup" -> "patrol";
                default -> "neutral";
            };

            Map<String, Button> m = MOVEMENT_BTNS.get(screen);
            if (m == null || m.isEmpty()) return;

            int greenRgb = 0x58E766;

            for (Map.Entry<String, Button> ent : m.entrySet()) {
                String key = ent.getKey();
                Button b = ent.getValue();
                if (b == null) continue;

                String glyph;
                try {
                    glyph = (b.getMessage() == null) ? "" : b.getMessage().getString();
                } catch (Throwable ignored) {
                    glyph = "";
                }

                if (key != null && key.equals(activeKey)) {
                    b.setMessage(Component.literal(glyph).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(greenRgb))));
                } else {
                    b.setMessage(Component.literal(glyph)); // default
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void updateCombatButtonsVisual(MerchantScreen screen) {
        try {
            if (screen == null) return;

            int villId = resolveTraderEntityId(screen);
            if (villId <= 0) return;

            String mode = COMBAT_MODE_ID.get(villId);
            if (mode == null) mode = "off";

            String activeKey = switch (mode) {
                case "flee" -> "flee";
                case "defend" -> "defend";
                case "aggressive" -> "aggressive";
                default -> "off";
            };

            Map<String, Button> m = COMBAT_BTNS.get(screen);
            if (m == null || m.isEmpty()) return;

            int greenRgb = 0x58E766;

            for (Map.Entry<String, Button> ent : m.entrySet()) {
                String key = ent.getKey();
                Button b = ent.getValue();
                if (b == null) continue;

                String glyph;
                try {
                    glyph = (b.getMessage() == null) ? "" : b.getMessage().getString();
                } catch (Throwable ignored) {
                    glyph = "";
                }

                boolean active = key != null && key.equals(activeKey) && !"off".equals(activeKey);
                if (active) {
                    b.setMessage(Component.literal(glyph).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(greenRgb))));
                } else {
                    b.setMessage(Component.literal(glyph)); // default
                }
            }
        } catch (Throwable ignored) {}
    }

    // ====================
    // FEATURE GATING
    // ====================

    // accept gate data from server
    public static void acceptRecruitGateData(PacketRecruitGateData p) {
        try {
            if (p == null) return;
            int id = p.villagerEntityId();
            if (id <= 0) return;

            if (!p.ok()) {
                return;
            }

            RECRUIT_STATE.put(id, new RecruitStateSnap(p.recruited(), p.canUseControls(), System.currentTimeMillis()));
        } catch (Throwable ignored) {}
    }

    private static void onClientTickPost(final net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            try {
                if (ClientKeybinds.consumeOpenGlobalCombatSettings()) {
                    if (mc.screen == null) {
                        openGlobalCombatSettings();
                    }
                }
            } catch (Throwable ignored) {}

            try {
                Screen s = mc.screen;
                if (s instanceof VillagerQuickActionsScreen qa) {
                    sendUiPauseKeepalive(qa.getVillagerEntityId());
                } else if (s instanceof CombatSettingsScreen cs && !cs.isGlobal()) {
                    sendUiPauseKeepalive(cs.getVillagerEntityId());
                }
            } catch (Throwable ignored) {}

            if (PENDING_QUICK_VILLAGER_ID <= 0) return;

            long age = System.currentTimeMillis() - PENDING_QUICK_AT_MS;
            if (age < QUICK_OPEN_DELAY_MS) return;

            // If a MerchantScreen opened, don't open quick actions.
            if (mc.screen instanceof MerchantScreen) {
                PENDING_QUICK_VILLAGER_ID = -1;
                return;
            }

            // If any other screen opened, also abort.
            if (mc.screen != null) {
                PENDING_QUICK_VILLAGER_ID = -1;
                return;
            }

            if (age > QUICK_OPEN_TIMEOUT_MS) {
                PENDING_QUICK_VILLAGER_ID = -1;
                return;
            }

            int id = PENDING_QUICK_VILLAGER_ID;
            PENDING_QUICK_VILLAGER_ID = -1;

            mc.setScreen(new VillagerQuickActionsScreen(id));

        } catch (Throwable ignored) {}
    }

    public static void openGlobalCombatSettings() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            ClientNetwork.sendToServer(new PacketCombatSettingsQuery(0, true));
            mc.setScreen(new CombatSettingsScreen(null, 0, true));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openGlobalCombatSettings failed", t);
        }
    }

    public static boolean canUseControlsForVillager(int villagerEntityId) {
        try {
            RecruitStateSnap snap = RECRUIT_STATE.get(villagerEntityId);
            return snap != null && snap.recruited && snap.canUseControls;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openVillagerInfoFromAnyParent(Screen parent, int villagerEntityId) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Try to construct VillagerInfoScreen with flexible constructors to avoid signature issues.
            Class<?> clz = Class.forName("org.z2six.villageroverhaul.client.VillagerInfoScreen");

            // (Screen,int)
            try {
                var c = clz.getConstructor(Screen.class, int.class);
                Object inst = c.newInstance(parent, villagerEntityId);
                if (inst instanceof Screen sc) {
                    mc.setScreen(sc);
                    return;
                }
            } catch (Throwable ignored) {}

            // (MerchantScreen,int) if parent is MerchantScreen
            try {
                if (parent instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen ms) {
                    var c = clz.getConstructor(net.minecraft.client.gui.screens.inventory.MerchantScreen.class, int.class);
                    Object inst = c.newInstance(ms, villagerEntityId);
                    if (inst instanceof Screen sc) {
                        mc.setScreen(sc);
                        return;
                    }
                }
            } catch (Throwable ignored) {}

            // (int) fallback
            try {
                var c = clz.getConstructor(int.class);
                Object inst = c.newInstance(villagerEntityId);
                if (inst instanceof Screen sc) {
                    mc.setScreen(sc);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] openVillagerInfoFromAnyParent failed", t);
        }
    }

    /**
     * For non-villagers: always enabled (unchanged behavior).
     * For villagers: controls enabled only if recruited AND this client canUseControls (owner).
     */
    private static boolean isControlsUiEnabled(MerchantScreen screen) {
        try {
            if (!isVillagerTrader(screen)) return true; // non-villager traders unchanged

            int id = resolveTraderEntityId(screen);
            if (id <= 0) return false;

            RecruitStateSnap snap = RECRUIT_STATE.get(id);
            return snap != null && snap.recruited && snap.canUseControls;
        } catch (Throwable t) {
            return false;
        }
    }

    private ClientUI() {}
}
