package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.farming.FarmingSettings;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.ClientVillagerStatsCache;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsData;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsQuery;
import org.z2six.villageroverhaul.network.farming.PacketFarmingSettingsUpdate;
import org.z2six.villageroverhaul.network.stats.PacketVillagerStatsData;

public final class FarmingSettingsScreen extends Screen {

    private static final long SETTINGS_STALE_MS = 1200L;

    private final Screen parent;
    private final int villagerEntityId;

    private FarmingSettings settings = new FarmingSettings();
    private boolean farmingModuleEnabled = true;

    // When we open a child editor screen, Minecraft will re-init this screen when it becomes active again.
    // We must preserve the local draft instead of reloading from cache/server (otherwise edits are lost).
    private boolean preserveLocalDraftOnNextInit = false;
    private boolean hasInitializedOnce = false;

    private enum Tab { LOGISTICS, MANUAL }
    private Tab activeTab = Tab.LOGISTICS;

    private Button btnTabLogistics;
    private Button btnTabManual;

    // Logistics tab widgets
    private EditBox timeoutBox;
    private EditBox retryBox;
    private Button btnDepositRules;
    private Button btnWithdrawRules;
    private Button btnPickupRules;
    private Button btnRegisterDepositChest;
    private Button btnRegisterWithdrawChest;
    private Button btnTillSoilToggle;

    // Manual tab widgets
    private EditBox manualTimeoutBox;
    private EditBox manualRetryBox;
    private EditBox manualRangeBox;
    private Button btnHarvestRules;
    private Button btnPlantRules;
    private Button btnBonemealToggle;
    private Button btnDropOtherToggle;
    private Button btnRangeShapeToggle;
    private Button btnWorkstationRegister;

    private Button btnSave;
    private Button btnBack;

    // Match VillagerInfoScreen sizing for consistent UI.
    private static final int PANEL_W = 316;
    private static final int PANEL_H = 206;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int TIME_BOX_W = 34;
    private static final int RANGE_BOX_W = 32;

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

        try {
            var cfg = ClientSyncedConfig.get();
            farmingModuleEnabled = (cfg == null) || cfg.enableFarmingModule;
        } catch (Throwable ignored) {
            farmingModuleEnabled = true;
        }

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        btnBack = Button.builder(Component.literal("Back"), b -> onClose())
                .pos(left + PANEL_W - 58 - PAD, top + PAD)
                .size(58, 18)
                .build();
        btnBack.setTooltip(Tooltip.create(Component.literal("Close without saving.")));
        addRenderableWidget(btnBack);

        btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 58 - PAD - 6 - 58, top + PAD)
                .size(58, 18)
                .build();
        btnSave.setTooltip(Tooltip.create(Component.literal("Save settings to the villager.")));
        addRenderableWidget(btnSave);

        // Tabs (below title)
        int tabY = top + PAD + 22;
        btnTabLogistics = Button.builder(Component.literal("Logistics"), b -> switchTab(Tab.LOGISTICS))
                .pos(left + PAD, tabY)
                .size(92, 18)
                .build();
        btnTabLogistics.setTooltip(Tooltip.create(Component.literal("Storage rules: deposit/withdraw items to/from registered chests.")));
        addRenderableWidget(btnTabLogistics);

        btnTabManual = Button.builder(Component.literal("Manual farming"), b -> switchTab(Tab.MANUAL))
                .pos(left + PAD + 92 + 6, tabY)
                .size(120, 18)
                .build();
        btnTabManual.setTooltip(Tooltip.create(Component.literal("Manual farming AI: pick up, plant, harvest, and optionally bonemeal within the workstation area.")));
        addRenderableWidget(btnTabManual);

        // Extra breathing room under tabs.
        int rowY = top + PAD + 50;
        int labelX = left + PAD;

        // ------------------------------------------------------------
        // Logistics tab
        // ------------------------------------------------------------
        int innerW = PANEL_W - (PAD * 2);
        int halfW = (innerW - 6) / 2;

        // Shared row positions for both tabs (manual tab uses row2/3/4 for its timeout/retry/range).
        int row2Y = rowY + 22;
        int row3Y = row2Y + 22;
        int row4Y = row3Y + 22;

        // Row 1: buttons (instead of label + edit button rows)
        btnDepositRules = Button.builder(Component.literal("Deposit rules"), b -> openDepositRules())
                .pos(left + PAD, rowY)
                .size(halfW, 18)
                .build();
        btnDepositRules.setTooltip(Tooltip.create(Component.literal("Configure what items to deposit and when.\nRule: trigger stacks + keep stacks (kept in villager inventory).")));
        addRenderableWidget(btnDepositRules);

        btnWithdrawRules = Button.builder(Component.literal("Withdraw rules"), b -> openWithdrawRules())
                .pos(left + PAD + halfW + 6, rowY)
                .size(halfW, 18)
                .build();
        btnWithdrawRules.setTooltip(Tooltip.create(Component.literal("Configure what items to withdraw and when.\nRule: trigger stacks + keep stacks (kept in chest).")));
        addRenderableWidget(btnWithdrawRules);

        // Row 2: register chests (status)
        btnRegisterDepositChest = Button.builder(Component.literal("Register Deposit [ ]"), b -> beginDepositChestRegister())
                .pos(left + PAD, row2Y)
                .size(halfW, 18)
                .build();
        btnRegisterDepositChest.setTooltip(Tooltip.create(Component.literal("Register a deposit chest.\nAfter clicking, RMB a chest within the villager's work area.")));
        addRenderableWidget(btnRegisterDepositChest);

        btnRegisterWithdrawChest = Button.builder(Component.literal("Register Withdraw [ ]"), b -> beginWithdrawChestRegister())
                .pos(left + PAD + halfW + 6, row2Y)
                .size(halfW, 18)
                .build();
        btnRegisterWithdrawChest.setTooltip(Tooltip.create(Component.literal("Register a withdraw chest.\nAfter clicking, RMB a chest within the villager's work area.")));
        addRenderableWidget(btnRegisterWithdrawChest);

        // Row 3: timeout
        timeoutBox = new EditBox(this.font, labelX + 178, row3Y, TIME_BOX_W, 18, Component.literal("Timeout"));
        timeoutBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        timeoutBox.setTooltip(Tooltip.create(Component.literal("Storage timeout in seconds.\nIf moving/depositing/withdrawing takes longer than this, it fails and stops.")));
        addRenderableWidget(timeoutBox);

        // Row 4: retry
        retryBox = new EditBox(this.font, labelX + 178, row4Y, TIME_BOX_W, 18, Component.literal("Retry"));
        retryBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        retryBox.setTooltip(Tooltip.create(Component.literal("Storage retry delay in seconds.\nAfter a failure, the villager will try again after this delay.")));
        addRenderableWidget(retryBox);

        // Row 5: pickup rules + till toggle
        int logRow5Y = row4Y + 22;
        int tillW = 108;
        int pickupW = innerW - tillW - 6;
        btnPickupRules = Button.builder(Component.literal("Pickup rules"), b -> openPickupRules())
                .pos(left + PAD, logRow5Y)
                .size(pickupW, 18)
                .build();
        btnPickupRules.setTooltip(Tooltip.create(Component.literal("Configure which items the villager may pick up while manual farming.\nEmpty list = pick up all items (within the workstation area).")));
        addRenderableWidget(btnPickupRules);

        btnTillSoilToggle = Button.builder(Component.literal("Till soil [ ]"), b -> toggleTillSoil())
                .pos(left + PAD + pickupW + 6, logRow5Y)
                .size(tillW, 18)
                .build();
        btnTillSoilToggle.setTooltip(Tooltip.create(Component.literal("If enabled, the villager turns Dirt/Grass into Farmland in its work area (requires a hoe).\nThis is the lowest priority action and follows the manual farming timeout/retry rules.")));
        addRenderableWidget(btnTillSoilToggle);

        // ------------------------------------------------------------
        // Manual tab
        // ------------------------------------------------------------
        // Row 1: buttons (instead of label + separate edit button rows)
        btnHarvestRules = Button.builder(Component.literal("Harvest rules"), b -> openManualHarvestRules())
                .pos(left + PAD, rowY)
                .size(halfW, 18)
                .build();
        btnHarvestRules.setTooltip(Tooltip.create(Component.literal("Items to harvest.\nIf a mature crop drops any of these items, the villager will harvest it.")));
        addRenderableWidget(btnHarvestRules);

        btnPlantRules = Button.builder(Component.literal("Planting rules"), b -> openManualPlantRules())
                .pos(left + PAD + halfW + 6, rowY)
                .size(halfW, 18)
                .build();
        btnPlantRules.setTooltip(Tooltip.create(Component.literal("Items to plant.\nThese must be block items that can be placed on the correct base block (e.g. Farmland, or Nether Wart on Soul Sand).")));
        addRenderableWidget(btnPlantRules);

        // Row 2-4: same as before (labels rendered, widgets here)
        manualTimeoutBox = new EditBox(this.font, labelX + 178, row2Y, TIME_BOX_W, 18, Component.literal("Timeout"));
        manualTimeoutBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        manualTimeoutBox.setTooltip(Tooltip.create(Component.literal("Manual farming timeout in seconds.\nIf an action takes too long (stuck path), it fails and the villager roams until retry.")));
        addRenderableWidget(manualTimeoutBox);

        manualRetryBox = new EditBox(this.font, labelX + 178, row3Y, TIME_BOX_W, 18, Component.literal("Retry"));
        manualRetryBox.setFilter(s -> s != null && s.matches("\\d{0,5}"));
        manualRetryBox.setTooltip(Tooltip.create(Component.literal("Manual farming retry delay in seconds after a failure.")));
        addRenderableWidget(manualRetryBox);

        manualRangeBox = new EditBox(this.font, labelX + 178, row4Y, RANGE_BOX_W, 18, Component.literal("Range"));
        manualRangeBox.setFilter(s -> s != null && s.matches("\\d{0,3}"));
        manualRangeBox.setTooltip(Tooltip.create(Component.literal("Manual farming range around the workstation.\nClamped by the villager's Ranger stat + server config.")));
        addRenderableWidget(manualRangeBox);

        btnRangeShapeToggle = Button.builder(Component.literal("Circular"), b -> toggleRangeShape())
                .pos(labelX + 178 + RANGE_BOX_W + 6, row4Y)
                .size(72, 18)
                .build();
        btnRangeShapeToggle.setTooltip(Tooltip.create(Component.literal("Work area shape around the workstation.\nCircular uses distance; Square uses X/Z bounds.")));
        addRenderableWidget(btnRangeShapeToggle);

        // Row 5: compact action/toggles
        int row5Y = row4Y + 22;
        btnWorkstationRegister = Button.builder(Component.literal("Register Workstation [ ]"), b -> beginWorkstationRegister())
                .pos(left + PAD, row5Y)
                .size(innerW, 18)
                .build();
        btnWorkstationRegister.setTooltip(Tooltip.create(Component.literal("Register a workstation block.\nAfter clicking, RMB any block.\nIf not registered, the villager will fall back to its vanilla job site (if any).")));
        addRenderableWidget(btnWorkstationRegister);

        int row6Y = row5Y + 22;
        btnBonemealToggle = Button.builder(Component.literal("Use Bonemeal [ ]"), b -> toggleBonemeal())
                .pos(left + PAD, row6Y)
                .size(halfW, 18)
                .build();
        btnBonemealToggle.setTooltip(Tooltip.create(Component.literal("If enabled, the villager will bonemeal crops during manual farming.\nBonemeal must be available via withdraw rules (or already in inventory).")));
        addRenderableWidget(btnBonemealToggle);

        btnDropOtherToggle = Button.builder(Component.literal("Toss other items [ ]"), b -> toggleDropOtherItems())
                .pos(left + PAD + halfW + 6, row6Y)
                .size(halfW, 18)
                .build();
        btnDropOtherToggle.setTooltip(Tooltip.create(Component.literal("If enabled, the villager will drop any inventory items not used by manual farming and not referenced by deposit/withdraw rules.")));
        addRenderableWidget(btnDropOtherToggle);

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
        updateTabVisibility();

        if (!farmingModuleEnabled) {
            // Keep only navigation; server will reject updates anyway.
            if (btnSave != null) btnSave.active = false;
            if (btnTabLogistics != null) btnTabLogistics.visible = false;
            if (btnTabManual != null) btnTabManual.visible = false;
            switchTab(Tab.LOGISTICS);
            // Hide everything except Back/Save (save is disabled).
            if (btnDepositRules != null) btnDepositRules.visible = false;
            if (btnWithdrawRules != null) btnWithdrawRules.visible = false;
            if (btnPickupRules != null) btnPickupRules.visible = false;
            if (btnRegisterDepositChest != null) btnRegisterDepositChest.visible = false;
            if (btnRegisterWithdrawChest != null) btnRegisterWithdrawChest.visible = false;
            if (btnTillSoilToggle != null) btnTillSoilToggle.visible = false;
            if (timeoutBox != null) timeoutBox.visible = false;
            if (retryBox != null) retryBox.visible = false;

            if (btnHarvestRules != null) btnHarvestRules.visible = false;
            if (btnPlantRules != null) btnPlantRules.visible = false;
            if (manualTimeoutBox != null) manualTimeoutBox.visible = false;
            if (manualRetryBox != null) manualRetryBox.visible = false;
            if (manualRangeBox != null) manualRangeBox.visible = false;
            if (btnBonemealToggle != null) btnBonemealToggle.visible = false;
            if (btnDropOtherToggle != null) btnDropOtherToggle.visible = false;
            if (btnRangeShapeToggle != null) btnRangeShapeToggle.visible = false;
            if (btnWorkstationRegister != null) btnWorkstationRegister.visible = false;
        }

        // Reset the "keep draft" latch after we used it.
        preserveLocalDraftOnNextInit = false;
        hasInitializedOnce = true;
    }

    private void switchTab(Tab t) {
        if (t == null) t = Tab.LOGISTICS;
        readFromWidgets();
        activeTab = t;
        applyToWidgets();
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        if (!farmingModuleEnabled) return;
        boolean isLog = activeTab == Tab.LOGISTICS;

        if (btnTabLogistics != null) btnTabLogistics.active = !isLog;
        if (btnTabManual != null) btnTabManual.active = isLog;

        if (btnDepositRules != null) btnDepositRules.visible = isLog;
        if (btnWithdrawRules != null) btnWithdrawRules.visible = isLog;
        if (btnPickupRules != null) btnPickupRules.visible = isLog;
        if (timeoutBox != null) timeoutBox.visible = isLog;
        if (retryBox != null) retryBox.visible = isLog;
        if (btnRegisterDepositChest != null) btnRegisterDepositChest.visible = isLog;
        if (btnRegisterWithdrawChest != null) btnRegisterWithdrawChest.visible = isLog;
        if (btnTillSoilToggle != null) btnTillSoilToggle.visible = isLog;

        if (btnHarvestRules != null) btnHarvestRules.visible = !isLog;
        if (btnPlantRules != null) btnPlantRules.visible = !isLog;
        if (manualTimeoutBox != null) manualTimeoutBox.visible = !isLog;
        if (manualRetryBox != null) manualRetryBox.visible = !isLog;
        if (manualRangeBox != null) manualRangeBox.visible = !isLog;
        if (btnBonemealToggle != null) btnBonemealToggle.visible = !isLog;
        if (btnDropOtherToggle != null) btnDropOtherToggle.visible = !isLog;
        if (btnRangeShapeToggle != null) btnRangeShapeToggle.visible = !isLog;
        if (btnWorkstationRegister != null) btnWorkstationRegister.visible = !isLog;
    }

    private void applyToWidgets() {
        try {
            if (timeoutBox != null) timeoutBox.setValue(String.valueOf(Math.max(1, settings.timeoutSeconds)));
            if (retryBox != null) retryBox.setValue(String.valueOf(Math.max(1, settings.retryAfterSeconds)));

            if (manualTimeoutBox != null) manualTimeoutBox.setValue(String.valueOf(Math.max(1, settings.manualTimeoutSeconds)));
            if (manualRetryBox != null) manualRetryBox.setValue(String.valueOf(Math.max(1, settings.manualRetryAfterSeconds)));
            if (manualRangeBox != null) manualRangeBox.setValue(String.valueOf(Math.max(1, settings.manualRange)));
            if (btnWorkstationRegister != null) btnWorkstationRegister.setMessage(Component.literal("Register Workstation [" + (settings.manualWorkstationRegistered ? "x" : " ") + "]"));
            if (btnBonemealToggle != null) btnBonemealToggle.setMessage(Component.literal("Use Bonemeal [" + (settings.manualUseBonemeal ? "x" : " ") + "]"));
            if (btnDropOtherToggle != null) btnDropOtherToggle.setMessage(Component.literal("Toss other items [" + (settings.manualDropOtherItems ? "x" : " ") + "]"));
            if (btnRangeShapeToggle != null) btnRangeShapeToggle.setMessage(Component.literal(settings.manualRangeCircular ? "Circular" : "Square"));
            if (btnRegisterDepositChest != null) btnRegisterDepositChest.setMessage(Component.literal("Register Deposit [" + (settings.hasDepositChest ? "x" : " ") + "]"));
            if (btnRegisterWithdrawChest != null) btnRegisterWithdrawChest.setMessage(Component.literal("Register Withdraw [" + (settings.hasWithdrawChest ? "x" : " ") + "]"));
            if (btnTillSoilToggle != null) btnTillSoilToggle.setMessage(Component.literal("Till soil [" + (settings.manualTillSoil ? "x" : " ") + "]"));
        } catch (Throwable ignored) {}
    }

    private void readFromWidgets() {
        try {
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

            int mTimeout = 10;
            try {
                String raw = manualTimeoutBox == null ? "" : manualTimeoutBox.getValue();
                mTimeout = raw == null || raw.isBlank() ? 10 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                mTimeout = 10;
            }
            settings.manualTimeoutSeconds = Math.max(1, mTimeout);

            int mRetry = 10;
            try {
                String raw = manualRetryBox == null ? "" : manualRetryBox.getValue();
                mRetry = raw == null || raw.isBlank() ? 10 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                mRetry = 10;
            }
            settings.manualRetryAfterSeconds = Math.max(1, mRetry);

            int mr = 10;
            try {
                String raw = manualRangeBox == null ? "" : manualRangeBox.getValue();
                mr = raw == null || raw.isBlank() ? 10 : Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
                mr = 10;
            }
            int max = getMaxManualRangeClient();
            if (mr > max) mr = max;
            if (mr < 1) mr = 1;
            if (mr > 64) mr = 64;
            settings.manualRange = mr;

        } catch (Throwable ignored) {}
    }

    private void openDepositRules() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingItemRulesEditorScreen(this, settings.depositRules, "Deposit rules"));
        } catch (Throwable ignored) {}
    }

    private void openWithdrawRules() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingItemRulesEditorScreen(this, settings.withdrawRules, "Withdraw rules"));
        } catch (Throwable ignored) {}
    }

    private void openPickupRules() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingManualItemListEditorScreen(this, settings.pickupItemIds, "Pickup rules"));
        } catch (Throwable ignored) {}
    }

    private void openManualHarvestRules() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingManualItemListEditorScreen(this, settings.manualHarvestItemIds, "Harvest rules"));
        } catch (Throwable ignored) {}
    }

    private void openManualPlantRules() {
        try {
            readFromWidgets();
            preserveLocalDraftOnNextInit = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            mc.setScreen(new FarmingManualItemListEditorScreen(this, settings.manualPlantItemIds, "Planting rules"));
        } catch (Throwable ignored) {}
    }

    private void toggleDropOtherItems() {
        try {
            settings.manualDropOtherItems = !settings.manualDropOtherItems;
            applyToWidgets();
        } catch (Throwable ignored) {}
    }

    private void toggleBonemeal() {
        try {
            settings.manualUseBonemeal = !settings.manualUseBonemeal;
            applyToWidgets();
        } catch (Throwable ignored) {}
    }

    private void toggleRangeShape() {
        try {
            settings.manualRangeCircular = !settings.manualRangeCircular;
            applyToWidgets();
        } catch (Throwable ignored) {}
    }

    private void toggleTillSoil() {
        try {
            settings.manualTillSoil = !settings.manualTillSoil;
            applyToWidgets();
        } catch (Throwable ignored) {}
    }

    private void beginWorkstationRegister() {
        try {
            readFromWidgets();
            ClientUI.beginWorkstationRegistration(villagerEntityId);
        } catch (Throwable ignored) {}
    }

    private void beginDepositChestRegister() {
        try {
            readFromWidgets();
            ClientUI.beginChestRegistration(villagerEntityId);
        } catch (Throwable ignored) {}
    }

    private void beginWithdrawChestRegister() {
        try {
            readFromWidgets();
            ClientUI.beginWithdrawChestRegistration(villagerEntityId);
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
        if (!farmingModuleEnabled) {
            gg.drawString(font, "Farming module disabled by server", left + PAD, top + PAD + 50, 0xFFFF7777, false);
            super.render(gg, mouseX, mouseY, partialTick);
            return;
        }

        // Keep labels aligned with init() rows.
        int rowY = top + PAD + 50;
        boolean isLog = activeTab == Tab.LOGISTICS;

        if (isLog) {
            // Row 1 has buttons, no labels needed.
            int row3Y = rowY + 44;
            gg.drawString(font, "Timeout (seconds):", left + PAD, row3Y + 4, 0xFFBFBFBF, false);

            int row4Y = row3Y + 22;
            gg.drawString(font, "Retry after (seconds):", left + PAD, row4Y + 4, 0xFFBFBFBF, false);

            // Row 4 is the "Pickup rules" button.
            int row5Y = row4Y + 22;
            int infoY = row5Y + 22;
            int dep = settings == null || settings.depositRules == null ? 0 : settings.depositRules.size();
            int wd = settings == null || settings.withdrawRules == null ? 0 : settings.withdrawRules.size();
            int pu = settings == null || settings.pickupItemIds == null ? 0 : settings.pickupItemIds.size();
            gg.drawString(font, "Deposit: " + dep + " | Withdraw: " + wd + " | Pickup: " + pu, left + PAD, infoY, 0xFFBFBFBF, false);
        } else {
            int row2Y = rowY + 22;
            gg.drawString(font, "Timeout (seconds):", left + PAD, row2Y + 4, 0xFFBFBFBF, false);

            int row3Y = row2Y + 22;
            gg.drawString(font, "Retry after (seconds):", left + PAD, row3Y + 4, 0xFFBFBFBF, false);

            int row4Y = row3Y + 22;
            int max = getMaxManualRangeClient();
            gg.drawString(font, "Range (max " + max + "):", left + PAD, row4Y + 4, 0xFFBFBFBF, false);

            int infoY = row4Y + 66;
            int h = settings == null || settings.manualHarvestItemIds == null ? 0 : settings.manualHarvestItemIds.size();
            int p = settings == null || settings.manualPlantItemIds == null ? 0 : settings.manualPlantItemIds.size();
            gg.drawString(font, "Harvest: " + h + " | Planting: " + p, left + PAD, infoY, 0xFFBFBFBF, false);
        }

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

    private int getMaxManualRangeClient() {
        try {
            int base = 10;
            ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
            if (cfg != null) base = Math.max(1, Math.min(64, cfg.manualFarmBaseRange));

            PacketVillagerStatsData snap = ClientVillagerStatsCache.get(villagerEntityId);
            int pts = 0;
            if (snap != null && snap.ok()) pts = snap.ranger();
            if (pts < -100) pts = -100;
            if (pts > 100) pts = 100;

            double minPct = cfg == null ? 0.0 : cfg.rangerMinPct;
            double maxPct = cfg == null ? 0.0 : cfg.rangerMaxPct;

            double t = (pts + 100.0) / 200.0;
            if (t < 0.0) t = 0.0;
            if (t > 1.0) t = 1.0;
            double pct = minPct + (maxPct - minPct) * t;

            double mult = 1.0 + (pct / 100.0);
            if (Double.isNaN(mult) || Double.isInfinite(mult)) mult = 1.0;
            if (mult < 0.0) mult = 0.0;

            long out = Math.round(base * mult);
            if (out < 1L) out = 1L;
            if (out > 64L) out = 64L;
            return (int) out;
        } catch (Throwable ignored) {
            return 10;
        }
    }
}
