// neoforge\src\main\java\org\z2six\villageroverhaul\client\VillagerQuickActionsScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatModeQuery;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerModeQuery;
import org.z2six.villageroverhaul.network.recruit.PacketRecruitGateQuery;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class VillagerQuickActionsScreen extends Screen {

    private final int villagerEntityId;

    private Button rerollBtn;
    private Button invBtn;
    private Button cmdBtn;
    private Button infoBtn;

    private boolean commandsExpanded = false;

    // Movement buttons
    private Button mvNeutral, mvIdle, mvFollow, mvPatrol;

    // Combat buttons (placeholders like ClientUI unless you later wire real packets)
    private Button cbFlee, cbDefend, cbAggressive, cbSettings;

    // Backdrop + header icons (like ClientUI)
    private CommandsBackdropWidget commandsBackdrop;
    private RowHeaderIconWidget movementHeaderIcon;
    private RowHeaderIconWidget combatHeaderIcon;

    // Track command buttons by key for green highlight
    private final Map<String, Button> MOVEMENT_BTNS = new HashMap<>();
    private final Map<String, Button> COMBAT_BTNS = new HashMap<>();

    // Mode query debounce (like ClientUI)
    private static final long MODE_STALE_MS = 1000L;
    private long lastModeQueryMs = 0L;
    private long lastCombatModeQueryMs = 0L;

    // Visual green used in ClientUI
    private static final int GREEN_RGB = 0x58E766;

    protected VillagerQuickActionsScreen(int villagerEntityId) {
        super(Component.empty());
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    protected void init() {
        // Ask server gate state right away
        try {
            ClientNetwork.sendToServer(new PacketRecruitGateQuery(villagerEntityId));
        } catch (Throwable ignored) {}

        // Ask server modes right away (for highlight)
        trySendModeQueryIfNeeded(true);
        trySendCombatModeQueryIfNeeded(true);

        final int w = 18, h = 18;
        final int gap = 2;

        int centerX = this.width / 2;
        int y = (this.height / 2) - 10;

        // Horizontal main row: [Reroll][Inv][Cmd][Info]
        int totalW = (w * 4) + (gap * 3);
        int x0 = centerX - (totalW / 2);

        rerollBtn = Button.builder(Component.literal("↻"), b -> onReroll())
                .pos(x0, y).size(w, h).build();
        invBtn = Button.builder(Component.literal("⛨"), b -> onInventory())
                .pos(x0 + (w + gap), y).size(w, h).build();
        cmdBtn = Button.builder(Component.literal("⚐"), b -> onToggleCommands())
                .pos(x0 + 2 * (w + gap), y).size(w, h).build();
        infoBtn = Button.builder(Component.literal("ⓘ"), b -> onInfo())
                .pos(x0 + 3 * (w + gap), y).size(w, h).build();

        rerollBtn.setTooltip(Tooltip.create(Component.literal("Reroll (requires trading screen)")));
        invBtn.setTooltip(Tooltip.create(Component.literal("Villager Inventory")));
        cmdBtn.setTooltip(Tooltip.create(Component.literal("Commands")));
        infoBtn.setTooltip(Tooltip.create(Component.literal("Villager Info")));

        addRenderableWidget(rerollBtn);
        addRenderableWidget(invBtn);
        addRenderableWidget(cmdBtn);
        addRenderableWidget(infoBtn);

        // ------------------------------------------------------------------
        // Commands palette: SAME VIBE AS ClientUI (backdrop + border + icons),
        // but now TWO HORIZONTAL ROWS:
        // Row 1: [Boots icon] [N][I][F][P]
        // Row 2: [Sword icon] [F][D][A]
        // ------------------------------------------------------------------
        try {
            // Below main row
            int panelTopAnchor = y + h + 6;

            final int panelPad = 3;
            final int panelBorder = 1;

            // Row widths
            int movementButtonsW = (4 * w) + (3 * gap);
            int combatButtonsW   = (4 * w) + (3 * gap);

            // Each row has a header icon + gap + buttons
            int movementRowW = w + gap + movementButtonsW; // icon + gap + buttons
            int combatRowW   = w + gap + combatButtonsW;

            int contentW = Math.max(movementRowW, combatRowW);
            int contentH = (2 * h) + gap; // two rows + gap between

            int panelW = (panelPad * 2) + contentW + (panelBorder * 2);
            int panelH = (panelPad * 2) + contentH + (panelBorder * 2);

            int panelX = centerX - (panelW / 2);
            int panelY = panelTopAnchor;

            int contentX = panelX + panelBorder + panelPad;
            int contentY = panelY + panelBorder + panelPad;

            int row1Y = contentY;
            int row2Y = contentY + h + gap;

            // Center each row content inside the panel content area
            int row1X = contentX + (contentW - movementRowW) / 2;
            int row2X = contentX + (contentW - combatRowW) / 2;

            // Backdrop behind everything
            commandsBackdrop = new CommandsBackdropWidget(panelX, panelY, panelW, panelH);
            commandsBackdrop.visible = false;
            commandsBackdrop.active = false;
            addRenderableWidget(commandsBackdrop);

            // Row header icons (textures)
            movementHeaderIcon = new RowHeaderIconWidget(row1X, row1Y, w, h, new ItemStack(Items.LEATHER_BOOTS));
            combatHeaderIcon   = new RowHeaderIconWidget(row2X, row2Y, w, h, new ItemStack(Items.IRON_SWORD));

            movementHeaderIcon.visible = false;
            movementHeaderIcon.active = false;
            movementHeaderIcon.setTooltip(Tooltip.create(Component.literal("Movement commands")));

            combatHeaderIcon.visible = false;
            combatHeaderIcon.active = false;
            combatHeaderIcon.setTooltip(Tooltip.create(Component.literal("Combat commands")));

            addRenderableWidget(movementHeaderIcon);
            addRenderableWidget(combatHeaderIcon);

            // Row 1 (Movement): icon then buttons
            int mvX0 = row1X + w + gap;

            mvNeutral = Button.builder(Component.literal("N"), b -> sendMovementCmd("neutral", PacketVillagerCommand.Command.NEUTRAL))
                    .pos(mvX0 + 0 * (w + gap), row1Y).size(w, h).build();
            mvIdle = Button.builder(Component.literal("I"), b -> sendMovementCmd("idle", PacketVillagerCommand.Command.IDLE))
                    .pos(mvX0 + 1 * (w + gap), row1Y).size(w, h).build();
            mvFollow = Button.builder(Component.literal("F"), b -> sendMovementCmd("follow", PacketVillagerCommand.Command.FOLLOW))
                    .pos(mvX0 + 2 * (w + gap), row1Y).size(w, h).build();
            mvPatrol = Button.builder(Component.literal("P"), b -> openPatrolPrompt())
                    .pos(mvX0 + 3 * (w + gap), row1Y).size(w, h).build();

            mvNeutral.setTooltip(Tooltip.create(Component.literal("Neutral")));
            mvIdle.setTooltip(Tooltip.create(Component.literal("Idle")));
            mvFollow.setTooltip(Tooltip.create(Component.literal("Follow")));
            mvPatrol.setTooltip(Tooltip.create(Component.literal("Patrol")));

            addRenderableWidget(mvNeutral);
            addRenderableWidget(mvIdle);
            addRenderableWidget(mvFollow);
            addRenderableWidget(mvPatrol);

            MOVEMENT_BTNS.put("neutral", mvNeutral);
            MOVEMENT_BTNS.put("idle", mvIdle);
            MOVEMENT_BTNS.put("follow", mvFollow);
            MOVEMENT_BTNS.put("patrol", mvPatrol);

            // Row 2 (Combat): icon then buttons (placeholders like ClientUI unless wired later)
            int cbX0 = row2X + w + gap;

            cbFlee = Button.builder(Component.literal("F"), b -> onCombatCommand("flee", PacketVillagerCombatCommand.Command.FLEE))
                    .pos(cbX0 + 0 * (w + gap), row2Y).size(w, h).build();
            cbDefend = Button.builder(Component.literal("D"), b -> onCombatCommand("defend", PacketVillagerCombatCommand.Command.DEFEND))
                    .pos(cbX0 + 1 * (w + gap), row2Y).size(w, h).build();
            cbAggressive = Button.builder(Component.literal("A"), b -> onCombatCommand("aggressive", PacketVillagerCombatCommand.Command.AGGRESSIVE))
                    .pos(cbX0 + 2 * (w + gap), row2Y).size(w, h).build();
            cbSettings = Button.builder(Component.literal("⛭"), b -> onCombatSettings())
                    .pos(cbX0 + 3 * (w + gap), row2Y).size(w, h).build();

            cbFlee.setTooltip(Tooltip.create(Component.literal("Flee")));
            cbDefend.setTooltip(Tooltip.create(Component.literal("Defend")));
            cbAggressive.setTooltip(Tooltip.create(Component.literal("Aggressive")));
            cbSettings.setTooltip(Tooltip.create(Component.literal("Combat settings")));

            addRenderableWidget(cbFlee);
            addRenderableWidget(cbDefend);
            addRenderableWidget(cbAggressive);
            addRenderableWidget(cbSettings);

            COMBAT_BTNS.put("flee", cbFlee);
            COMBAT_BTNS.put("defend", cbDefend);
            COMBAT_BTNS.put("aggressive", cbAggressive);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions failed building commands palette", t);
        }

        // Start collapsed
        setCommandsVisible(false);
        updateCommandsMainButtonVisual();

        // Apply current gate (if already cached)
        applyGateToWidgets();

        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Opened VillagerQuickActionsScreen (villagerEntityId={})", villagerEntityId);
    }

    private void applyGateToWidgets() {
        try {
            boolean controls = ClientUI.canUseControlsForVillager(villagerEntityId);

            // Info always allowed
            infoBtn.active = true;
            infoBtn.visible = true;

            // Controls gated
            rerollBtn.active = false; // disabled here by design (no MerchantScreen)
            rerollBtn.visible = controls;
            invBtn.active = controls;
            invBtn.visible = controls;
            cmdBtn.active = controls;
            cmdBtn.visible = controls;

            if (!controls) {
                commandsExpanded = false;
                setCommandsVisible(false);
                updateCommandsMainButtonVisual();
            }
        } catch (Throwable ignored) {}
    }

    private void onReroll() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Reroll requires the trading screen.").withStyle(ChatFormatting.YELLOW),
                        true
                );
            }
        } catch (Throwable ignored) {}
    }

    private void onInventory() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            ClientNetwork.sendToServer(new org.z2six.villageroverhaul.network.PacketOpenVillagerInventory(villagerEntityId));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions inventory failed", t);
        }
    }

    private void onToggleCommands() {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) {
                commandsExpanded = false;
                setCommandsVisible(false);
                updateCommandsMainButtonVisual();
                return;
            }

            commandsExpanded = !commandsExpanded;
            setCommandsVisible(commandsExpanded);
            updateCommandsMainButtonVisual();

            // Sync highlight immediately when opening
            if (commandsExpanded) updateCommandButtonsHighlight();

        } catch (Throwable ignored) {}
    }

    private void setCommandsVisible(boolean v) {
        try {
            setWidgetVisible(commandsBackdrop, v);
            setWidgetVisible(movementHeaderIcon, v);
            setWidgetVisible(combatHeaderIcon, v);

            setWidgetVisible(mvNeutral, v);
            setWidgetVisible(mvIdle, v);
            setWidgetVisible(mvFollow, v);
            setWidgetVisible(mvPatrol, v);

            setWidgetVisible(cbFlee, v);
            setWidgetVisible(cbDefend, v);
            setWidgetVisible(cbAggressive, v);
            setWidgetVisible(cbSettings, v);

            // When collapsing, also clear highlights back to default
            if (!v) {
                resetAllCommandButtonStyles();
            }
        } catch (Throwable ignored) {}
    }

    private void setWidgetVisible(AbstractWidget w, boolean v) {
        if (w == null) return;
        w.visible = v;
        // Backdrop/icon widgets are non-interactive; keep them inactive.
        if (w instanceof Button) w.active = v;
        else w.active = false;
    }

    private void updateCommandsMainButtonVisual() {
        try {
            if (cmdBtn == null) return;

            Component msg;
            if (commandsExpanded) {
                msg = Component.literal("⚐").setStyle(Style.EMPTY.withColor(TextColor.fromRgb(GREEN_RGB)));
            } else {
                msg = Component.literal("⚐");
            }
            cmdBtn.setMessage(msg);
        } catch (Throwable ignored) {}
    }

    private void sendMovementCmd(String key, PacketVillagerCommand.Command cmd) {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;

            ClientNetwork.sendToServer(new PacketVillagerCommand(villagerEntityId, cmd));

            // Collapse after click (like ClientUI requirement)
            commandsExpanded = false;
            setCommandsVisible(false);
            updateCommandsMainButtonVisual();

            // Optimistically highlight the chosen mode until server update arrives
            if (key != null) applyHighlightKey(MOVEMENT_BTNS, key);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions sendMovementCmd failed", t);
        }
    }

    private void onCombatCommand(String key, PacketVillagerCombatCommand.Command cmd) {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;

            ClientNetwork.sendToServer(new PacketVillagerCombatCommand(villagerEntityId, cmd));

            // Collapse after click (like ClientUI)
            commandsExpanded = false;
            setCommandsVisible(false);
            updateCommandsMainButtonVisual();

            // Optimistically highlight the chosen mode until server update arrives
            if (key != null) applyHighlightKey(COMBAT_BTNS, key);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions combat command failed", t);
        }
    }

    private void onCombatSettings() {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            ClientNetwork.sendToServer(new org.z2six.villageroverhaul.network.modes.PacketCombatSettingsQuery(villagerEntityId, false));
            mc.setScreen(new CombatSettingsScreen(this, villagerEntityId, false));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions combat settings failed", t);
        }
    }

    private void openPatrolPrompt() {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Same tolerant construction approach you had, but keep it simple & safe:
            // If PatrolBeginPromptScreen has (Screen,int) we use it, otherwise (int).
            try {
                Class<?> clz = Class.forName("org.z2six.villageroverhaul.client.PatrolBeginPromptScreen");

                try {
                    var c = clz.getConstructor(Screen.class, int.class);
                    Object inst = c.newInstance(this, villagerEntityId);
                    if (inst instanceof Screen sc) {
                        mc.setScreen(sc);
                        return;
                    }
                } catch (Throwable ignored) {}

                try {
                    var c = clz.getConstructor(int.class);
                    Object inst = c.newInstance(villagerEntityId);
                    if (inst instanceof Screen sc) {
                        mc.setScreen(sc);
                        return;
                    }
                } catch (Throwable ignored) {}

            } catch (Throwable ignored) {}

            // If prompt cannot open, just close commands
            commandsExpanded = false;
            setCommandsVisible(false);
            updateCommandsMainButtonVisual();

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions patrol prompt failed", t);
        }
    }

    private void onInfo() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // FIX: VillagerInfoScreen currently only has (MerchantScreen,int).
            // Passing null parent is fine; it will return to null on close.
            mc.setScreen(new VillagerInfoScreen((net.minecraft.client.gui.screens.inventory.MerchantScreen) null, villagerEntityId));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions info failed", t);
        }
    }

    @Override
    public void tick() {
        super.tick();

        // Keep gate responsive
        applyGateToWidgets();

        // Keep mode highlight in sync
        trySendModeQueryIfNeeded(false);
        trySendCombatModeQueryIfNeeded(false);

        // Only bother styling if palette visible
        if (commandsExpanded) {
            updateCommandButtonsHighlight();
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Minimal overlay feel
        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Mode query + highlight (mirrors ClientUI logic, but via reflection because
    // ClientUI caches are private).
    // -------------------------------------------------------------------------

    private void trySendModeQueryIfNeeded(boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!force && (now - lastModeQueryMs) < MODE_STALE_MS) return;
            lastModeQueryMs = now;

            ClientNetwork.sendToServer(new PacketVillagerModeQuery(villagerEntityId));
        } catch (Throwable ignored) {}
    }

    private void trySendCombatModeQueryIfNeeded(boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!force && (now - lastCombatModeQueryMs) < MODE_STALE_MS) return;
            lastCombatModeQueryMs = now;

            ClientNetwork.sendToServer(new PacketVillagerCombatModeQuery(villagerEntityId));
        } catch (Throwable ignored) {}
    }

    private void updateCommandButtonsHighlight() {
        try {
            String movementMode = readModeIdFromClientUI(villagerEntityId);
            if (movementMode == null) movementMode = "neutral";

            // match ClientUI: patrol_setup -> patrol
            String movementKey = switch (movementMode) {
                case "idle" -> "idle";
                case "follow" -> "follow";
                case "patrol", "patrol_setup" -> "patrol";
                default -> "neutral";
            };

            String combatMode = readCombatModeIdFromClientUI(villagerEntityId);
            if (combatMode == null) combatMode = "off";

            String combatKey = switch (combatMode) {
                case "flee" -> "flee";
                case "defend" -> "defend";
                case "aggressive" -> "aggressive";
                default -> "off";
            };

            applyHighlightKey(MOVEMENT_BTNS, movementKey);
            applyHighlightKey(COMBAT_BTNS, combatKey);
        } catch (Throwable ignored) {}
    }

    private void applyHighlightKey(Map<String, Button> buttons, String activeKey) {
        try {
            if (buttons == null) return;
            if (activeKey == null) activeKey = "neutral";
            activeKey = activeKey.toLowerCase(Locale.ROOT);

            for (Map.Entry<String, Button> e : buttons.entrySet()) {
                String k = (e.getKey() == null) ? "" : e.getKey();
                Button b = e.getValue();
                if (b == null) continue;

                String glyph = "";
                try { glyph = b.getMessage() == null ? "" : b.getMessage().getString(); } catch (Throwable ignored) {}

                boolean active = k.equals(activeKey) && !"off".equals(activeKey);
                if (active) {
                    b.setMessage(Component.literal(glyph).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(GREEN_RGB))));
                } else {
                    b.setMessage(Component.literal(glyph));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void resetAllCommandButtonStyles() {
        try {
            for (Button b : MOVEMENT_BTNS.values()) {
                if (b == null) continue;
                String glyph = "";
                try { glyph = b.getMessage() == null ? "" : b.getMessage().getString(); } catch (Throwable ignored) {}
                b.setMessage(Component.literal(glyph));
            }
            for (Button b : COMBAT_BTNS.values()) {
                if (b == null) continue;
                String glyph = "";
                try { glyph = b.getMessage() == null ? "" : b.getMessage().getString(); } catch (Throwable ignored) {}
                b.setMessage(Component.literal(glyph));
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static String readModeIdFromClientUI(int villagerId) {
        try {
            // ClientUI has:
            // private static final Map<Integer, String> MODE_ID = new WeakHashMap<>();
            Class<?> clz = Class.forName("org.z2six.villageroverhaul.client.ClientUI");

            Field f = null;
            try {
                f = clz.getDeclaredField("MODE_ID");
            } catch (NoSuchFieldException ignored) {}

            if (f == null) return null;
            f.setAccessible(true);

            Object mapObj = f.get(null);
            if (!(mapObj instanceof Map<?, ?> m)) return null;

            Object v = m.get(villagerId);
            return (v instanceof String s) ? s : null;

        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String readCombatModeIdFromClientUI(int villagerId) {
        try {
            // ClientUI has:
            // private static final Map<Integer, String> COMBAT_MODE_ID = new WeakHashMap<>();
            Class<?> clz = Class.forName("org.z2six.villageroverhaul.client.ClientUI");

            Field f = null;
            try {
                f = clz.getDeclaredField("COMBAT_MODE_ID");
            } catch (NoSuchFieldException ignored) {}

            if (f == null) return null;
            f.setAccessible(true);

            Object mapObj = f.get(null);
            if (!(mapObj instanceof Map<?, ?> m)) return null;

            Object v = m.get(villagerId);
            return (v instanceof String s) ? s : null;

        } catch (Throwable ignored) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Shared widgets (copied from ClientUI vibe)
    // -------------------------------------------------------------------------

    private static final class CommandsBackdropWidget extends AbstractWidget {

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
}
