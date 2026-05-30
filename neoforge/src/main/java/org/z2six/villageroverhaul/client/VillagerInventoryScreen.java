// neoforge\src\main\java\org\z2six\villageroverhaul\client\VillagerInventoryScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;

import java.util.List;

public final class VillagerInventoryScreen extends AbstractContainerScreen<VillagerInventoryMenu> {

    private LivingEntity cachedEntity;

    // Style
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int BOX_BG = 0xFF101010;
    private static final int BOX_BORDER = 0xFF2E2E2E;

    // Slot visuals
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_BG = 0xFF151515;
    private static final int SLOT_BORDER = 0xFF404040;

    // Model box
    private static final int MODEL_BOX_W = 88;
    private static final int MODEL_BOX_H = 112;

    // Layout gaps
    private static final int GAP_ARMOR_TO_MODEL = 10;

    // Header
    private static final int HEADER_TITLE_Y = PAD + 5;
    private static final int HEADER_NAME_GAP_Y = 12;

    // Content anchors
    private static final int CONTENT_TOP_Y = 40;
    private static final int PANEL_RAISE_Y = 8;

    private Button backBtn;

    public VillagerInventoryScreen(VillagerInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title == null ? Component.literal("Villager Inventory") : title);
        this.imageWidth = VillagerInventoryMenu.IMAGE_W;
        this.imageHeight = VillagerInventoryMenu.IMAGE_H;
    }

    public int getVillagerEntityId() {
        try {
            return this.menu == null ? -1 : this.menu.getVillagerEntityId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    @Override
    protected void init() {
        super.init();

        resolveEntity();

        this.topPos = computePanelTop();

        this.backBtn = this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(this.leftPos + this.imageWidth - 58 - PAD, this.topPos + PAD)
                        .size(58, 18)
                        .build()
        );
    }

    private int computePanelTop() {
        int top = (this.height - this.imageHeight) / 2;
        top -= PANEL_RAISE_Y;
        if (top < 4) top = 4;
        return top;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        resolveEntity();
    }

    private void resolveEntity() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            Entity e = mc.level.getEntity(this.menu.getVillagerEntityId());
            if (e instanceof LivingEntity le) this.cachedEntity = le;
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Remove vanilla gray labels ("title" and "Inventory") completely.
     */
    @Override
    protected void renderLabels(GuiGraphics gg, int mouseX, int mouseY) {
        // no-op
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics gg, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;

        drawPanel(gg, left, top, this.imageWidth, this.imageHeight);

        Font font = Minecraft.getInstance().font;

        gg.drawString(font, Component.literal("Villager Inventory"),
                left + PAD, top + HEADER_TITLE_Y, 0xFFFFFFFF, true);

        gg.drawString(font, safeNameLine(this.cachedEntity),
                left + PAD, top + HEADER_TITLE_Y + HEADER_NAME_GAP_Y, 0xFFBFBFBF, false);

        // Layout anchors (must match menu constants)
        int armorX = left + PAD;
        int armorY = top + CONTENT_TOP_Y;

        int modelX1 = armorX + SLOT_SIZE + GAP_ARMOR_TO_MODEL;
        int modelY1 = top + CONTENT_TOP_Y;
        int modelX2 = modelX1 + MODEL_BOX_W;
        int modelY2 = modelY1 + MODEL_BOX_H;

        // --- Slot plates for REAL equipment slots (6) ---
        // (The actual items are rendered by AbstractContainerScreen via the menu slots.)
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.ARMOR_Y0 + 0 * 22);
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.ARMOR_Y0 + 1 * 22);
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.ARMOR_Y0 + 2 * 22);
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.ARMOR_Y0 + 3 * 22);
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.HANDS_Y0 + 0 * 22);
        drawSlotPlate(gg, left + VillagerInventoryMenu.EQUIP_X, top + VillagerInventoryMenu.HANDS_Y0 + 1 * 22);

        // --- Model box + render ---
        drawEntityBox(gg, modelX1, modelY1, modelX2, modelY2);
        renderEntityModelIfPresent(gg, modelX1, modelY1, modelX2, modelY2, mouseX, mouseY);

        // --- Plates under REAL villager pickup slots (8) ---
        int villInvX0 = left + VillagerInventoryMenu.VILL_INV_X0;
        int villInvY0 = top + VillagerInventoryMenu.VILL_INV_Y0;
        for (int i = 0; i < 8; i++) {
            int col = i % 4;
            int row = i / 4;
            drawSlotPlate(gg, villInvX0 + col * 18, villInvY0 + row * 18);
        }

        // --- Plates under REAL player slots ---
        int pSlotsX0 = left + VillagerInventoryMenu.PLAYER_SLOTS_X0;
        int pSlotsY0 = top + VillagerInventoryMenu.PLAYER_SLOTS_Y0;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotPlate(gg, pSlotsX0 + col * 18, pSlotsY0 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlotPlate(gg, pSlotsX0 + col * 18, pSlotsY0 + 58);
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);
        super.render(gg, mouseX, mouseY, partialTick);

        // Normal tooltips for items in real slots
        super.renderTooltip(gg, mouseX, mouseY);

        // Extra: show equipment slot name when hovered and empty
        renderEmptyEquipSlotTooltip(gg, mouseX, mouseY);
    }

    private void renderEmptyEquipSlotTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        try {
            if (this.hoveredSlot == null) return;
            if (this.hoveredSlot.hasItem()) return;

            String label = null;
            if (this.hoveredSlot instanceof VillagerInventoryMenu.EquipmentProxySlot eq) {
                label = eq.getEmptyLabel();
            } else if (this.hoveredSlot instanceof VillagerInventoryMenu.CombatLoadoutSlot cl) {
                label = cl.getEmptyLabel();
            } else {
                return;
            }
            if (label == null || label.isBlank()) return;

            Font font = Minecraft.getInstance().font;
            gg.renderComponentTooltip(font,
                    List.of(Component.literal(label).withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);

        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);

        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    private static void drawEntityBox(GuiGraphics gg, int x1, int y1, int x2, int y2) {
        gg.fill(x1, y1, x2, y2, BOX_BG);

        gg.fill(x1, y1, x2, y1 + 1, BOX_BORDER);
        gg.fill(x1, y2 - 1, x2, y2, BOX_BORDER);
        gg.fill(x1, y1, x1 + 1, y2, BOX_BORDER);
        gg.fill(x2 - 1, y1, x2, y2, BOX_BORDER);
    }

    private static void drawSlotPlate(GuiGraphics gg, int x, int y) {
        gg.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);

        gg.fill(x, y, x + SLOT_SIZE, y + 1, SLOT_BORDER);
        gg.fill(x, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BORDER);
        gg.fill(x, y, x + 1, y + SLOT_SIZE, SLOT_BORDER);
        gg.fill(x + SLOT_SIZE - 1, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BORDER);
    }

    private void renderEntityModelIfPresent(GuiGraphics gg, int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        try {
            LivingEntity le = this.cachedEntity;
            if (le == null) return;

            int scale = 36;
            float yOffset = 0.0f;

            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    gg,
                    x1 + 4, y1 + 4,
                    x2 - 4, y2 - 4,
                    scale,
                    yOffset,
                    (float) mouseX, (float) mouseY,
                    le
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInventoryScreen entity render failed (soft): {}", t.toString());
        }
    }

    // ---------------------------------------------------------------------
    // Safe reads
    // ---------------------------------------------------------------------

    private static Component safeNameLine(LivingEntity le) {
        try {
            if (le == null) return Component.literal("Entity: (not found)");
            Component dn = le.getDisplayName();
            if (dn == null) return Component.literal("Name: ?");
            return Component.literal("Name: ").append(dn);
        } catch (Throwable t) {
            return Component.literal("Name: ?");
        }
    }
}
