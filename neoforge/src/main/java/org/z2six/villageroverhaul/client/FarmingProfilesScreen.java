package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfilesQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingProfileDelete;
import org.z2six.villageroverhaul.server.PlayerFarmingProfilesSavedData;

import java.util.ArrayList;
import java.util.List;

public final class FarmingProfilesScreen extends Screen {

    private static final long DATA_STALE_MS = 1200L;
    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;
    private static final int PAD = 10;
    private static final int ROW_H = 20;
    private static final int SCROLLBAR_W = 7;

    private final Screen parent;
    private final FarmingSettingsScreen targetScreen;
    private final boolean applyMode;

    private final List<PlayerFarmingProfilesSavedData.Profile> profiles = new ArrayList<>();
    private int selected = -1;
    private int scrollRow = 0;
    private String status = "";

    private EditBox nameBox;
    private Button btnAdd;
    private Button btnEdit;
    private Button btnDelete;
    private Button btnBack;

    public FarmingProfilesScreen(Screen parent) {
        this(parent, null);
    }

    public FarmingProfilesScreen(Screen parent, FarmingSettingsScreen targetScreen) {
        super(Component.literal("Manual Farming Profiles"));
        this.parent = parent;
        this.targetScreen = targetScreen;
        this.applyMode = targetScreen != null;
    }

    public void applyFromServer(CompoundTag tag) {
        try {
            String oldSelected = selected >= 0 && selected < profiles.size() ? profiles.get(selected).name() : "";
            profiles.clear();
            profiles.addAll(PlayerFarmingProfilesSavedData.fromTag(tag));
            selected = -1;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).name().equalsIgnoreCase(oldSelected)) {
                    selected = i;
                    break;
                }
            }
            clampScroll();
            updateButtons();
        } catch (Throwable ignored) {}
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

        btnBack = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(left + PANEL_W - PAD - 58, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(btnBack);

        if (!applyMode) {
            nameBox = new EditBox(this.font, left + PAD, top + 34, 118, 18, Component.literal("Profile name"));
            nameBox.setMaxLength(48);
            nameBox.setTooltip(Tooltip.create(Component.literal("Name for a new manual farming profile.")));
            addRenderableWidget(nameBox);

            btnAdd = Button.builder(Component.literal("Add"), b -> onAdd())
                    .pos(left + PAD + 118 + 6, top + 34)
                    .size(46, 18)
                    .build();
            btnAdd.setTooltip(Tooltip.create(Component.literal("Create a new profile and open the farming settings editor.")));
            addRenderableWidget(btnAdd);

            btnEdit = Button.builder(Component.literal("Edit"), b -> onEdit())
                    .pos(left + PAD + 118 + 6 + 46 + 6, top + 34)
                    .size(46, 18)
                    .build();
            btnEdit.setTooltip(Tooltip.create(Component.literal("Edit the selected profile.")));
            addRenderableWidget(btnEdit);

            btnDelete = Button.builder(Component.literal("Delete"), b -> onDelete())
                    .pos(left + PAD + 118 + 6 + 46 + 6 + 46 + 6, top + 34)
                    .size(56, 18)
                    .build();
            btnDelete.setTooltip(Tooltip.create(Component.literal("Delete the selected profile.")));
            addRenderableWidget(btnDelete);
        }

        CompoundTag cached = PlayerFarmingProfilesClientCache.get();
        if (cached != null) applyFromServer(cached);
        if (PlayerFarmingProfilesClientCache.getAgeMs() > DATA_STALE_MS) {
            try { ClientNetwork.sendToServer(new PacketFarmingProfilesQuery()); } catch (Throwable ignored) {}
        }
        updateButtons();
    }

    private void onAdd() {
        try {
            String name = PlayerFarmingProfilesSavedData.sanitizeName(nameBox == null ? "" : nameBox.getValue());
            if (name.isEmpty()) {
                status = "Enter a profile name first";
                return;
            }
            for (PlayerFarmingProfilesSavedData.Profile p : profiles) {
                if (p != null && name.equalsIgnoreCase(p.name())) {
                    status = "Profile already exists";
                    return;
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingSettingsScreen(this, name, new FarmingSettings()));
        } catch (Throwable ignored) {}
    }

    private void onEdit() {
        try {
            PlayerFarmingProfilesSavedData.Profile profile = selectedProfile();
            if (profile == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingSettingsScreen(this, profile.name(), profile.settings()));
        } catch (Throwable ignored) {}
    }

    private void onDelete() {
        try {
            PlayerFarmingProfilesSavedData.Profile profile = selectedProfile();
            if (profile == null) return;
            ClientNetwork.sendToServer(new PacketFarmingProfileDelete(profile.name()));
            status = "Deleted " + profile.name();
        } catch (Throwable ignored) {}
    }

    private void applySelectedToTarget() {
        try {
            if (!applyMode || targetScreen == null) return;
            PlayerFarmingProfilesSavedData.Profile profile = selectedProfile();
            if (profile == null) return;
            targetScreen.applyProfileFromPreset(profile.name(), profile.settings());
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(targetScreen);
        } catch (Throwable ignored) {}
    }

    private PlayerFarmingProfilesSavedData.Profile selectedProfile() {
        if (selected < 0 || selected >= profiles.size()) return null;
        return profiles.get(selected);
    }

    private void updateButtons() {
        boolean hasSelection = selectedProfile() != null;
        if (btnEdit != null) btnEdit.active = hasSelection;
        if (btnDelete != null) btnDelete.active = hasSelection;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;

        int listX = getListX();
        int listY = getListY();
        int listW = getListW();
        int listH = getListH();
        if (mouseX < listX || mouseX >= (listX + listW) || mouseY < listY || mouseY >= (listY + listH)) {
            return false;
        }

        int row = scrollRow + (int) ((mouseY - listY - 4) / ROW_H);
        if (row >= 0 && row < profiles.size()) {
            selected = row;
            updateButtons();
            if (applyMode) {
                applySelectedToTarget();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (profiles.isEmpty()) return false;
        if (scrollY > 0) scrollRow--;
        if (scrollY < 0) scrollRow++;
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int max = Math.max(0, profiles.size() - getVisibleRows());
        if (scrollRow < 0) scrollRow = 0;
        if (scrollRow > max) scrollRow = max;
    }

    private int getVisibleRows() {
        return Math.max(1, (getListH() - 8) / ROW_H);
    }

    private int getListX() {
        return (this.width - PANEL_W) / 2 + PAD;
    }

    private int getListY() {
        int top = (this.height - PANEL_H) / 2;
        return top + (applyMode ? 34 : 58);
    }

    private int getListW() {
        return PANEL_W - PAD * 2 - SCROLLBAR_W - 2;
    }

    private int getListH() {
        int top = (this.height - PANEL_H) / 2;
        int bottom = top + PANEL_H - PAD;
        return Math.max(40, bottom - getListY() - 18);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        drawPanel(gg, left, top, PANEL_W, PANEL_H, 0xCC0B0B0B, 0xFF3A3A3A);

        Font font = this.font;
        gg.drawString(font, applyMode ? "Load Farming Profile" : "Manual Farming Profiles", left + PAD, top + PAD + 5, 0xFFFFFFFF, true);
        if (applyMode) {
            gg.drawString(font, "Click a profile to load it into this villager.", left + PAD, top + 34, 0xFFBFBFBF, false);
        } else if (!status.isEmpty()) {
            gg.drawString(font, status, left + PAD, top + 56, 0xFFBFBFBF, false);
        }

        int listX = getListX();
        int listY = getListY();
        int listW = getListW();
        int listH = getListH();
        drawPanel(gg, listX, listY, listW, listH, 0xFF101010, 0xFF2E2E2E);

        boolean scissor = false;
        try {
            gg.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
            scissor = true;
        } catch (Throwable ignored) {}

        int y = listY + 4;
        for (int i = scrollRow; i < profiles.size(); i++) {
            int rowY = y + (i - scrollRow) * ROW_H;
            if (rowY + ROW_H > listY + listH) break;

            int bg = i == selected ? 0xFF2D3E54 : 0x00000000;
            if (bg != 0) gg.fill(listX + 2, rowY, listX + listW - 2, rowY + 18, bg);
            String name = profiles.get(i).name();
            gg.drawString(font, name, listX + 6, rowY + 5, 0xFFFFFFFF, false);
        }

        if (profiles.isEmpty()) {
            gg.drawString(font, applyMode ? "No profiles saved yet." : "No profiles. Create one above.", listX + 6, listY + 6, 0xFF9A9A9A, false);
        }

        if (scissor) {
            try { gg.disableScissor(); } catch (Throwable ignored) {}
        }

        renderScrollbar(gg, listX + listW + 2, listY, SCROLLBAR_W, listH);
        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void renderScrollbar(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, 0xFF101010);
        gg.fill(x, y, x + w, y + 1, 0xFF2E2E2E);
        gg.fill(x, y + h - 1, x + w, y + h, 0xFF2E2E2E);
        gg.fill(x, y, x + 1, y + h, 0xFF2E2E2E);
        gg.fill(x + w - 1, y, x + w, y + h, 0xFF2E2E2E);

        int visible = getVisibleRows();
        int total = Math.max(1, profiles.size());
        int maxScroll = Math.max(0, total - visible);
        if (maxScroll <= 0) {
            gg.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF3F3F3F);
            return;
        }

        int thumbH = Math.max(18, (int) ((visible / (double) total) * (h - 2)));
        int track = Math.max(1, h - 2 - thumbH);
        int thumbY = y + 1 + (int) Math.round((scrollRow / (double) maxScroll) * track);
        gg.fill(x + 1, thumbY, x + w - 1, thumbY + thumbH, 0xFF707070);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h, int bg, int border) {
        gg.fill(x, y, x + w, y + h, bg);
        gg.fill(x, y, x + w, y + 1, border);
        gg.fill(x, y + h - 1, x + w, y + h, border);
        gg.fill(x, y, x + 1, y + h, border);
        gg.fill(x + w - 1, y, x + w, y + h, border);
    }
}
