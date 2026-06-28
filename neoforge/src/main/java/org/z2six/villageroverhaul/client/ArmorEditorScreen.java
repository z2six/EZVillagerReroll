package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.client.render.ArmorEditorArmorKind;
import org.z2six.villageroverhaul.client.render.ArmorEditorArmorKindPolicy;
import org.z2six.villageroverhaul.client.render.ArmorEditorProfile;
import org.z2six.villageroverhaul.client.render.ArmorEditorRuntimeSettings;
import org.z2six.villageroverhaul.client.render.ArmorEditorSettings;
import org.z2six.villageroverhaul.client.render.ArmorEditorTransform;
import org.z2six.villageroverhaul.client.render.ArmorEditorTransformTarget;
import org.z2six.villageroverhaul.client.render.ArmorRandomCycle;
import org.z2six.villageroverhaul.render.VillagerRenderFlags;
import org.z2six.villageroverhaul.server.VillagerFactionService;
import org.z2six.villageroverhaul.server.VillagerGenderService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class ArmorEditorScreen extends Screen {
    private static final int PAD = 8;
    private static final int TOP_H = 30;
    private static final int LEFT_W = 206;
    private static final int RIGHT_W = 306;
    private static final int ROW_H = 18;
    private static final int ROW_GAP = 4;

    private static final int BG = 0xD0101010;
    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF444444;
    private static final int VIEW_BG = 0xAA050505;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final Screen parent;
    private final EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> committed = new EnumMap<>(ArmorEditorProfile.class);
    private final EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> working = new EnumMap<>(ArmorEditorProfile.class);
    private ArmorEditorProfile selectedProfile = ArmorEditorProfile.VILLAGER;
    private ArmorEditorArmorKind selectedArmorKind = ArmorEditorArmorKind.VANILLA;
    private boolean hideDwarfBodyShapeWithChest = true;
    private boolean dirty = false;

    private Villager previewVillager;
    private EditBox searchBox;
    private final List<Button> armorButtons = new ArrayList<>();
    private final List<String> armorButtonIds = new ArrayList<>();
    private final List<Button> paramButtons = new ArrayList<>();
    private final List<Button> slotButtons = new ArrayList<>();
    private final List<Button> targetButtons = new ArrayList<>();
    private Button profileButton;
    private Button armorKindButton;
    private Button bodyShapeButton;

    private final EnumMap<EquipmentSlot, String> equippedArmorIds = new EnumMap<>(EquipmentSlot.class);
    private final EnumMap<EquipmentSlot, ArmorRandomCycle> randomCycles = new EnumMap<>(EquipmentSlot.class);

    private String status = "";
    private EquipmentSlot selectedSlot = EquipmentSlot.HEAD;
    private ArmorEditorTransformTarget selectedTarget = ArmorEditorTransformTarget.slot();
    private Param selectedParam = Param.X;
    private int armorScroll = 0;

    private float previewYaw = 0.0f;
    private float previewPitch = 0.0f;
    private float previewZoom = 96.0f;
    private float panX = 0.0f;
    private float panY = 0.0f;
    private boolean orbitDragging = false;
    private boolean panDragging = false;
    private double lastDragX = 0.0;
    private double lastDragY = 0.0;

    private enum Param {
        X("X", false),
        Y("Y", false),
        Z("Z", false),
        SX("SX", true),
        SY("SY", true),
        SZ("SZ", true);

        final String label;
        final boolean scale;

        Param(String label, boolean scale) {
            this.label = label;
            this.scale = scale;
        }
    }

    public ArmorEditorScreen(Screen parent) {
        super(Component.literal("Villager Armor Editor"));
        this.parent = parent;
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                ArmorEditorSettings snapshot = ArmorEditorRuntimeSettings.snapshot(profile, armorKind);
                putSettings(this.committed, profile, armorKind, snapshot);
                putSettings(this.working, profile, armorKind, snapshot.copy());
                ArmorEditorRuntimeSettings.applyTransient(profile, armorKind, snapshot);
            }
        }
        Random random = new Random();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            randomCycles.put(slot, new ArmorRandomCycle(random));
        }
        this.hideDwarfBodyShapeWithChest = ArmorEditorRuntimeSettings.hideDwarfBodyShapeWithChest();
    }

    @Override
    protected void init() {
        super.init();
        armorButtons.clear();
        armorButtonIds.clear();
        paramButtons.clear();
        slotButtons.clear();
        targetButtons.clear();

        int topY = PAD;
        int x = PAD;

        addRenderableWidget(Button.builder(Component.literal("Apply"), b -> applySession())
                .pos(x, topY).size(62, 20).build());
        x += 68;
        addRenderableWidget(Button.builder(Component.literal("Log"), b -> logExport())
                .pos(x, topY).size(52, 20).build());
        x += 58;
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> copyExport())
                .pos(x, topY).size(58, 20).build());
        x += 64;
        addRenderableWidget(Button.builder(Component.literal("Reset All"), b -> resetAll())
                .pos(x, topY).size(82, 20).build());
        x += 88;
        profileButton = Button.builder(Component.literal(""), b -> cycleProfile())
                .pos(x, topY).size(92, 20).build();
        addRenderableWidget(profileButton);
        x += 98;
        armorKindButton = Button.builder(Component.literal(""), b -> cycleArmorKind())
                .pos(x, topY).size(104, 20).build();
        addRenderableWidget(armorKindButton);
        x += 110;
        bodyShapeButton = Button.builder(Component.literal(""), b -> toggleDwarfBodyShapeHide())
                .pos(x, topY).size(116, 20).build();
        addRenderableWidget(bodyShapeButton);

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .pos(this.width - PAD - 62, topY).size(62, 20).build());

        initLeftPanel();
        initRightPanel();
        ensureArmorSelection();
        updateSlotButtons();
        updateTargetButtons();
        updateParamButtons();
        updateProfileButtons();
        updateArmorButtons();
        updatePreviewEquipment();
    }

    private void initLeftPanel() {
        int x = leftX() + PAD;
        int y = panelY() + PAD;
        int w = LEFT_W - PAD * 2;

        int sw = (w - 6) / 2;
        addSlotButton("Head", EquipmentSlot.HEAD, x, y, sw);
        addSlotButton("Chest", EquipmentSlot.CHEST, x + sw + 6, y, sw);
        y += 22;
        addSlotButton("Legs", EquipmentSlot.LEGS, x, y, sw);
        addSlotButton("Feet", EquipmentSlot.FEET, x + sw + 6, y, sw);
        y += 30;

        int targetW = (w - 9) / 4;
        for (int i = 0; i < 4; i++) {
            final int buttonIndex = i;
            Button btn = Button.builder(Component.literal(""), b -> selectTarget(buttonIndex))
                    .pos(x + i * (targetW + 3), y)
                    .size(targetW, 18)
                    .build();
            addRenderableWidget(btn);
            targetButtons.add(btn);
        }
        y += 28;

        int bw = (w - 10) / 3;
        Param[] params = Param.values();
        for (int i = 0; i < params.length; i++) {
            Param p = params[i];
            int col = i % 3;
            int row = i / 3;
            Button btn = Button.builder(Component.literal(p.label), b -> {
                        selectedParam = p;
                        updateParamButtons();
                    })
                    .pos(x + col * (bw + 5), y + row * 22)
                    .size(bw, 18)
                    .build();
            addRenderableWidget(btn);
            paramButtons.add(btn);
        }
        y += 50;

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustSelected(-1.0f))
                .pos(x, y).size(34, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustSelected(1.0f))
                .pos(x + 40, y).size(34, 20).build());
    }

    private void addSlotButton(String label, EquipmentSlot slot, int x, int y, int w) {
        Button btn = Button.builder(Component.literal(label), b -> {
                    selectedSlot = slot;
                    normalizeSelectedTarget();
                    updateSlotButtons();
                    updateTargetButtons();
                    updateArmorButtons();
                })
                .pos(x, y)
                .size(w, 20)
                .build();
        addRenderableWidget(btn);
        slotButtons.add(btn);
    }

    private void initRightPanel() {
        int x = rightX() + PAD;
        int y = panelY() + PAD;
        int w = RIGHT_W - PAD * 2;

        searchBox = new EditBox(this.font, x, y, w, 18, Component.literal("Search armor"));
        searchBox.setResponder(s -> {
            armorScroll = 0;
            updateArmorButtons();
        });
        addRenderableWidget(searchBox);
        y += 26;

        int bw = (w - 18) / 4;
        addRenderableWidget(Button.builder(Component.literal("Randomize"), b -> randomizeArmor())
                .pos(x, y).size(bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Rand Slot"), b -> randomizeSelectedSlot())
                .pos(x + bw + 6, y).size(bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear Slot"), b -> clearSelectedSlot())
                .pos(x + (bw + 6) * 2, y).size(bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear All"), b -> clearAllArmor())
                .pos(x + (bw + 6) * 3, y).size(w - (bw + 6) * 3, 20).build());

        int rows = armorRows();
        int startY = armorListStartY();
        for (int i = 0; i < rows; i++) {
            final int buttonIndex = i;
            Button btn = Button.builder(Component.literal(""), b -> {
                        if (buttonIndex >= 0 && buttonIndex < armorButtonIds.size()) {
                            String id = armorButtonIds.get(buttonIndex);
                            if (id != null && !id.isBlank()) selectArmor(id);
                        }
                    })
                    .pos(x, startY + i * (ROW_H + ROW_GAP))
                    .size(w, ROW_H)
                    .build();
            btn.visible = false;
            btn.active = false;
            addRenderableWidget(btn);
            armorButtons.add(btn);
            armorButtonIds.add("");
        }
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, BG);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg, mouseX, mouseY, partialTick);
        drawViewport(gg);
        drawPanel(gg, leftX(), panelY(), LEFT_W, panelH());
        drawPanel(gg, rightX(), panelY(), RIGHT_W, panelH());
        renderPreview(gg, partialTick);
        drawLabels(gg);
        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void drawLabels(GuiGraphics gg) {
        int lx = leftX() + PAD;
        int ly = leftInfoY();
        int lw = LEFT_W - PAD * 2;
        gg.fill(lx - 3, ly - 4, lx + lw + 3, Math.min(panelY() + panelH() - PAD, ly + 89), 0xAA050505);
        gg.drawString(this.font, "Editing", lx, ly, 0xFFFFFFFF, false);
        ly += 13;
        gg.drawString(this.font, profileLabel(selectedProfile) + " / " + armorKindLabel(selectedArmorKind), lx, ly, 0xFFCCCCCC, false);
        ly += 13;
        gg.drawString(this.font, cleanSlotLabel(selectedSlot) + " " + selectedTarget.label() + " transform", lx, ly, 0xFFCCCCCC, false);
        ly += 13;
        gg.drawString(this.font, selectedTarget.slotTarget() ? "Adjusts the equipped armor slot" : "Adjusts only this armor model part", lx, ly, 0xFFCCCCCC, false);
        ly += 18;

        ArmorEditorTransform tx = currentTransform();
        gg.drawString(this.font, fit(tx.toDisplay(), lw), lx, ly, 0xFFDDDDDD, false);
        ly += 13;
        gg.drawString(this.font, fit("Wheel here or +/- adjusts " + selectedParam.label, lw), lx, ly, 0xFF999999, false);

        int vx = viewportX();
        int vy = viewportY();
        gg.drawString(this.font, "Villager Armor Editor", vx, PAD + 6, 0xFFFFFFFF, false);
        gg.drawString(this.font, fit(equippedSummary(), viewportW()), vx, vy + viewportH() + 8, 0xFFDDDDDD, false);
        if (!status.isBlank()) {
            gg.drawString(this.font, fit(status, viewportW()), vx, vy + viewportH() + 22, 0xFFFFCC66, false);
        }
    }

    private void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    private void drawViewport(GuiGraphics gg) {
        int x = viewportX();
        int y = viewportY();
        int w = viewportW();
        int h = viewportH();
        gg.fill(x, y, x + w, y + h, VIEW_BG);
        gg.fill(x, y, x + w, y + 1, 0xFF555555);
        gg.fill(x, y + h - 1, x + w, y + h, 0xFF555555);
        gg.fill(x, y, x + 1, y + h, 0xFF555555);
        gg.fill(x + w - 1, y, x + w, y + h, 0xFF555555);
    }

    private void renderPreview(GuiGraphics gg, float partialTick) {
        try {
            Villager villager = previewVillager();
            if (villager == null) {
                gg.drawCenteredString(this.font, "World not ready", viewportX() + viewportW() / 2, viewportY() + viewportH() / 2, 0xFFFF6666);
                return;
            }
            updatePreviewEquipment();

            int centerX = Math.round(viewportX() + viewportW() / 2.0f + panX);
            int centerY = Math.round(viewportY() + viewportH() / 2.0f + panY);
            int scissorHalfW = Math.max(640, viewportW() + Math.round(previewZoom * 5.0f));
            int scissorHalfH = Math.max(640, viewportH() + Math.round(previewZoom * 5.0f));
            int x1 = centerX - scissorHalfW;
            int y1 = centerY - scissorHalfH;
            int x2 = centerX + scissorHalfW;
            int y2 = centerY + scissorHalfH;

            try (ArmorEditorRuntimeSettings.Scope ignored = ArmorEditorRuntimeSettings.pushArmorKind(selectedArmorKind)) {
                InventoryScreen.renderEntityInInventoryFollowsAngle(
                        gg,
                        x1, y1, x2, y2,
                        Math.max(12, Math.round(previewZoom)),
                        0.0f,
                        previewYaw / 20.0f,
                        previewPitch / 20.0f,
                        villager
                );
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ArmorEditorScreen preview render failed (soft): {}", t.toString());
        }
    }

    private Villager previewVillager() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return null;
            if (previewVillager == null || previewVillager.level() != mc.level) {
                previewVillager = EntityType.VILLAGER.create(mc.level);
                if (previewVillager != null) {
                    previewVillager.setNoAi(true);
                    previewVillager.setPos(0.0, 0.0, 0.0);
                }
            }
            if (previewVillager != null && mc.player != null) {
                previewVillager.tickCount = mc.player.tickCount;
            }
            return previewVillager;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updatePreviewEquipment() {
        try {
            Villager villager = previewVillager;
            if (villager == null) return;

            for (EquipmentSlot slot : ARMOR_SLOTS) {
                villager.setItemSlot(slot, ItemStack.EMPTY);
            }

            for (EquipmentSlot slot : ARMOR_SLOTS) {
                String id = equippedArmorIds.get(slot);
                Item item = itemById(id);
                if (item instanceof ArmorItem armor && armorSlot(armor) == slot) {
                    villager.setItemSlot(slot, new ItemStack(item));
                }
            }

            if (villager instanceof VillagerOverhaulRenderAccess acc) {
                boolean hideHat = !villager.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
                acc.ezvr$setFaction(selectedProfile == ArmorEditorProfile.DWARF
                        ? VillagerFactionService.FACTION_DWARF
                        : VillagerFactionService.FACTION_HUMAN);
                acc.ezvr$setGenderId((byte) VillagerGenderService.GENDER_MALE);
                acc.ezvr$setRenderFlags(VillagerRenderFlags.pack(false, true, hideHat));
            }
        } catch (Throwable ignored) {}
    }

    private void selectArmor(String id) {
        Item item = itemById(id);
        if (!(item instanceof ArmorItem armor)) return;
        EquipmentSlot slot = armorSlot(armor);
        if (!isArmorSlot(slot)) return;

        ArmorEditorArmorKind armorKind = ArmorEditorArmorKindPolicy.armorKindForItemId(id);
        if (armorKind != selectedArmorKind) {
            selectedArmorKind = armorKind;
            removeEquippedArmorNotMatching(armorKind);
        }
        equippedArmorIds.put(slot, id == null ? "" : id.trim());
        status = "Equipped " + armorKindLabel(armorKind) + " " + cleanSlotLabel(slot) + " armor";
        updateSlotButtons();
        updateTargetButtons();
        updateProfileButtons();
        updateArmorButtons();
        updatePreviewEquipment();
    }

    private void clearSelectedSlot() {
        equippedArmorIds.remove(selectedSlot);
        status = "Cleared " + cleanSlotLabel(selectedSlot) + " slot";
        updateArmorButtons();
        updatePreviewEquipment();
    }

    private void clearAllArmor() {
        equippedArmorIds.clear();
        status = "Cleared armor preview";
        updateArmorButtons();
        updatePreviewEquipment();
    }

    private void randomizeArmor() {
        try {
            boolean equippedAny = false;
            for (EquipmentSlot slot : ARMOR_SLOTS) {
                List<String> ids = allArmorIdsForSlot(slot, "", selectedArmorKind);
                if (ids.isEmpty()) {
                    equippedArmorIds.remove(slot);
                    continue;
                }
                String id = nextRandomArmorId(slot, ids);
                equippedArmorIds.put(slot, id);
                equippedAny = true;
            }
            status = equippedAny ? "Random " + armorKindLabel(selectedArmorKind) + " armor equipped" : "No " + armorKindLabel(selectedArmorKind) + " armor items found";
            updateSlotButtons();
            updateTargetButtons();
            updateArmorButtons();
            updatePreviewEquipment();
        } catch (Throwable ignored) {
            status = "Randomize failed";
        }
    }

    private void randomizeSelectedSlot() {
        try {
            List<String> ids = allArmorIdsForSlot(selectedSlot, "", selectedArmorKind);
            if (ids.isEmpty()) {
                equippedArmorIds.remove(selectedSlot);
                status = "No " + armorKindLabel(selectedArmorKind) + " " + cleanSlotLabel(selectedSlot).toLowerCase(Locale.ROOT) + " armor found";
                updateArmorButtons();
                updatePreviewEquipment();
                return;
            }

            String id = nextRandomArmorId(selectedSlot, ids);
            equippedArmorIds.put(selectedSlot, id);
            status = "Randomized " + armorKindLabel(selectedArmorKind) + " " + cleanSlotLabel(selectedSlot) + " slot";
            updateArmorButtons();
            updatePreviewEquipment();
        } catch (Throwable ignored) {
            status = "Randomize slot failed";
        }
    }

    private void ensureArmorSelection() {
        boolean hasAny = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (equippedArmorIds.get(slot) != null && itemById(equippedArmorIds.get(slot)) instanceof ArmorItem) {
                hasAny = true;
                break;
            }
        }
        if (hasAny) return;

        List<String> ids = allArmorIdsForSlot(selectedSlot, "", selectedArmorKind);
        if (!ids.isEmpty()) selectArmor(ids.get(0));
    }

    private String nextRandomArmorId(EquipmentSlot slot, List<String> ids) {
        ArmorRandomCycle cycle = randomCycles.computeIfAbsent(slot, ignored -> new ArmorRandomCycle(new Random()));
        return cycle.next(ids, equippedArmorIds.get(slot));
    }

    private void removeEquippedArmorNotMatching(ArmorEditorArmorKind armorKind) {
        ArmorEditorArmorKind kind = armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            String id = equippedArmorIds.get(slot);
            if (id != null && ArmorEditorArmorKindPolicy.armorKindForItemId(id) != kind) {
                equippedArmorIds.remove(slot);
            }
        }
    }

    private void adjustSelected(float dir) {
        try {
            float step = selectedParam.scale ? 0.01f : 0.005f;
            if (Screen.hasShiftDown()) step *= 0.2f;
            if (Screen.hasControlDown()) step *= 5.0f;
            float delta = dir * step;

            ArmorEditorTransform tx = currentTransform().adjust(selectedParam.label, delta);
            ArmorEditorSettings.SlotKey slotKey = ArmorEditorRuntimeSettings.slotKey(selectedSlot);
            if (slotKey == null) return;
            if (selectedTarget.armsTarget()) {
                currentWorking().setPartTransform(slotKey, ArmorEditorSettings.PartKey.RIGHT_ARM, tx);
                currentWorking().setPartTransform(slotKey, ArmorEditorSettings.PartKey.LEFT_ARM, tx);
            } else if (selectedTarget.legsTarget()) {
                currentWorking().setPartTransform(slotKey, ArmorEditorSettings.PartKey.RIGHT_LEG, tx);
                currentWorking().setPartTransform(slotKey, ArmorEditorSettings.PartKey.LEFT_LEG, tx);
            } else if (selectedTarget.partTarget() != null) {
                currentWorking().setPartTransform(slotKey, selectedTarget.partTarget(), tx);
            } else {
                currentWorking().setSlotTransform(slotKey, tx);
            }
            dirty = true;
            status = "Unsaved changes";
            ArmorEditorRuntimeSettings.applyTransient(selectedProfile, selectedArmorKind, currentWorking());
        } catch (Throwable ignored) {}
    }

    private ArmorEditorTransform currentTransform() {
        ArmorEditorSettings.SlotKey slotKey = ArmorEditorRuntimeSettings.slotKey(selectedSlot);
        if (slotKey == null) return ArmorEditorTransform.IDENTITY;
        if (selectedTarget.armsTarget()) {
            return currentWorking().partTransform(slotKey, ArmorEditorSettings.PartKey.RIGHT_ARM);
        }
        if (selectedTarget.legsTarget()) {
            return currentWorking().partTransform(slotKey, ArmorEditorSettings.PartKey.RIGHT_LEG);
        }
        if (selectedTarget.partTarget() != null) {
            return currentWorking().partTransform(slotKey, selectedTarget.partTarget());
        }
        return currentWorking().slotTransform(slotKey);
    }

    private void resetAll() {
        putSettings(working, selectedProfile, selectedArmorKind, profileDefaults());
        dirty = true;
        status = profileLabel(selectedProfile) + " " + armorKindLabel(selectedArmorKind) + " armor settings reset";
        ArmorEditorRuntimeSettings.applyTransient(selectedProfile, selectedArmorKind, currentWorking());
    }

    private void applySession() {
        for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
            for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                ArmorEditorSettings settings = settingsIn(working, profile, armorKind);
                if (settings == null) settings = defaultsFor(profile, armorKind);
                ArmorEditorRuntimeSettings.save(profile, armorKind, settings);
                putSettings(committed, profile, armorKind, settings.copy());
            }
        }
        dirty = false;
        VillagerOverhaul.LOG().info("{}", exportAllWorking());
        status = "Applied all armor settings and exported to latest.log";
    }

    private void logExport() {
        try {
            String export = exportAllWorking();
            VillagerOverhaul.LOG().info("{}", export);
            status = "Export written to latest.log";
        } catch (Throwable t) {
            status = "Log export failed";
        }
    }

    private void copyExport() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.keyboardHandler == null) {
                status = "Clipboard unavailable";
                return;
            }
            mc.keyboardHandler.setClipboard(exportAllWorking());
            status = "Export copied";
        } catch (Throwable t) {
            status = "Clipboard export failed";
        }
    }

    private String exportAllWorking() {
        return ArmorEditorRuntimeSettings.exportAllHardcodeLines((profile, armorKind) -> {
            ArmorEditorSettings settings = settingsIn(working, profile, armorKind);
            return settings == null ? defaultsFor(profile, armorKind) : settings;
        });
    }

    private void updateSlotButtons() {
        for (int i = 0; i < slotButtons.size() && i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = ARMOR_SLOTS[i];
            Button b = slotButtons.get(i);
            b.setMessage(Component.literal((slot == selectedSlot ? "> " : "") + cleanSlotLabel(slot)));
        }
    }

    private void updateTargetButtons() {
        ArmorEditorSettings.SlotKey slotKey = ArmorEditorRuntimeSettings.slotKey(selectedSlot);
        selectedTarget = ArmorEditorTransformTarget.normalizeForSlot(slotKey, selectedTarget);
        List<ArmorEditorTransformTarget> targets = ArmorEditorTransformTarget.targetsForSlot(slotKey);
        for (int i = 0; i < targetButtons.size(); i++) {
            Button b = targetButtons.get(i);
            if (i < targets.size()) {
                ArmorEditorTransformTarget target = targets.get(i);
                b.visible = true;
                b.active = true;
                b.setMessage(Component.literal((target.equals(selectedTarget) ? "> " : "") + target.label()));
            } else {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
            }
        }
    }

    private void selectTarget(int buttonIndex) {
        ArmorEditorSettings.SlotKey slotKey = ArmorEditorRuntimeSettings.slotKey(selectedSlot);
        List<ArmorEditorTransformTarget> targets = ArmorEditorTransformTarget.targetsForSlot(slotKey);
        if (buttonIndex < 0 || buttonIndex >= targets.size()) return;
        selectedTarget = targets.get(buttonIndex);
        status = "Editing " + selectedTarget.label() + " transform";
        updateTargetButtons();
    }

    private void normalizeSelectedTarget() {
        selectedTarget = ArmorEditorTransformTarget.normalizeForSlot(
                ArmorEditorRuntimeSettings.slotKey(selectedSlot),
                selectedTarget
        );
    }

    private void updateParamButtons() {
        for (Button b : paramButtons) {
            try {
                String label = b.getMessage() == null ? "" : b.getMessage().getString();
                Param p = paramFromLabel(label);
                boolean selected = p == selectedParam;
                b.setMessage(Component.literal((selected ? "> " : "") + p.label));
            } catch (Throwable ignored) {}
        }
    }

    private void updateProfileButtons() {
        if (profileButton != null) {
            profileButton.setMessage(Component.literal("Model: " + profileLabel(selectedProfile)));
        }
        if (armorKindButton != null) {
            armorKindButton.setMessage(Component.literal("Armor: " + armorKindLabel(selectedArmorKind)));
        }
        if (bodyShapeButton != null) {
            bodyShapeButton.visible = selectedProfile == ArmorEditorProfile.DWARF;
            bodyShapeButton.active = selectedProfile == ArmorEditorProfile.DWARF;
            bodyShapeButton.setMessage(Component.literal(hideDwarfBodyShapeWithChest ? "Chest hides shape" : "Chest shows shape"));
        }
    }

    private void cycleProfile() {
        selectedProfile = selectedProfile == ArmorEditorProfile.DWARF ? ArmorEditorProfile.VILLAGER : ArmorEditorProfile.DWARF;
        ArmorEditorRuntimeSettings.applyTransient(selectedProfile, selectedArmorKind, currentWorking());
        status = "Editing " + profileLabel(selectedProfile) + " armor";
        updateProfileButtons();
        updateSlotButtons();
        updateTargetButtons();
        updateParamButtons();
        updatePreviewEquipment();
    }

    private void cycleArmorKind() {
        selectedArmorKind = selectedArmorKind == ArmorEditorArmorKind.MODDED ? ArmorEditorArmorKind.VANILLA : ArmorEditorArmorKind.MODDED;
        switchEquippedArmorToKind(selectedArmorKind);
        ArmorEditorRuntimeSettings.applyTransient(selectedProfile, selectedArmorKind, currentWorking());
        status = "Editing " + profileLabel(selectedProfile) + " " + armorKindLabel(selectedArmorKind) + " armor";
        updateProfileButtons();
        updateSlotButtons();
        updateTargetButtons();
        updateParamButtons();
        updateArmorButtons();
        updatePreviewEquipment();
    }

    private void switchEquippedArmorToKind(ArmorEditorArmorKind armorKind) {
        ArmorEditorArmorKind kind = armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            List<String> ids = allArmorIdsForSlot(slot, "", kind);
            if (ids.isEmpty()) {
                equippedArmorIds.remove(slot);
                continue;
            }
            equippedArmorIds.put(slot, nextRandomArmorId(slot, ids));
        }
    }

    private void toggleDwarfBodyShapeHide() {
        hideDwarfBodyShapeWithChest = !hideDwarfBodyShapeWithChest;
        ArmorEditorRuntimeSettings.setHideDwarfBodyShapeWithChest(hideDwarfBodyShapeWithChest);
        status = hideDwarfBodyShapeWithChest ? "Chest armor hides belly/breasts" : "Chest armor shows belly/breasts";
        updateProfileButtons();
    }

    private ArmorEditorSettings currentWorking() {
        ArmorEditorSettings settings = settingsIn(working, selectedProfile, selectedArmorKind);
        if (settings == null) {
            settings = profileDefaults();
            putSettings(working, selectedProfile, selectedArmorKind, settings);
        }
        return settings;
    }

    private ArmorEditorSettings profileDefaults() {
        return defaultsFor(selectedProfile, selectedArmorKind);
    }

    private static ArmorEditorSettings defaultsFor(ArmorEditorProfile profile, ArmorEditorArmorKind armorKind) {
        if (profile == ArmorEditorProfile.DWARF) {
            return armorKind == ArmorEditorArmorKind.MODDED
                    ? ArmorEditorSettings.dwarfModdedDefaults()
                    : ArmorEditorSettings.dwarfDefaults();
        }
        return armorKind == ArmorEditorArmorKind.MODDED
                ? ArmorEditorSettings.moddedDefaults()
                : ArmorEditorSettings.defaults();
    }

    private static ArmorEditorSettings settingsIn(EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> map,
                                                  ArmorEditorProfile profile,
                                                  ArmorEditorArmorKind armorKind) {
        if (map == null) return null;
        EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> byKind = map.get(profile == null ? ArmorEditorProfile.VILLAGER : profile);
        if (byKind == null) return null;
        return byKind.get(armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind);
    }

    private static void putSettings(EnumMap<ArmorEditorProfile, EnumMap<ArmorEditorArmorKind, ArmorEditorSettings>> map,
                                    ArmorEditorProfile profile,
                                    ArmorEditorArmorKind armorKind,
                                    ArmorEditorSettings settings) {
        if (map == null) return;
        ArmorEditorProfile p = profile == null ? ArmorEditorProfile.VILLAGER : profile;
        ArmorEditorArmorKind k = armorKind == null ? ArmorEditorArmorKind.VANILLA : armorKind;
        EnumMap<ArmorEditorArmorKind, ArmorEditorSettings> byKind = map.computeIfAbsent(p, ignored -> new EnumMap<>(ArmorEditorArmorKind.class));
        if (settings == null) {
            byKind.remove(k);
        } else {
            byKind.put(k, settings);
        }
    }

    private static String profileLabel(ArmorEditorProfile profile) {
        return profile == ArmorEditorProfile.DWARF ? "Dwarf" : "Villager";
    }

    private static String armorKindLabel(ArmorEditorArmorKind armorKind) {
        return armorKind == ArmorEditorArmorKind.MODDED ? "Modded" : "Vanilla";
    }

    private void updateArmorButtons() {
        try {
            String q = searchBox == null ? "" : searchBox.getValue();
            List<String> ids = allArmorIds(q);
            int maxScroll = Math.max(0, ids.size() - armorButtons.size());
            armorScroll = Mth.clamp(armorScroll, 0, maxScroll);
            String selectedArmorId = selectedArmorId();

            for (int i = 0; i < armorButtons.size(); i++) {
                Button b = armorButtons.get(i);
                int idx = armorScroll + i;
                if (idx >= 0 && idx < ids.size()) {
                    String id = ids.get(idx);
                    boolean selected = id.equals(selectedArmorId);
                    b.setMessage(Component.literal(fit((selected ? "* " : "") + id, RIGHT_W - PAD * 2 - 8)));
                    b.visible = true;
                    b.active = true;
                    armorButtonIds.set(i, id);
                } else {
                    b.setMessage(Component.literal(""));
                    b.visible = false;
                    b.active = false;
                    armorButtonIds.set(i, "");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static List<String> allArmorIds(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        try {
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (!(item instanceof ArmorItem)) continue;
                String s = id.toString();
                if (!q.isBlank() && !s.toLowerCase(Locale.ROOT).contains(q)) continue;
                ids.add(s);
            }
        } catch (Throwable ignored) {}
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static List<String> allArmorIdsForSlot(EquipmentSlot slot, String query) {
        return allArmorIdsForSlot(slot, query, null);
    }

    private static List<String> allArmorIdsForSlot(EquipmentSlot slot, String query, ArmorEditorArmorKind armorKind) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<String> ids = new ArrayList<>();
        try {
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (id == null) continue;
                Item item = BuiltInRegistries.ITEM.get(id);
                if (!(item instanceof ArmorItem armor)) continue;
                if (armorSlot(armor) != slot) continue;
                String s = id.toString();
                if (armorKind != null && ArmorEditorArmorKindPolicy.armorKindForItemId(s) != armorKind) continue;
                if (!q.isBlank() && !s.toLowerCase(Locale.ROOT).contains(q)) continue;
                ids.add(s);
            }
        } catch (Throwable ignored) {}
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static Item itemById(String id) {
        try {
            if (id == null || id.isBlank()) return null;
            return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id.trim()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String selectedArmorId() {
        String id = equippedArmorIds.get(selectedSlot);
        return id == null ? "" : id;
    }

    private String equippedSummary() {
        StringBuilder sb = new StringBuilder(160);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (!sb.isEmpty()) sb.append("  ");
            String id = equippedArmorIds.get(slot);
            sb.append(cleanSlotLabel(slot)).append('=').append(shortItemId(id));
        }
        return sb.toString();
    }

    private static String shortItemId(String id) {
        if (id == null || id.isBlank()) return "-";
        int colon = id.indexOf(':');
        return colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
    }

    private static EquipmentSlot armorSlot(ArmorItem armor) {
        try {
            return armor == null ? null : armor.getEquipmentSlot();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        if (slot == null) return false;
        for (EquipmentSlot armorSlot : ARMOR_SLOTS) {
            if (armorSlot == slot) return true;
        }
        return false;
    }

    private static String cleanSlotLabel(EquipmentSlot slot) {
        if (slot == null) return "Head";
        return switch (slot) {
            case HEAD -> "Head";
            case CHEST -> "Chest";
            case LEGS -> "Legs";
            case FEET -> "Feet";
            default -> "Head";
        };
    }

    private static Param paramFromLabel(String label) {
        String s = label == null ? "" : label.replace(">", "").trim().toUpperCase(Locale.ROOT);
        for (Param p : Param.values()) {
            if (p.label.equals(s)) return p;
        }
        return Param.X;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInside(mouseX, mouseY, viewportX(), viewportY(), viewportW(), viewportH())) {
            previewZoom = Mth.clamp(previewZoom + (float) scrollY * 8.0f, 18.0f, 360.0f);
            return true;
        }
        if (isInside(mouseX, mouseY, rightX(), panelY(), RIGHT_W, panelH())) {
            armorScroll -= (int) Math.signum(scrollY);
            updateArmorButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (isInside(mouseX, mouseY, viewportX(), viewportY(), viewportW(), viewportH())) {
            if (button == 0) {
                orbitDragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
                return true;
            }
            if (button == 1 || button == 2) {
                panDragging = true;
                lastDragX = mouseX;
                lastDragY = mouseY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) orbitDragging = false;
        if (button == 1 || button == 2) panDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (orbitDragging && button == 0) {
            double dx = mouseX - lastDragX;
            double dy = mouseY - lastDragY;
            lastDragX = mouseX;
            lastDragY = mouseY;
            previewYaw = Mth.wrapDegrees(previewYaw + (float) dx * 1.25f);
            previewPitch = Mth.clamp(previewPitch + (float) dy * 0.85f, -85.0f, 85.0f);
            return true;
        }
        if (panDragging && (button == 1 || button == 2)) {
            double dx = mouseX - lastDragX;
            double dy = mouseY - lastDragY;
            lastDragX = mouseX;
            lastDragY = mouseY;
            panX += (float) dx * 0.22f;
            panY += (float) dy * 0.22f;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void removed() {
        if (dirty) {
            for (ArmorEditorProfile profile : ArmorEditorProfile.values()) {
                for (ArmorEditorArmorKind armorKind : ArmorEditorArmorKind.values()) {
                    ArmorEditorRuntimeSettings.applyTransient(profile, armorKind, settingsIn(committed, profile, armorKind));
                }
            }
        }
        ArmorEditorRuntimeSettings.setHideDwarfBodyShapeWithChest(true);
        super.removed();
    }

    private int leftX() {
        return PAD;
    }

    private int rightX() {
        return this.width - RIGHT_W - PAD;
    }

    private int panelY() {
        return TOP_H + PAD;
    }

    private int panelH() {
        return this.height - panelY() - PAD;
    }

    private int viewportX() {
        return leftX() + LEFT_W + PAD;
    }

    private int viewportY() {
        return panelY();
    }

    private int viewportW() {
        return Math.max(120, rightX() - PAD - viewportX());
    }

    private int viewportH() {
        return Math.max(120, this.height - viewportY() - 44);
    }

    private int armorListStartY() {
        return panelY() + PAD + 52;
    }

    private int armorRows() {
        return Math.max(1, (panelY() + panelH() - PAD - armorListStartY()) / (ROW_H + ROW_GAP));
    }

    private int leftInfoY() {
        return Math.min(panelY() + panelH() - 99, panelY() + 194);
    }

    private String fit(String text, int maxWidth) {
        String s = text == null ? "" : text;
        if (maxWidth <= 0 || this.font.width(s) <= maxWidth) return s;
        String suffix = "...";
        int allowed = Math.max(0, maxWidth - this.font.width(suffix));
        while (!s.isEmpty() && this.font.width(s) > allowed) {
            s = s.substring(0, s.length() - 1);
        }
        return s + suffix;
    }

    private static boolean isInside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
