package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsData;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsUpdate;

public final class FarmingSettingsScreen extends Screen {

    private static final long SETTINGS_STALE_MS = 1200L;

    private final Screen parent;
    private final int villagerEntityId;

    private FarmingSettings settings = new FarmingSettings();

    // When we open a child editor screen, Minecraft will re-init this screen when it becomes active again.
    // We must preserve the local draft instead of reloading from cache/server (otherwise edits are lost).
    private boolean preserveLocalDraftOnNextInit = false;
    private boolean hasInitializedOnce = false;

    private EditBox stacksBox;
    private EditBox timeoutBox;
    private EditBox retryBox;
    private Button btnItems;
    private Button btnSave;
    private Button btnBack;

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 235;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    public FarmingSettingsScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Farming Settings"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    public void applyFromServer(PacketFarmingSettingsData msg) {
        try {
            if (msg == null) return;
            if (msg.villagerEntityId() != villagerEntityId) return;
            FarmingSettings incoming = FarmingSettings.fromTag(msg.settings());
            if (this.settings != null && incoming != null && incoming.updatedAt < this.settings.updatedAt) {
                return;
            }
            this.settings = incoming;
            applyToWidgets();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] FarmingSettingsScreen.applyFromServer failed (soft): {}", t.toString());
        }
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

        btnBack = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(left + PANEL_W - 58 - PAD, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(btnBack);

        btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 58 - PAD - 6 - 58, top + PAD)
                .size(58, 18)
                .build();
        addRenderableWidget(btnSave);

        int rowY = top + PAD + 40;
        int labelX = left + PAD;

        stacksBox = new EditBox(this.font, labelX + 178, rowY, 50, 18, Component.literal("Stacks"));
        stacksBox.setFilter(s -> s != null && s.matches("\\d{0,3}"));
        addRenderableWidget(stacksBox);

        btnItems = Button.builder(Component.literal("Edit items..."), b -> openItemList())
                .pos(labelX + 236, rowY)
                .size(88, 18)
                .build();
        addRenderableWidget(btnItems);

        int row2Y = rowY + 26;
        timeoutBox = new EditBox(this.font, labelX + 178, row2Y, 50, 18, Component.literal("Timeout"));
        timeoutBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        addRenderableWidget(timeoutBox);

        int row3Y = row2Y + 26;
        retryBox = new EditBox(this.font, labelX + 178, row3Y, 50, 18, Component.literal("Retry"));
        retryBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        addRenderableWidget(retryBox);

        // Load cached settings and/or query server, unless we're returning from the item editor with a local draft.
        if (!(hasInitializedOnce && preserveLocalDraftOnNextInit)) {
            FarmingSettings cached = ClientFarmingSettingsCache.get(villagerEntityId);
            long age = ClientFarmingSettingsCache.getAgeMs(villagerEntityId);
            if (cached != null) {
                this.settings = cached;
            }

            if (age > SETTINGS_STALE_MS) {
                try {
                    ClientNetwork.sendToServer(new PacketFarmingSettingsQuery(villagerEntityId));
                } catch (Throwable ignored) {}
            }
        }

        applyToWidgets();

        // Reset the "keep draft" latch after we used it.
        preserveLocalDraftOnNextInit = false;
        hasInitializedOnce = true;
    }

    private void applyToWidgets() {
        try {
            if (stacksBox != null) stacksBox.setValue(String.valueOf(Math.max(0, settings.stacksThreshold)));
            if (timeoutBox != null) timeoutBox.setValue(String.valueOf(Math.max(1, settings.timeoutSeconds)));
            if (retryBox != null) retryBox.setValue(String.valueOf(Math.max(1, settings.retryAfterSeconds)));
        } catch (Throwable ignored) {}
    }

    private void readFromWidgets() {
        try {
            int stacks = 0;
            try {
                String raw = stacksBox == null ? "" : stacksBox.getValue();
                stacks = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                stacks = 0;
            }
            settings.stacksThreshold = Math.max(0, stacks);

            int timeout = 60;
            try {
                String raw = timeoutBox == null ? "" : timeoutBox.getValue();
                timeout = raw == null || raw.isBlank() ? 60 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                timeout = 60;
            }
            settings.timeoutSeconds = Math.max(1, timeout);

            int retry = 60;
            try {
                String raw = retryBox == null ? "" : retryBox.getValue();
                retry = raw == null || raw.isBlank() ? 60 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                retry = 60;
            }
            settings.retryAfterSeconds = Math.max(1, retry);
        } catch (Throwable ignored) {}
    }

    private void openItemList() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new ItemListEditorScreen(this, settings.itemIds, "Farming: item list"));
        } catch (Throwable ignored) {}
    }

    private void onSave() {
        try {
            readFromWidgets();
            ClientNetwork.sendToServer(new PacketFarmingSettingsUpdate(villagerEntityId, settings.toTag()));
            onClose();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] FarmingSettingsScreen.onSave failed", t);
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, "Farming Settings", left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        int rowY = top + PAD + 44;
        gg.drawString(font, "Stacks threshold (0 = immediate):", left + PAD, rowY + 4, 0xFFBFBFBF, false);

        int row2Y = rowY + 26;
        gg.drawString(font, "Timeout (seconds):", left + PAD, row2Y + 4, 0xFFBFBFBF, false);

        int row3Y = row2Y + 26;
        gg.drawString(font, "Retry after (seconds):", left + PAD, row3Y + 4, 0xFFBFBFBF, false);

        int itemsY = rowY + 82;
        int count = settings == null || settings.itemIds == null ? 0 : settings.itemIds.size();
        gg.drawString(font, "Items in list: " + count, left + PAD, itemsY, 0xFFBFBFBF, false);

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
