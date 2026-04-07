package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.z2six.villageroverhaul.network.customcommands.PacketCcChatListenQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcChatListenSet;

import java.util.ArrayList;
import java.util.List;

public final class CustomCommandsSettingsScreen extends Screen {
    private static final int PANEL_W = 316;
    private static final int PANEL_H = 148;
    private static final int PAD = 6;

    private static final int PANEL_BG = 0xFF101010;
    private static final int PANEL_BORDER = 0xFF2E2E2E;

    private final Screen parent;
    private final int villagerEntityId;

    private Button backBtn;
    private Button saveBtn;
    private Button listenBtn;
    private Button passBtn;

    private boolean listen = true;
    private boolean pass = false;
    private int passRange = 6;

    public CustomCommandsSettingsScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Custom Commands Settings"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    /**
     * Keep this NO-OP.
     * We apply the blur/background once in render(), then draw our panel above it.
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        backBtn = Button.builder(Component.literal("Back"), b -> onBack())
                .pos(left + PANEL_W - 58 - PAD - 58 - 4, top + PAD)
                .size(58, 18)
                .build();
        saveBtn = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 58 - PAD, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(backBtn);
        addRenderableWidget(saveBtn);

        listenBtn = Button.builder(Component.literal("Listen to chat commands [x]"), b -> {
            listen = !listen;
            listenBtn.setMessage(Component.literal(listen ? "Listen to chat commands [x]" : "Listen to chat commands []"));
        }).pos(left + PAD, top + PAD + 34).size(PANEL_W - PAD * 2, 20).build();
        addRenderableWidget(listenBtn);

        int rowY = top + PAD + 80;
        passBtn = Button.builder(Component.literal("Pass chat commands to nearby villagers []"), b -> {
            pass = !pass;
            passBtn.setMessage(Component.literal(pass ? "Pass chat commands to nearby villagers [x]" : "Pass chat commands to nearby villagers []"));
        }).pos(left + PAD, rowY).size(PANEL_W - PAD * 2, 20).build();
        addRenderableWidget(passBtn);

        try { ClientNetwork.sendToServer(new PacketCcChatListenQuery(villagerEntityId)); } catch (Throwable ignored) {}
    }

    public void applyData(boolean listen, boolean pass, int passRange) {
        this.listen = listen;
        this.pass = pass;
        this.passRange = passRange;
        if (listenBtn != null) listenBtn.setMessage(Component.literal(this.listen ? "Listen to chat commands [x]" : "Listen to chat commands []"));
        if (passBtn != null) passBtn.setMessage(Component.literal(this.pass ? "Pass chat commands to nearby villagers [x]" : "Pass chat commands to nearby villagers []"));
    }

    private void onBack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    private void onSave() {
        try { ClientNetwork.sendToServer(new PacketCcChatListenSet(villagerEntityId, listen, pass, passRange)); } catch (Throwable ignored) {}
        onBack();
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        drawPanel(gg, left, top, PANEL_W, PANEL_H, PANEL_BG, PANEL_BORDER);

        gg.drawString(this.font, Component.literal("Custom Commands Settings"), left + PAD, top + PAD, 0xFFFFFF);
        gg.drawString(this.font, Component.literal("Enable/disable chat + macro triggers for this villager."),
                left + PAD, top + PAD + 22, 0xC8C8C8);

        gg.drawString(this.font, Component.literal("Should this villager pass chat commands to nearby villagers"),
                left + PAD, top + PAD + 60, 0xC8C8C8);

        super.render(gg, mouseX, mouseY, partialTick);
        renderTooltips(gg, mouseX, mouseY);
    }

    private void renderTooltips(GuiGraphics gg, int mouseX, int mouseY) {
        try {
            if (listenBtn != null && listenBtn.isMouseOver(mouseX, mouseY)) {
                renderTooltipLines(gg, mouseX, mouseY,
                        "If disabled: this villager ignores all chat-triggered actions.",
                        "Affects both module chat commands and taught macros."
                );
                return;
            }
            if (passBtn != null && passBtn.isMouseOver(mouseX, mouseY)) {
                renderTooltipLines(gg, mouseX, mouseY,
                        "If enabled: this villager can relay heard chat commands to other nearby villagers.",
                        "Relay uses the same localized chat or whisper scope as normal hearing.",
                        "Requires \"Chain\" enabled in your chat commands screen."
                );
                return;
            }
        } catch (Throwable ignored) {}
    }

    private void renderTooltipLines(GuiGraphics gg, int mouseX, int mouseY, String... lines) {
        try {
            if (gg == null || this.font == null || lines == null || lines.length == 0) return;
            List<FormattedCharSequence> out = new ArrayList<>();
            for (String s : lines) {
                if (s == null || s.isBlank()) continue;
                out.add(Component.literal(s).getVisualOrderText());
            }
            if (out.isEmpty()) return;
            gg.renderTooltip(this.font, out, mouseX, mouseY);
        } catch (Throwable ignored) {}
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h, int bg, int border) {
        try {
            gg.fill(x, y, x + w, y + h, bg);
            gg.fill(x, y, x + w, y + 1, border);
            gg.fill(x, y + h - 1, x + w, y + h, border);
            gg.fill(x, y, x + 1, y + h, border);
            gg.fill(x + w - 1, y, x + w, y + h, border);
        } catch (Throwable ignored) {}
    }
}
