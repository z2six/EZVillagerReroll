// VillagerInfoScreen.java
// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/VillagerInfoScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.server.VillagerStatsService;

import java.util.List;

public final class VillagerInfoScreen extends Screen {

    private final MerchantScreen parent;
    private final int villagerEntityId;

    private LivingEntity cachedEntity;

    private boolean hasStats = false;
    private int generosity = 0;
    private int timeliness = 0;
    private int intellect  = 0;
    private int hoarder    = 0;
    private int ambitious  = 0;

    // Layout
    private static final int PANEL_W = 292;
    private static final int PANEL_H = 190;

    private static final int PAD = 10;

    // Entity box (left)
    private static final int ENTITY_BOX_W = 120;
    private static final int ENTITY_BOX_H = 140;

    // Bars (right)
    private static final int BAR_W = 140;
    private static final int BAR_H = 12;
    private static final int BAR_GAP = 10;

    // Colors (ARGB)
    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    private static final int BAR_BG = 0xFF151515;
    private static final int BAR_OUTLINE = 0xFF404040;
    private static final int BAR_CENTER = 0xFFAAAAAA;

    private static final int C_GENEROSITY = 0xFF42D16C; // green
    private static final int C_TIMELINESS = 0xFF2FC7FF; // cyan
    private static final int C_INTELLECT  = 0xFFB26BFF; // purple
    private static final int C_HOARDER    = 0xFFFFB347; // orange
    private static final int C_AMBITIOUS  = 0xFFFF4B4B; // red

    public VillagerInfoScreen(MerchantScreen parent, int villagerEntityId) {
        super(Component.literal("Villager Info"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    protected void init() {
        super.init();

        // Resolve entity once early
        resolveEntity();

        // Try read stats if present on client (may not be synced; we still try)
        tryReadStatsFromEntity();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );
    }

    @Override
    public void tick() {
        super.tick();
        resolveEntity();
        if (!hasStats) tryReadStatsFromEntity();
    }

    private void resolveEntity() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) return;

            Entity e = mc.level.getEntity(this.villagerEntityId);
            if (e instanceof LivingEntity le) {
                this.cachedEntity = le;
            }
        } catch (Throwable t) {
            // soft
        }
    }

    private void tryReadStatsFromEntity() {
        try {
            LivingEntity le = this.cachedEntity;
            if (le == null) return;

            CompoundTag pd = le.getPersistentData();
            if (pd == null) return;

            if (!pd.contains(VillagerStatsService.TAG_ROOT, CompoundTag.TAG_COMPOUND)) return;

            CompoundTag root = pd.getCompound(VillagerStatsService.TAG_ROOT);
            if (root == null) return;

            // All keys must exist
            if (!root.contains(VillagerStatsService.K_GENEROSITY)) return;
            if (!root.contains(VillagerStatsService.K_TIMELINESS)) return;
            if (!root.contains(VillagerStatsService.K_INTELLECT)) return;
            if (!root.contains(VillagerStatsService.K_HOARDER)) return;
            if (!root.contains(VillagerStatsService.K_AMBITIOUS)) return;

            this.generosity = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_GENEROSITY));
            this.timeliness = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_TIMELINESS));
            this.intellect  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_INTELLECT));
            this.hoarder    = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_HOARDER));
            this.ambitious  = VillagerStatsService.clampPoints(root.getInt(VillagerStatsService.K_AMBITIOUS));

            this.hasStats = true;

        } catch (Throwable t) {
            // soft
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            if (this.parent != null) {
                mc.setScreen(this.parent);
            } else {
                mc.setScreen(null);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] VillagerInfoScreen.onClose failed", t);
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(gg);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        // Panel
        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        // Title
        Font font = Minecraft.getInstance().font;
        Component title = Component.literal("Villager Info");
        gg.drawString(font, title, left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        // Entity area
        int boxLeft = left + PAD;
        int boxTop = top + 28;
        int boxRight = boxLeft + ENTITY_BOX_W;
        int boxBottom = boxTop + ENTITY_BOX_H;

        drawEntityBox(gg, boxLeft, boxTop, boxRight, boxBottom);

        // Text block (top-right)
        int textX = boxRight + PAD;
        int textY = top + 34;

        LivingEntity le = this.cachedEntity;
        if (le == null) {
            gg.drawString(font, Component.literal("Entity: (not found)"), textX, textY, 0xFFFF7777, false);
        } else {
            // Name (supports mods that set a custom name)
            Component name = safeName(le);
            gg.drawString(font, Component.literal("Name: ").append(name), textX, textY, 0xFFFFFFFF, false);

            // Profession
            Component prof = safeProfession(le);
            gg.drawString(font, Component.literal("Profession: ").append(prof), textX, textY + 12, 0xFFFFFFFF, false);

            // Minimal extra info (type/id)
            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) {
                gg.drawString(font, Component.literal("Type: " + typeId), textX, textY + 24, 0xFFBFBFBF, false);
            }
        }

        // Render villager 3D model
        renderVillagerModel(gg, boxLeft, boxTop, boxRight, boxBottom, mouseX, mouseY);

        // Bars
        int barsX = boxRight + PAD;
        int barsY = top + 78;

        boolean hoveredAny = false;

        hoveredAny |= renderStatBar(gg, font, "Generosity", this.hasStats ? this.generosity : null,
                barsX, barsY, BAR_W, BAR_H, C_GENEROSITY, mouseX, mouseY);

        hoveredAny |= renderStatBar(gg, font, "Timeliness", this.hasStats ? this.timeliness : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 1, BAR_W, BAR_H, C_TIMELINESS, mouseX, mouseY);

        hoveredAny |= renderStatBar(gg, font, "Intellect", this.hasStats ? this.intellect : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 2, BAR_W, BAR_H, C_INTELLECT, mouseX, mouseY);

        hoveredAny |= renderStatBar(gg, font, "Hoarder", this.hasStats ? this.hoarder : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 3, BAR_W, BAR_H, C_HOARDER, mouseX, mouseY);

        hoveredAny |= renderStatBar(gg, font, "Ambitious", this.hasStats ? this.ambitious : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 4, BAR_W, BAR_H, C_AMBITIOUS, mouseX, mouseY);

        // Small hint if stats aren't present client-side
        if (!this.hasStats) {
            gg.drawString(font, Component.literal("Stats: syncing…"), barsX, barsY + (BAR_H + BAR_GAP) * 5 + 2, 0xFFAAAAAA, false);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        try {
            gg.fill(x, y, x + w, y + h, PANEL_BG);

            // Outline
            gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
            gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
            gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
            gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
        } catch (Throwable ignored) {}
    }

    private static void drawEntityBox(GuiGraphics gg, int x1, int y1, int x2, int y2) {
        try {
            gg.fill(x1, y1, x2, y2, 0xFF101010);

            gg.fill(x1, y1, x2, y1 + 1, 0xFF2E2E2E);
            gg.fill(x1, y2 - 1, x2, y2, 0xFF2E2E2E);
            gg.fill(x1, y1, x1 + 1, y2, 0xFF2E2E2E);
            gg.fill(x2 - 1, y1, x2, y2, 0xFF2E2E2E);
        } catch (Throwable ignored) {}
    }

    private void renderVillagerModel(GuiGraphics gg, int boxLeft, int boxTop, int boxRight, int boxBottom, int mouseX, int mouseY) {
        try {
            LivingEntity le = this.cachedEntity;
            if (le == null) return;

            // Signature (1.21.x):
            // InventoryScreen.renderEntityInInventoryFollowsMouse(GuiGraphics, int x1, int y1, int x2, int y2, int scale, float yOffset, float mouseX, float mouseY, LivingEntity)
            // :contentReference[oaicite:0]{index=0}
            int scale = 48;
            float yOffset = 0.0f;

            // Nudge box slightly so it feels centered
            int x1 = boxLeft + 6;
            int y1 = boxTop + 6;
            int x2 = boxRight - 6;
            int y2 = boxBottom - 6;

            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    gg,
                    x1, y1, x2, y2,
                    scale,
                    yOffset,
                    (float) mouseX, (float) mouseY,
                    le
            );
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] VillagerInfoScreen entity render failed (soft): {}", t.toString());
        }
    }

    private static Component safeName(LivingEntity le) {
        try {
            if (le == null) return Component.literal("?");
            // display name includes custom name (mods/nametag) and fallback
            return le.getDisplayName();
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }

    private static ResourceLocation safeEntityTypeId(LivingEntity le) {
        try {
            if (le == null) return null;
            return BuiltInRegistries.ENTITY_TYPE.getKey(le.getType());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Component safeProfession(LivingEntity le) {
        try {
            if (le instanceof WanderingTrader) {
                return Component.translatable("entity.minecraft.wandering_trader");
            }

            if (le instanceof Villager v) {
                var prof = v.getVillagerData().getProfession();
                ResourceLocation key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(prof);
                if (key != null) {
                    // Vanilla language keys are "entity.minecraft.villager.<profession>"
                    return Component.translatable("entity.minecraft.villager." + key.getPath());
                }
                return Component.literal("Villager");
            }

            // Generic fallback
            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) return Component.literal(typeId.toString());
            return Component.literal("Unknown");
        } catch (Throwable t) {
            return Component.literal("Unknown");
        }
    }

    private static boolean renderStatBar(
            GuiGraphics gg,
            Font font,
            String label,
            Integer valueOrNull,
            int x, int y, int w, int h,
            int color,
            int mouseX, int mouseY
    ) {
        try {
            // Background + outline
            gg.fill(x, y, x + w, y + h, BAR_BG);

            gg.fill(x, y, x + w, y + 1, BAR_OUTLINE);
            gg.fill(x, y + h - 1, x + w, y + h, BAR_OUTLINE);
            gg.fill(x, y, x + 1, y + h, BAR_OUTLINE);
            gg.fill(x + w - 1, y, x + w, y + h, BAR_OUTLINE);

            // Center line
            int cx = x + w / 2;
            gg.fill(cx, y + 2, cx + 1, y + h - 2, BAR_CENTER);

            // Fill
            if (valueOrNull != null) {
                int v = Mth.clamp(valueOrNull, VillagerStatsService.POINTS_MIN, VillagerStatsService.POINTS_MAX);

                float norm = v / 100.0f; // -1..+1
                int half = w / 2;

                if (norm > 0.0f) {
                    int fillW = (int) (norm * half);
                    gg.fill(cx + 1, y + 2, cx + 1 + fillW, y + h - 2, color);
                } else if (norm < 0.0f) {
                    int fillW = (int) (Math.abs(norm) * half);
                    gg.fill(cx - fillW, y + 2, cx, y + h - 2, color);
                } else {
                    // zero = no fill
                }
            } else {
                // Not synced: draw a subtle "?" marker
                gg.drawString(font, "?", x + w - 10, y + 2, 0xFF777777, false);
            }

            // Hover tooltip
            boolean hover = mouseX >= x && mouseX < (x + w) && mouseY >= y && mouseY < (y + h);
            if (hover) {
                Component line1 = Component.literal(label);
                Component line2 = Component.literal(valueOrNull == null ? "Value: (syncing…)" : ("Value: " + valueOrNull));
                gg.renderComponentTooltip(font, List.of(line1, line2), mouseX, mouseY);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
