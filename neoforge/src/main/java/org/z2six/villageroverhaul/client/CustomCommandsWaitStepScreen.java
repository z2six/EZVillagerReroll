package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.customcommands.PacketCcAddWaitStep;

/**
 * Prompt screen for adding a WAIT step (seconds) during teaching.
 */
public final class CustomCommandsWaitStepScreen extends Screen {

    private final int villagerEntityId;

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 92;

    private EditBox secondsBox;

    public CustomCommandsWaitStepScreen(int villagerEntityId) {
        super(Component.literal("Wait"));
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        secondsBox = new EditBox(this.font, left + 10, top + 32, PANEL_W - 20, 18, Component.literal("Seconds"));
        secondsBox.setMaxLength(16);
        secondsBox.setValue("1.0");
        secondsBox.setTooltip(Tooltip.create(Component.literal("How many seconds to wait before the next step (supports decimals).")));
        addRenderableWidget(secondsBox);

        Button btnOk = Button.builder(Component.literal("OK"), b -> onOk())
                .pos(left + PANEL_W - 10 - 60, top + PANEL_H - 10 - 18)
                .size(60, 18)
                .build();
        addRenderableWidget(btnOk);

        Button btnCancel = Button.builder(Component.literal("Cancel"), b -> onClose())
                .pos(left + PANEL_W - 10 - 60 - 64, top + PANEL_H - 10 - 18)
                .size(60, 18)
                .build();
        addRenderableWidget(btnCancel);
    }

    private void onOk() {
        float seconds = parseSeconds(secondsBox == null ? "" : secondsBox.getValue(), 1.0f);
        ClientNetwork.sendToServer(new PacketCcAddWaitStep(villagerEntityId, seconds));
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(new CustomCommandsTeachMenuScreen(villagerEntityId));
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xCC0B0B0B);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF3A3A3A);

        gg.drawString(this.font, Component.literal("Wait (seconds)"), left + 10, top + 12, 0xFFFFFFFF);

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static float parseSeconds(String s, float fallback) {
        try {
            if (s == null) return fallback;
            String t = s.trim();
            if (t.isEmpty()) return fallback;
            float v = Float.parseFloat(t);
            if (Float.isNaN(v) || Float.isInfinite(v)) return fallback;
            if (v < 0.0f) v = 0.0f;
            if (v > 3600.0f) v = 3600.0f;
            return v;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}

