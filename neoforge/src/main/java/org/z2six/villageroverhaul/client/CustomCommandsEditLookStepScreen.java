package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.customcommands.PacketCcActionDetailQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcUpdateActionStepLookDuration;

public final class CustomCommandsEditLookStepScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;
    private final int actionIndex;
    private final int stepIndex;
    private final float initialSeconds;

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 92;

    private EditBox secondsBox;

    public CustomCommandsEditLookStepScreen(Screen parent, int villagerEntityId, int actionIndex, int stepIndex, float initialSeconds) {
        super(Component.literal("Edit look"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.actionIndex = actionIndex;
        this.stepIndex = stepIndex;
        this.initialSeconds = initialSeconds;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        secondsBox = new EditBox(this.font, left + 10, top + 28, PANEL_W - 20, 18, Component.literal("Seconds"));
        secondsBox.setMaxLength(16);
        secondsBox.setValue(String.valueOf(initialSeconds));
        secondsBox.setTooltip(Tooltip.create(Component.literal("How many seconds the villager should look (decimals allowed).")));
        addRenderableWidget(secondsBox);

        Button btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 10 - 60, top + PANEL_H - 10 - 18).size(60, 18).build();
        addRenderableWidget(btnSave);

        Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onCancel())
                .pos(left + PANEL_W - 10 - 60 - 64, top + PANEL_H - 10 - 18).size(60, 18).build();
        addRenderableWidget(btnCancel);
    }

    private void onSave() {
        try {
            float seconds = parseFloat(secondsBox == null ? "" : secondsBox.getValue(), initialSeconds);
            ClientNetwork.sendToServer(new PacketCcUpdateActionStepLookDuration(villagerEntityId, actionIndex, stepIndex, seconds));
            ClientNetwork.sendToServer(new PacketCcActionDetailQuery(villagerEntityId, actionIndex));
        } catch (Throwable ignored) {}
        onCancel();
    }

    private void onCancel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    private static float parseFloat(String s, float fallback) {
        try {
            if (s == null) return fallback;
            String t = s.trim();
            if (t.isEmpty()) return fallback;
            return Float.parseFloat(t);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xCC0B0B0B);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF3A3A3A);
        gg.drawString(this.font, Component.literal("Seconds to look:"), left + 10, top + 12, 0xFFB0B0B0);
        super.render(gg, mouseX, mouseY, partialTick);
    }
}

