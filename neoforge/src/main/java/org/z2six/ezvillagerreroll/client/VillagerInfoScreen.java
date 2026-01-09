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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.ClientVillagerStatsCache;
import org.z2six.ezvillagerreroll.network.PacketVillagerStatsData;
import org.z2six.ezvillagerreroll.network.PacketVillagerStatsQuery;
import org.z2six.ezvillagerreroll.server.VillagerStatsService;

import java.util.List;

public final class VillagerInfoScreen extends Screen {

    private static final long STATS_QUERY_DEBOUNCE_MS = 750;

    private final MerchantScreen parent;
    private final int villagerEntityId;

    private LivingEntity cachedEntity;

    private boolean hasStats = false;
    private boolean statsUnavailable = false;

    private int generosity = 0;
    private int timeliness = 0;
    private int intellect  = 0;
    private int hoarder    = 0;
    private int ambitious  = 0;

    private long lastStatsQueryMs = 0L;

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

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null && mc.player.getId() == villagerEntityId) {
                EZVillagerReroll.LOG().warn("[EZVR] VillagerInfoScreen opened with player entityId={} (expected villager). Trader id resolution may be wrong.",
                        villagerEntityId);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Keep this NO-OP.
     * We apply the blur/background once in render(), then draw our panel above it.
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        resolveEntity();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );

        // Kick initial request immediately
        trySendStatsQuery(false);
        tryApplyStatsFromCache();
    }

    @Override
    public void tick() {
        super.tick();

        resolveEntity();
        tryApplyStatsFromCache();

        if (!hasStats && !statsUnavailable) {
            trySendStatsQuery(true);
        }
    }

    private void tryApplyStatsFromCache() {
        try {
            PacketVillagerStatsData snap = ClientVillagerStatsCache.get(this.villagerEntityId);
            if (snap == null) return;

            if (!snap.ok()) {
                this.statsUnavailable = true;
                this.hasStats = false;
                return;
            }

            this.generosity = VillagerStatsService.clampPoints(snap.generosity());
            this.timeliness = VillagerStatsService.clampPoints(snap.timeliness());
            this.intellect  = VillagerStatsService.clampPoints(snap.intellect());
            this.hoarder    = VillagerStatsService.clampPoints(snap.hoarder());
            this.ambitious  = VillagerStatsService.clampPoints(snap.ambitious());

            this.hasStats = true;
            this.statsUnavailable = false;

        } catch (Throwable ignored) {}
    }

    private void trySendStatsQuery(boolean debounced) {
        try {
            long now = System.currentTimeMillis();
            if (debounced && (now - lastStatsQueryMs) < STATS_QUERY_DEBOUNCE_MS) return;
            lastStatsQueryMs = now;

            ClientNetwork.sendToServer(new PacketVillagerStatsQuery(this.villagerEntityId));
            EZVillagerReroll.LOG().debug("[EZVR] VillagerInfoScreen sent PacketVillagerStatsQuery(entityId={})", this.villagerEntityId);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] VillagerInfoScreen.trySendStatsQuery failed (soft): {}", t.toString());
        }
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
        // Apply blur/background ONCE
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;

        gg.drawString(font, Component.literal("Villager Info"), left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        int boxLeft = left + PAD;
        int boxTop = top + 28;
        int boxRight = boxLeft + ENTITY_BOX_W;
        int boxBottom = boxTop + ENTITY_BOX_H;

        drawEntityBox(gg, boxLeft, boxTop, boxRight, boxBottom);

        int textX = boxRight + PAD;
        int textY = top + 34;

        LivingEntity le = this.cachedEntity;
        if (le == null) {
            gg.drawString(font, Component.literal("Entity: (not found)"), textX, textY, 0xFFFF7777, false);
            gg.drawString(font, Component.literal("Id: " + this.villagerEntityId), textX, textY + 12, 0xFFBFBFBF, false);
        } else {
            Component name = safeName(le);
            gg.drawString(font, Component.literal("Name: ").append(name), textX, textY, 0xFFFFFFFF, false);

            Component prof = safeProfession(le);
            gg.drawString(font, Component.literal("Profession: ").append(prof), textX, textY + 12, 0xFFFFFFFF, false);

            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) {
                gg.drawString(font, Component.literal("Type: " + typeId), textX, textY + 24, 0xFFBFBFBF, false);
            }
        }

        renderVillagerModel(gg, boxLeft, boxTop, boxRight, boxBottom, mouseX, mouseY);

        int barsX = boxRight + PAD;
        int barsY = top + 78;

        renderStatBar(gg, font, "Generosity", this.hasStats ? this.generosity : null,
                barsX, barsY, BAR_W, BAR_H, C_GENEROSITY, mouseX, mouseY);

        renderStatBar(gg, font, "Timeliness", this.hasStats ? this.timeliness : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 1, BAR_W, BAR_H, C_TIMELINESS, mouseX, mouseY);

        renderStatBar(gg, font, "Intellect", this.hasStats ? this.intellect : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 2, BAR_W, BAR_H, C_INTELLECT, mouseX, mouseY);

        renderStatBar(gg, font, "Hoarder", this.hasStats ? this.hoarder : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 3, BAR_W, BAR_H, C_HOARDER, mouseX, mouseY);

        renderStatBar(gg, font, "Ambitious", this.hasStats ? this.ambitious : null,
                barsX, barsY + (BAR_H + BAR_GAP) * 4, BAR_W, BAR_H, C_AMBITIOUS, mouseX, mouseY);

        if (!this.hasStats) {
            if (this.statsUnavailable) {
                gg.drawString(font, Component.literal("Stats: unavailable"), barsX, barsY + (BAR_H + BAR_GAP) * 5 + 2, 0xFFFF7777, false);
            } else {
                gg.drawString(font, Component.literal("Stats: syncing…"), barsX, barsY + (BAR_H + BAR_GAP) * 5 + 2, 0xFFAAAAAA, false);
            }
        }

        // Render widgets (Back button)
        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        try {
            gg.fill(x, y, x + w, y + h, PANEL_BG);

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

            int scale = 48;
            float yOffset = 0.0f;

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
                    return Component.translatable("entity.minecraft.villager." + key.getPath());
                }
                return Component.literal("Villager");
            }

            ResourceLocation typeId = safeEntityTypeId(le);
            if (typeId != null) return Component.literal(typeId.toString());
            return Component.literal("Unknown");
        } catch (Throwable t) {
            return Component.literal("Unknown");
        }
    }

    private static void renderStatBar(
            GuiGraphics gg,
            Font font,
            String label,
            Integer valueOrNull,
            int x, int y, int w, int h,
            int color,
            int mouseX, int mouseY
    ) {
        try {
            gg.fill(x, y, x + w, y + h, BAR_BG);

            gg.fill(x, y, x + w, y + 1, BAR_OUTLINE);
            gg.fill(x, y + h - 1, x + w, y + h, BAR_OUTLINE);
            gg.fill(x, y, x + 1, y + h, BAR_OUTLINE);
            gg.fill(x + w - 1, y, x + w, y + h, BAR_OUTLINE);

            int cx = x + w / 2;
            gg.fill(cx, y + 2, cx + 1, y + h - 2, BAR_CENTER);

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
                }
            } else {
                gg.drawString(font, "?", x + w - 10, y + 2, 0xFF777777, false);
            }

            boolean hover = mouseX >= x && mouseX < (x + w) && mouseY >= y && mouseY < (y + h);
            if (hover) {
                Component line1 = Component.literal(label);
                Component line2 = Component.literal(valueOrNull == null ? "Value: (syncing…)" : ("Value: " + valueOrNull));
                gg.renderComponentTooltip(font, List.of(line1, line2), mouseX, mouseY);
            }
        } catch (Throwable ignored) {}
    }
}
