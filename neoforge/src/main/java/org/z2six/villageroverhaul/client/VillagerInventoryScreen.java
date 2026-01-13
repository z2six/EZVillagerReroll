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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;

import java.util.ArrayList;
import java.util.List;

public final class VillagerInventoryScreen extends AbstractContainerScreen<VillagerInventoryMenu> {

    private LivingEntity cachedEntity;

    // Style (match VillagerInfoScreen vibe)
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int BOX_BG = 0xFF101010;
    private static final int BOX_BORDER = 0xFF2E2E2E;

    // Slot visuals
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_GAP = 4;
    private static final int SLOT_BG = 0xFF151515;
    private static final int SLOT_BORDER = 0xFF404040;

    // Model box
    private static final int MODEL_BOX_W = 88;
    private static final int MODEL_BOX_H = 112;

    // Layout gaps
    private static final int GAP_ARMOR_TO_MODEL = 10;
    private static final int GAP_MODEL_TO_RIGHT = 16;
    private static final int GAP_MODEL_TO_VILL_INV_Y = 10;

    // Header + content
    private static final int HEADER_TITLE_Y = PAD + 5;
    private static final int HEADER_NAME_GAP_Y = 12;

    // Content anchors (relative to panel top)
    private static final int CONTENT_TOP_Y = 40;
    private static final int PANEL_RAISE_Y = 8;

    private Button backBtn;

    // Pseudo slot tooltips (armor + hands)
    private final List<SlotBox> pseudoBoxes = new ArrayList<>(16);

    private static final class SlotBox {
        final int x, y, w, h;
        final ItemStack stack;
        final Component emptyLabel;

        SlotBox(int x, int y, int w, int h, ItemStack stack, Component emptyLabel) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.stack = stack == null ? ItemStack.EMPTY : stack;
            this.emptyLabel = emptyLabel == null ? Component.literal("<empty>") : emptyLabel;
        }

        boolean hover(int mx, int my) {
            return mx >= x && mx < (x + w) && my >= y && my < (y + h);
        }
    }

    public VillagerInventoryScreen(VillagerInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title == null ? Component.literal("Villager Inventory") : title);

        // MUST match menu slot coords
        this.imageWidth = VillagerInventoryMenu.IMAGE_W;
        this.imageHeight = VillagerInventoryMenu.IMAGE_H;
    }

    @Override
    protected void init() {
        super.init();

        resolveEntity();

        // keep topPos compact
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
     * IMPORTANT:
     * AbstractContainerScreen draws "title" and "Inventory" labels in gray by default.
     * We want zero vanilla labels, so we override and draw nothing here.
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

        // Panel
        drawPanel(gg, left, top, this.imageWidth, this.imageHeight);

        Font font = Minecraft.getInstance().font;

        // Title (white, only once)
        gg.drawString(font, Component.literal("Villager Inventory"),
                left + PAD, top + HEADER_TITLE_Y, 0xFFFFFFFF, true);

        // Name
        Component nm = safeNameLine(this.cachedEntity);
        gg.drawString(font, nm,
                left + PAD, top + HEADER_TITLE_Y + HEADER_NAME_GAP_Y, 0xFFBFBFBF, false);

        pseudoBoxes.clear();

        Villager vill = (this.cachedEntity instanceof Villager v) ? v : null;

        // =========================================================
        // NEW LAYOUT:
        // LEFT: armor(4) then hands(2)
        // MID:  model
        // BELOW MODEL: villager inventory (real slots)
        // RIGHT: player inventory (real slots)
        // =========================================================

        int armorX = left + PAD;
        int armorY = top + CONTENT_TOP_Y;

        int modelX1 = armorX + SLOT_SIZE + GAP_ARMOR_TO_MODEL;
        int modelY1 = top + CONTENT_TOP_Y;
        int modelX2 = modelX1 + MODEL_BOX_W;
        int modelY2 = modelY1 + MODEL_BOX_H;

        // Hands directly under armor (left side)
        int handsX = armorX;
        int handsY = armorY + 4 * (SLOT_SIZE + SLOT_GAP) + SLOT_GAP;

        // Villager inv (real) below model (menu coords must match)
        // (We use menu constants for slot plates, so these are just for the plates loop)
        int villInvX0 = left + VillagerInventoryMenu.VILL_INV_X0;
        int villInvY0 = top + VillagerInventoryMenu.VILL_INV_Y0;

        // Player inv (real) on the right (menu coords must match)
        int pSlotsX0 = left + VillagerInventoryMenu.PLAYER_SLOTS_X0;
        int pSlotsY0 = top + VillagerInventoryMenu.PLAYER_SLOTS_Y0;

        // ---------------------------------------------------------
        // PSEUDO SLOTS: Armor (with proper empty-label tooltips)
        // ---------------------------------------------------------
        drawPseudoSlot(gg, armorX, armorY + 0 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.HEAD) : ItemStack.EMPTY,
                Component.literal("Helmet").withStyle(ChatFormatting.GRAY));

        drawPseudoSlot(gg, armorX, armorY + 1 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.CHEST) : ItemStack.EMPTY,
                Component.literal("Chestplate").withStyle(ChatFormatting.GRAY));

        drawPseudoSlot(gg, armorX, armorY + 2 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.LEGS) : ItemStack.EMPTY,
                Component.literal("Leggings").withStyle(ChatFormatting.GRAY));

        drawPseudoSlot(gg, armorX, armorY + 3 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.FEET) : ItemStack.EMPTY,
                Component.literal("Boots").withStyle(ChatFormatting.GRAY));

        // ---------------------------------------------------------
        // PSEUDO SLOTS: Hands (below armor)
        // ---------------------------------------------------------
        drawPseudoSlot(gg, handsX, handsY + 0 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.MAINHAND) : ItemStack.EMPTY,
                Component.literal("Main Hand").withStyle(ChatFormatting.GRAY));

        drawPseudoSlot(gg, handsX, handsY + 1 * (SLOT_SIZE + SLOT_GAP),
                vill != null ? safeItemBySlot(vill, EquipmentSlot.OFFHAND) : ItemStack.EMPTY,
                Component.literal("Offhand").withStyle(ChatFormatting.GRAY));

        // ---------------------------------------------------------
        // Model box + render
        // ---------------------------------------------------------
        drawEntityBox(gg, modelX1, modelY1, modelX2, modelY2);
        renderEntityModelIfPresent(gg, modelX1, modelY1, modelX2, modelY2, mouseX, mouseY);

        // ---------------------------------------------------------
        // Plates under REAL villager slots (8) - now below model
        // ---------------------------------------------------------
        for (int i = 0; i < 8; i++) {
            int col = i % 4;
            int row = i / 4;
            int sx = villInvX0 + (col * 18);
            int sy = villInvY0 + (row * 18);
            drawSlotPlate(gg, sx, sy);
        }

        // ---------------------------------------------------------
        // Plates under REAL player slots - now right side of model
        // ---------------------------------------------------------
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = pSlotsX0 + col * 18;
                int sy = pSlotsY0 + row * 18;
                drawSlotPlate(gg, sx, sy);
            }
        }
        for (int col = 0; col < 9; col++) {
            int sx = pSlotsX0 + col * 18;
            int sy = pSlotsY0 + 58;
            drawSlotPlate(gg, sx, sy);
        }

        // NOTE: No "Inventory" labels (per your request). We'll texture later.
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg, mouseX, mouseY, partialTick);
        super.render(gg, mouseX, mouseY, partialTick);

        // Vanilla tooltips for REAL slots
        super.renderTooltip(gg, mouseX, mouseY);

        // Pseudo tooltips (only if not hovering a real slot)
        tryRenderPseudoTooltip(gg, mouseX, mouseY);
    }

    private void tryRenderPseudoTooltip(GuiGraphics gg, int mouseX, int mouseY) {
        try {
            if (this.hoveredSlot != null) return;

            SlotBox hovered = null;
            for (SlotBox sb : pseudoBoxes) {
                if (sb != null && sb.hover(mouseX, mouseY)) {
                    hovered = sb;
                    break;
                }
            }
            if (hovered == null) return;

            Font font = Minecraft.getInstance().font;
            List<Component> lines = new ArrayList<>(2);

            if (hovered.stack.isEmpty()) {
                // FIX #8: show the slot name (Helmet/Chestplate/...) instead of "<empty>"
                lines.add(hovered.emptyLabel);
            } else {
                lines.add(hovered.stack.getHoverName().copy());
            }

            gg.renderComponentTooltip(font, lines, mouseX, mouseY);
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

    private void drawPseudoSlot(GuiGraphics gg, int x, int y, ItemStack st, Component emptyLabel) {
        drawSlotPlate(gg, x, y);

        if (st != null && !st.isEmpty()) {
            gg.renderItem(st, x + 1, y + 1);
            gg.renderItemDecorations(Minecraft.getInstance().font, st, x + 1, y + 1);
        }

        pseudoBoxes.add(new SlotBox(x, y, SLOT_SIZE, SLOT_SIZE, st, emptyLabel));
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

    private static ItemStack safeItemBySlot(LivingEntity le, EquipmentSlot slot) {
        try {
            if (le == null || slot == null) return ItemStack.EMPTY;
            ItemStack st = le.getItemBySlot(slot);
            return st == null ? ItemStack.EMPTY : st;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

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
