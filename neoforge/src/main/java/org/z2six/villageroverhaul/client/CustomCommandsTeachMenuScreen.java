package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.customcommands.PacketCcAddWaypoint;
import org.z2six.villageroverhaul.network.customcommands.PacketCcBeginRecord;
import org.z2six.villageroverhaul.network.customcommands.PacketCcStopTeaching;

public final class CustomCommandsTeachMenuScreen extends Screen {

    private final int villagerEntityId;

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 186;

    private static final int ACTION_ROW_H = 20;
    private static final int ACTION_ROWS_VISIBLE = 5;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_PAD = 3;

    private final java.util.List<Button> actionButtons = new java.util.ArrayList<>();
    private int actionScroll = 0;
    private boolean scrollDragging = false;
    private int scrollDragOffsetY = 0;

    public CustomCommandsTeachMenuScreen(int villagerEntityId) {
        super(Component.literal("Teach"));
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

        int x = left + 10;
        int bw = PANEL_W - 20 - (SCROLLBAR_W + SCROLLBAR_PAD);
        int bh = 18;

        Button btnWaypoint = Button.builder(Component.literal("Add waypoint"), b -> {
                    ClientNetwork.sendToServer(new PacketCcAddWaypoint(villagerEntityId));
                })
                .pos(0, 0).size(bw, bh).build();
        btnWaypoint.setTooltip(Tooltip.create(Component.literal("Records the block you're standing on as a waypoint.")));
        addRenderableWidget(btnWaypoint);
        actionButtons.add(btnWaypoint);

        Button btnInteract = Button.builder(Component.literal("Interact"), b -> {
                    ClientNetwork.sendToServer(new PacketCcBeginRecord(villagerEntityId, 0));
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(null);
                })
                .pos(0, 0).size(bw, bh).build();
        btnInteract.setTooltip(Tooltip.create(Component.literal("Record a block or entity for the villager to interact with.")));
        addRenderableWidget(btnInteract);
        actionButtons.add(btnInteract);

        Button btnWait = Button.builder(Component.literal("Wait"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(new CustomCommandsWaitStepScreen(villagerEntityId));
                })
                .pos(0, 0).size(bw, bh).build();
        btnWait.setTooltip(Tooltip.create(Component.literal("Add a timed delay before the next step.")));
        addRenderableWidget(btnWait);
        actionButtons.add(btnWait);

        Button btnWithdraw = Button.builder(Component.literal("Withdraw item"), b -> {
                    ClientNetwork.sendToServer(new PacketCcBeginRecord(villagerEntityId, 1));
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(null);
                })
                .pos(0, 0).size(bw, bh).build();
        btnWithdraw.setTooltip(Tooltip.create(Component.literal("Record a chest/barrel for the villager to withdraw items from.")));
        addRenderableWidget(btnWithdraw);
        actionButtons.add(btnWithdraw);

        Button btnDeposit = Button.builder(Component.literal("Deposit item"), b -> {
                    ClientNetwork.sendToServer(new PacketCcBeginRecord(villagerEntityId, 2));
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(null);
                })
                .pos(0, 0).size(bw, bh).build();
        btnDeposit.setTooltip(Tooltip.create(Component.literal("Record a chest/barrel for the villager to deposit items into.")));
        addRenderableWidget(btnDeposit);
        actionButtons.add(btnDeposit);

        int bottomY = top + PANEL_H - 10 - 18;
        Button btnFinish = Button.builder(Component.literal("Finish teaching"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.setScreen(new CustomCommandsFinishTeachingScreen(this, villagerEntityId));
                })
                .pos(left + 10, bottomY).size(bw - 64, 18).build();
        btnFinish.setTooltip(Tooltip.create(Component.literal("Name and save what you've taught.")));
        addRenderableWidget(btnFinish);

        Button btnStop = Button.builder(Component.literal("Cancel"), b -> {
                    ClientNetwork.sendToServer(new PacketCcStopTeaching(villagerEntityId));
                    onClose();
                })
                .pos(left + PANEL_W - 10 - 60, bottomY).size(60, 18).build();
        btnStop.setTooltip(Tooltip.create(Component.literal("Stop teaching without saving.")));
        addRenderableWidget(btnStop);

        layoutActionButtons();
    }

    private void layoutActionButtons() {
        try {
            int left = (this.width - PANEL_W) / 2;
            int top = (this.height - PANEL_H) / 2;
            int x = left + 10;
            int panelY = top + 26;

            int maxScroll = Math.max(0, actionButtons.size() - ACTION_ROWS_VISIBLE);
            if (actionScroll < 0) actionScroll = 0;
            if (actionScroll > maxScroll) actionScroll = maxScroll;

            for (int i = 0; i < actionButtons.size(); i++) {
                Button b = actionButtons.get(i);
                int row = i - actionScroll;
                boolean vis = row >= 0 && row < ACTION_ROWS_VISIBLE;
                b.visible = vis;
                b.active = vis;
                if (vis) b.setPosition(x, panelY + row * ACTION_ROW_H);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int panelX = left + 10;
        int panelY = top + 26;
        int panelW = PANEL_W - 20;
        int panelH = ACTION_ROWS_VISIBLE * ACTION_ROW_H;
        if (mouseX < panelX || mouseX > panelX + panelW || mouseY < panelY || mouseY > panelY + panelH) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        int maxScroll = Math.max(0, actionButtons.size() - ACTION_ROWS_VISIBLE);
        actionScroll -= (int) Math.signum(scrollY);
        if (actionScroll < 0) actionScroll = 0;
        if (actionScroll > maxScroll) actionScroll = maxScroll;
        layoutActionButtons();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int panelX = left + 10;
        int panelY = top + 24;
        int panelW = PANEL_W - 20;
        int trackX = panelX + panelW - SCROLLBAR_W;
        int trackY = panelY + 2;
        int trackH = ACTION_ROWS_VISIBLE * ACTION_ROW_H;

        int maxScroll = Math.max(0, actionButtons.size() - ACTION_ROWS_VISIBLE);
        if (maxScroll > 0 && mouseX >= trackX && mouseX <= trackX + SCROLLBAR_W && mouseY >= trackY && mouseY <= trackY + trackH) {
            int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) ACTION_ROWS_VISIBLE / (double) Math.max(ACTION_ROWS_VISIBLE, actionButtons.size())));
            int thumbY = trackY + (int) Math.round((double) (trackH - thumbH) * ((double) actionScroll / (double) maxScroll));
            if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                scrollDragging = true;
                scrollDragOffsetY = (int) mouseY - thumbY;
                return true;
            }
            int y = (int) mouseY - trackY - (thumbH / 2);
            double frac = (trackH - thumbH) <= 0 ? 0.0 : (double) y / (double) (trackH - thumbH);
            frac = Math.max(0.0, Math.min(1.0, frac));
            actionScroll = (int) Math.round(frac * (double) maxScroll);
            layoutActionButtons();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!scrollDragging) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int panelY = top + 24;
        int trackY = panelY + 2;
        int trackH = ACTION_ROWS_VISIBLE * ACTION_ROW_H;

        int maxScroll = Math.max(0, actionButtons.size() - ACTION_ROWS_VISIBLE);
        if (maxScroll <= 0) return true;

        int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) ACTION_ROWS_VISIBLE / (double) Math.max(ACTION_ROWS_VISIBLE, actionButtons.size())));
        int newThumbY = (int) mouseY - scrollDragOffsetY;
        int minY = trackY;
        int maxY = trackY + trackH - thumbH;
        if (newThumbY < minY) newThumbY = minY;
        if (newThumbY > maxY) newThumbY = maxY;
        double frac = (maxY - minY) <= 0 ? 0.0 : (double) (newThumbY - minY) / (double) (maxY - minY);
        actionScroll = (int) Math.round(frac * (double) maxScroll);
        if (actionScroll < 0) actionScroll = 0;
        if (actionScroll > maxScroll) actionScroll = maxScroll;
        layoutActionButtons();
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) scrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xCC0B0B0B);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF3A3A3A);

        gg.drawString(this.font, Component.literal("Teach: actions"), left + 10, top + 10, 0xFFFFFFFF);

        int panelX = left + 10;
        int panelY = top + 24;
        int panelW = PANEL_W - 20;
        int panelH = ACTION_ROWS_VISIBLE * ACTION_ROW_H + 4;
        gg.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0x22000000);
        gg.renderOutline(panelX, panelY, panelW, panelH, 0xFF2E2E2E);

        int maxScroll = Math.max(0, actionButtons.size() - ACTION_ROWS_VISIBLE);
        if (maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_W;
            int trackY = panelY + 2;
            int trackH = ACTION_ROWS_VISIBLE * ACTION_ROW_H;
            gg.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, 0x33000000);

            int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) ACTION_ROWS_VISIBLE / (double) Math.max(ACTION_ROWS_VISIBLE, actionButtons.size())));
            int thumbY = trackY + (int) Math.round((double) (trackH - thumbH) * ((double) actionScroll / (double) maxScroll));
            gg.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xAA888888);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }
}
