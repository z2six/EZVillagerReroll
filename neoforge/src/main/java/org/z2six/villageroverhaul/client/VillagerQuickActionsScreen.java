// VillagerQuickActionsScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/VillagerQuickActionsScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.PacketVillagerCommand;

import java.lang.reflect.Constructor;

public final class VillagerQuickActionsScreen extends Screen {

    private final int villagerEntityId;

    private Button rerollBtn;
    private Button invBtn;
    private Button cmdBtn;
    private Button infoBtn;

    private boolean commandsExpanded = false;

    // movement row buttons
    private Button mvNeutral, mvIdle, mvFollow, mvPatrol;

    protected VillagerQuickActionsScreen(int villagerEntityId) {
        super(Component.empty());
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    protected void init() {
        try {
            // Ask server gate state right away
            ClientNetwork.sendToServer(new org.z2six.villageroverhaul.network.PacketRecruitGateQuery(villagerEntityId));
        } catch (Throwable ignored) {}

        int w = 18, h = 18;
        int gap = 2;

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

        // Commands row (hidden until expanded)
        int rowY = y + h + 6;

        mvNeutral = Button.builder(Component.literal("N"), b -> sendCmd(PacketVillagerCommand.Command.NEUTRAL))
                .pos(x0, rowY).size(w, h).build();
        mvIdle = Button.builder(Component.literal("I"), b -> sendCmd(PacketVillagerCommand.Command.IDLE))
                .pos(x0 + (w + gap), rowY).size(w, h).build();
        mvFollow = Button.builder(Component.literal("F"), b -> sendCmd(PacketVillagerCommand.Command.FOLLOW))
                .pos(x0 + 2 * (w + gap), rowY).size(w, h).build();
        mvPatrol = Button.builder(Component.literal("P"), b -> openPatrolPrompt())
                .pos(x0 + 3 * (w + gap), rowY).size(w, h).build();

        mvNeutral.setTooltip(Tooltip.create(Component.literal("Neutral")));
        mvIdle.setTooltip(Tooltip.create(Component.literal("Idle")));
        mvFollow.setTooltip(Tooltip.create(Component.literal("Follow")));
        mvPatrol.setTooltip(Tooltip.create(Component.literal("Patrol")));

        addRenderableWidget(mvNeutral);
        addRenderableWidget(mvIdle);
        addRenderableWidget(mvFollow);
        addRenderableWidget(mvPatrol);

        setCommandsVisible(false);

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
            rerollBtn.visible = controls; // show only if owner+recruited
            invBtn.active = controls;
            invBtn.visible = controls;
            cmdBtn.active = controls;
            cmdBtn.visible = controls;

            if (!controls) {
                commandsExpanded = false;
                setCommandsVisible(false);
            }
        } catch (Throwable ignored) {}
    }

    private void onReroll() {
        // This screen is used when no MerchantScreen opened; we intentionally do not support reroll here.
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Reroll requires the trading screen.").withStyle(ChatFormatting.YELLOW), true);
            }
        } catch (Throwable ignored) {}
    }

    private void onInventory() {
        try {
            ClientUI.openVillagerInventoryPlaceholder(null, villagerEntityId);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions inventory failed", t);
        }
    }

    private void onToggleCommands() {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) {
                commandsExpanded = false;
                setCommandsVisible(false);
                return;
            }

            commandsExpanded = !commandsExpanded;
            setCommandsVisible(commandsExpanded);
        } catch (Throwable ignored) {}
    }

    private void setCommandsVisible(boolean v) {
        try {
            setWidgetVisible(mvNeutral, v);
            setWidgetVisible(mvIdle, v);
            setWidgetVisible(mvFollow, v);
            setWidgetVisible(mvPatrol, v);
        } catch (Throwable ignored) {}
    }

    private void setWidgetVisible(AbstractWidget w, boolean v) {
        if (w == null) return;
        w.visible = v;
        w.active = v;
    }

    private void sendCmd(PacketVillagerCommand.Command cmd) {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;
            ClientNetwork.sendToServer(new PacketVillagerCommand(villagerEntityId, cmd));
            commandsExpanded = false;
            setCommandsVisible(false);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions sendCmd failed", t);
        }
    }

    private void openPatrolPrompt() {
        try {
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // Try to construct PatrolBeginPromptScreen in a signature-tolerant way.
            try {
                Class<?> clz = Class.forName("org.z2six.villageroverhaul.client.PatrolBeginPromptScreen");

                // Prefer (Screen,int) if it exists
                try {
                    Constructor<?> c = clz.getConstructor(Screen.class, int.class);
                    Object inst = c.newInstance(this, villagerEntityId);
                    if (inst instanceof Screen sc) {
                        mc.setScreen(sc);
                        return;
                    }
                } catch (Throwable ignored) {}

                // Fallback (MerchantScreen,int) won't work here, so last resort: (int)
                try {
                    Constructor<?> c = clz.getConstructor(int.class);
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

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions patrol prompt failed", t);
        }
    }

    private void onInfo() {
        try {
            // Info always allowed
            ClientUI.openVillagerInfoFromAnyParent(this, villagerEntityId);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] QuickActions info failed", t);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Refresh gate every tick (cheap; cached). This lets UI enable once server responds.
        applyGateToWidgets();
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // No background; minimal overlay feel
        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
