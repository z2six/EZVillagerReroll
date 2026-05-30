package org.z2six.villageroverhaul.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.naming.PacketOpenVillagerLastNameScreen;
import org.z2six.villageroverhaul.network.naming.PacketVillagerLastNameChange;

public final class VillagerLastNameScreen extends Screen {
    private static final int PANEL_W = 286;
    private static final int PANEL_H = 190;
    private static final int PAD = 12;
    private static final int ROW_H = 20;
    private static final int SCROLLBAR_W = 6;

    private final int villagerEntityId;
    private final String currentLastName;
    private final String message;
    private final List<String> lastNames = new ArrayList<>();

    private int selected = -1;
    private int scrollRow = 0;
    private String status = "";
    private boolean draggingScrollbar = false;
    private int scrollbarDragOffset = 0;

    private Button applyButton;

    public VillagerLastNameScreen(PacketOpenVillagerLastNameScreen packet) {
        this(
                packet == null ? -1 : packet.villagerEntityId(),
                packet == null ? "" : packet.currentLastName(),
                packet == null ? List.of() : packet.lastNames(),
                packet == null ? "" : packet.message()
        );
    }

    public VillagerLastNameScreen(int villagerEntityId, String currentLastName, List<String> lastNames, String message) {
        super(Component.literal("Family Name Deed"));
        this.villagerEntityId = villagerEntityId;
        this.currentLastName = currentLastName == null ? "" : currentLastName.trim();
        this.message = message == null ? "" : message.trim();

        if (lastNames != null) {
            for (String lastName : lastNames) {
                addName(lastName);
            }
        }
        for (int i = 0; i < this.lastNames.size(); i++) {
            if (this.lastNames.get(i).equalsIgnoreCase(this.currentLastName)) {
                this.selected = i;
                break;
            }
        }
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    protected void init() {
        int left = left();
        int top = top();

        Button back = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(left + PANEL_W - PAD - 58, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(back);

        applyButton = Button.builder(Component.literal("Apply"), b -> applySelected())
                .pos(left + PAD, top + PANEL_H - PAD - 20)
                .size(96, 20)
                .build();
        addRenderableWidget(applyButton);

        Button cancel = Button.builder(Component.literal("Cancel"), b -> onClose())
                .pos(left + PANEL_W - PAD - 76, top + PANEL_H - PAD - 20)
                .size(76, 20)
                .build();
        addRenderableWidget(cancel);

        clampScroll();
        updateButtons();
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible behind this small modal.
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            int left = left();
            int top = top();

            gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xD0000000);
            gg.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xBB191919);
            gg.drawCenteredString(this.font, "Family Name Deed", this.width / 2, top + 10, 0xFFFFFFFF);

            int textX = left + PAD;
            int y = top + 34;
            String current = currentLastName.isBlank() ? "Current: unknown" : "Current: " + currentLastName;
            gg.drawString(this.font, current, textX, y, 0xFFE0E0E0, false);
            y += 13;
            gg.drawString(this.font, "Choose a family name from this tree.", textX, y, 0xFFAAAAAA, false);

            String line = !status.isBlank() ? status : message;
            if (!line.isBlank()) {
                gg.drawString(this.font, line, textX, y + 13, 0xFFFFCC66, false);
            }

            drawList(gg, mouseX, mouseY);
            super.render(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}
    }

    private void drawList(GuiGraphics gg, int mouseX, int mouseY) {
        int x = listX();
        int y = listY();
        int w = listW();
        int h = listH();

        gg.fill(x, y, x + w, y + h, 0xAA0D0D0D);
        gg.fill(x, y, x + w, y + 1, 0xFF3A3A3A);
        gg.fill(x, y + h - 1, x + w, y + h, 0xFF3A3A3A);
        gg.fill(x, y, x + 1, y + h, 0xFF3A3A3A);
        gg.fill(x + w - 1, y, x + w, y + h, 0xFF3A3A3A);

        int visibleRows = Math.max(1, (h - 4) / ROW_H);
        for (int i = 0; i < visibleRows; i++) {
            int idx = scrollRow + i;
            if (idx < 0 || idx >= lastNames.size()) break;

            int rowY = y + 2 + i * ROW_H;
            boolean selectedRow = idx == selected;
            boolean currentRow = lastNames.get(idx).equalsIgnoreCase(currentLastName);
            boolean hover = mouseX >= x && mouseX < x + w - SCROLLBAR_W && mouseY >= rowY && mouseY < rowY + ROW_H;

            if (selectedRow) {
                gg.fill(x + 2, rowY, x + w - SCROLLBAR_W - 2, rowY + ROW_H - 2, 0x663A73D9);
            } else if (hover) {
                gg.fill(x + 2, rowY, x + w - SCROLLBAR_W - 2, rowY + ROW_H - 2, 0x442F2F2F);
            }

            int color = currentRow ? 0xFF6EEA7A : 0xFFE8E8E8;
            gg.drawString(this.font, lastNames.get(idx), x + 8, rowY + 6, color, false);
        }

        if (lastNames.isEmpty()) {
            gg.drawCenteredString(this.font, "No family names available", x + w / 2, y + h / 2 - 4, 0xFFAAAAAA);
        }

        drawScrollbar(gg, x, y, w, h, visibleRows);
    }

    private void drawScrollbar(GuiGraphics gg, int x, int y, int w, int h, int visibleRows) {
        if (!canScroll()) return;
        int barX = scrollbarX();
        gg.fill(barX, y + 1, barX + SCROLLBAR_W - 1, y + h - 1, 0xFF151515);
        gg.fill(barX + 1, thumbY(), barX + SCROLLBAR_W - 2, thumbY() + thumbH(), draggingScrollbar ? 0xFFAAAAAA : 0xFF777777);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int x = listX();
        int y = listY();
        int w = listW();
        int h = listH();
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
            return false;
        }

        if (canScroll() && mouseX >= scrollbarX() && mouseX < scrollbarX() + SCROLLBAR_W) {
            int thumbY = thumbY();
            int thumbH = thumbH();
            if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
                scrollbarDragOffset = (int) mouseY - thumbY;
            } else {
                scrollbarDragOffset = thumbH / 2;
                updateScrollFromMouse(mouseY);
            }
            draggingScrollbar = true;
            return true;
        }

        int row = scrollRow + (int) ((mouseY - y - 2) / ROW_H);
        if (row >= 0 && row < lastNames.size()) {
            selected = row;
            updateButtons();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (lastNames.isEmpty()) return false;
        if (scrollY > 0) scrollRow--;
        if (scrollY < 0) scrollRow++;
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void applySelected() {
        try {
            if (selected < 0 || selected >= lastNames.size()) {
                status = "Select a family name first";
                return;
            }
            ClientNetwork.sendToServer(new PacketVillagerLastNameChange(villagerEntityId, lastNames.get(selected)));
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {
            status = "Could not send rename request";
        }
    }

    private void updateButtons() {
        if (applyButton != null) {
            applyButton.active = selected >= 0 && selected < lastNames.size();
        }
    }

    private void clampScroll() {
        int max = maxScroll();
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > max) scrollRow = max;
    }

    private boolean canScroll() {
        return lastNames.size() > visibleRows();
    }

    private int visibleRows() {
        return Math.max(1, (listH() - 4) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, lastNames.size() - visibleRows());
    }

    private int scrollbarX() {
        return listX() + listW() - SCROLLBAR_W;
    }

    private int thumbH() {
        return Math.max(12, (listH() - 2) * visibleRows() / Math.max(visibleRows(), lastNames.size()));
    }

    private int thumbY() {
        int maxScroll = Math.max(1, maxScroll());
        int trackH = Math.max(1, listH() - 2 - thumbH());
        return listY() + 1 + (trackH * scrollRow / maxScroll);
    }

    private void updateScrollFromMouse(double mouseY) {
        int trackTop = listY() + 1;
        int trackH = Math.max(1, listH() - 2 - thumbH());
        double raw = mouseY - scrollbarDragOffset - trackTop;
        double ratio = raw / (double) trackH;
        int next = (int) Math.round(ratio * maxScroll());
        scrollRow = next;
        clampScroll();
    }

    private void addName(String raw) {
        if (raw == null) return;
        String name = raw.trim();
        if (name.isEmpty()) return;
        for (String existing : lastNames) {
            if (existing.equalsIgnoreCase(name)) return;
        }
        lastNames.add(name);
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    private int listX() {
        return left() + PAD;
    }

    private int listY() {
        return top() + 72;
    }

    private int listW() {
        return PANEL_W - PAD * 2;
    }

    private int listH() {
        return 74;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
