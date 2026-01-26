package org.z2six.villageroverhaul.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.customcommands.PacketCcActionDetailQuery;
import org.z2six.villageroverhaul.network.customcommands.PacketCcListQuery;

import java.util.ArrayList;
import java.util.List;

public final class CustomCommandsListScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;

    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;

    private static final int ROWS_VISIBLE = 8;
    private static final int ROW_H = 18;

    private static final class Entry {
        final int index;
        final String title;
        final String command;
        final boolean caseSensitive;
        final String desc;
        final int steps;
        final int timeoutSeconds;
        final int retryAfterSeconds;
        final int stopAfterRetries;
        Entry(int index, String title, String command, boolean caseSensitive, String desc, int steps, int timeoutSeconds, int retryAfterSeconds, int stopAfterRetries) {
            this.index = index;
            this.title = title;
            this.command = command;
            this.caseSensitive = caseSensitive;
            this.desc = desc;
            this.steps = steps;
            this.timeoutSeconds = timeoutSeconds;
            this.retryAfterSeconds = retryAfterSeconds;
            this.stopAfterRetries = stopAfterRetries;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int scroll = 0;

    public CustomCommandsListScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Custom Commands"));
        this.parent = parent;
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

        Button btnBack = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(left + PANEL_W - 10 - 60, top + 6).size(60, 18).build();
        addRenderableWidget(btnBack);

        ClientNetwork.sendToServer(new PacketCcListQuery(villagerEntityId));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        try {
            CompoundTag tag = CustomCommandsClientCache.consumeList(villagerEntityId);
            if (tag == null) return;

            entries.clear();
            if (tag.contains("list", Tag.TAG_LIST)) {
                ListTag list = tag.getList("list", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag e = list.getCompound(i);
                    entries.add(new Entry(
                            e.getInt("i"),
                            e.getString("t"),
                            e.getString("c"),
                            e.getBoolean("case"),
                            e.getString("d"),
                            e.getInt("n"),
                            e.getInt("to"),
                            e.getInt("ra"),
                            e.getInt("stop")
                    ));
                }
            }

            int maxScroll = Math.max(0, entries.size() - ROWS_VISIBLE);
            if (scroll > maxScroll) scroll = maxScroll;
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int maxScroll = Math.max(0, entries.size() - ROWS_VISIBLE);
        scroll -= (int) Math.signum(scrollY);
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        int listX = left + 10;
        int listY = top + 34;

        if (mouseX < listX || mouseX > listX + (PANEL_W - 20)) return super.mouseClicked(mouseX, mouseY, button);
        if (mouseY < listY || mouseY > listY + (ROWS_VISIBLE * ROW_H)) return super.mouseClicked(mouseX, mouseY, button);

        int row = (int) ((mouseY - listY) / ROW_H);
        int idx = scroll + row;
        if (idx < 0 || idx >= entries.size()) return super.mouseClicked(mouseX, mouseY, button);

        Entry e = entries.get(idx);
        ClientNetwork.sendToServer(new PacketCcActionDetailQuery(villagerEntityId, e.index));
        if (this.minecraft != null) this.minecraft.setScreen(new CustomCommandsActionDetailScreen(this, villagerEntityId, e.index));
        return true;
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xCC0B0B0B);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF3A3A3A);

        gg.drawString(this.font, Component.literal("Custom Commands"), left + 10, top + 10, 0xFFFFFFFF);

        int listX = left + 10;
        int listY = top + 34;

        int hoverIdx = -1;
        for (int i = 0; i < ROWS_VISIBLE; i++) {
            int idx = scroll + i;
            if (idx >= entries.size()) break;
            Entry e = entries.get(idx);
            int y = listY + (i * ROW_H);

            int bg = 0x22000000;
            if (mouseX >= listX && mouseX <= listX + (PANEL_W - 20) && mouseY >= y && mouseY <= y + ROW_H) {
                bg = 0x33000000;
                hoverIdx = idx;
            }
            gg.fill(listX, y, listX + (PANEL_W - 20), y + ROW_H - 1, bg);

            String label = e.title == null || e.title.isBlank() ? "<unnamed>" : e.title;
            gg.drawString(this.font, Component.literal(label), listX + 4, y + 5, 0xFFFFFFFF);
            gg.drawString(this.font, Component.literal(String.valueOf(e.steps)), listX + (PANEL_W - 28), y + 5, 0xFFB0B0B0);
        }

        super.render(gg, mouseX, mouseY, partialTick);

        if (hoverIdx >= 0 && hoverIdx < entries.size()) {
            Entry e = entries.get(hoverIdx);
            List<FormattedCharSequence> lines = new ArrayList<>();
            if (e.command != null && !e.command.isBlank()) {
                lines.add(Component.literal("Chat: " + e.command + (e.caseSensitive ? "" : " (case-insensitive)")).getVisualOrderText());
            }
            if (e.desc != null && !e.desc.isBlank()) lines.add(Component.literal(e.desc).getVisualOrderText());
            lines.add(Component.literal("Steps: " + e.steps).getVisualOrderText());
            String stop = "";
            try { if (e.stopAfterRetries > 0) stop = "  Stop: " + e.stopAfterRetries; } catch (Throwable ignored) { stop = ""; }
            lines.add(Component.literal("Timeout: " + e.timeoutSeconds + "s  Retry: " + e.retryAfterSeconds + "s" + stop).getVisualOrderText());
            gg.renderTooltip(this.font, lines, (int) mouseX, (int) mouseY);
        }
    }
}
