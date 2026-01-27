// neoforge/src/main/java/org/z2six/villageroverhaul/client/RespawnAnchorScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.respawn.PacketOpenRespawnAnchorScreen;
import org.z2six.villageroverhaul.network.respawn.PacketRespawnInfoQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * UI shown when the player uses the configured currency item on a respawn anchor.
 */
public final class RespawnAnchorScreen extends Screen {

    private static final int W = 320;
    private static final int H = 200;

    private static final int ROW_H = 22;
    private static final int LIST_PAD = 10;

    private final BlockPos anchorPos;
    private final List<PacketOpenRespawnAnchorScreen.Entry> entries;

    private int scroll = 0;

    private Button closeBtn;

    public RespawnAnchorScreen(BlockPos anchorPos, List<PacketOpenRespawnAnchorScreen.Entry> entries) {
        super(Component.literal("Respawn villager"));
        this.anchorPos = anchorPos == null ? BlockPos.ZERO : anchorPos;
        this.entries = entries == null ? List.of() : new ArrayList<>(entries);
    }

    @Override
    protected void init() {
        super.init();
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        closeBtn = Button.builder(Component.literal("Close"), b -> Minecraft.getInstance().setScreen(null))
                .pos(left + W - 58 - 10, top + H - 20 - 10)
                .size(58, 20)
                .build();
        addRenderableWidget(closeBtn);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {
        }
        int left = (this.width - W) / 2;
        int top = (this.height - H) / 2;

        gg.fill(left, top, left + W, top + H, 0xCC0B0B0B);
        gg.drawString(this.font, this.title, left + 10, top + 10, 0xFFFFFFFF, false);

        int listX = left + LIST_PAD;
        int listY = top + 30;
        int listW = W - LIST_PAD * 2;
        int listH = H - 30 - 40;

        gg.fill(listX, listY, listX + listW, listY + listH, 0xFF101010);

        int visible = Math.max(1, listH / ROW_H);
        int total = entries.size();
        int maxScroll = Math.max(0, total - visible);
        scroll = Mth.clamp(scroll, 0, maxScroll);

        int start = scroll;
        int end = Math.min(total, start + visible);

        boolean tooltip = false;
        ItemStack currencyIcon = ClientCostIcon.costIcon();

        for (int i = start; i < end; i++) {
            int row = i - start;
            int y = listY + row * ROW_H;

            PacketOpenRespawnAnchorScreen.Entry e = entries.get(i);
            if (e == null || e.respawnId() == null) continue;

            int bg = (mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H)
                    ? 0xFF1A1A1A
                    : 0xFF141414;
            gg.fill(listX + 1, y + 1, listX + listW - 1, y + ROW_H - 1, bg);

            // name
            String name = parseName(e.nameJson());
            gg.drawString(this.font, name, listX + 6, y + 7, 0xFFFFFFFF, false);

            // profession
            String prof = shortenProf(e.professionId());
            gg.drawString(this.font, prof, listX + 140, y + 7, 0xFFAAAAAA, false);

            // cost + icon
            int cost = Math.max(0, e.respawnCost());
            String costStr = String.valueOf(cost);
            int costX = listX + listW - 6 - 16 - 3 - this.font.width(costStr);
            gg.drawString(this.font, costStr, costX, y + 7, 0xFF66FF66, false);
            gg.renderItem(currencyIcon, costX + this.font.width(costStr) + 3, y + 3);

            if (e.deaths() > 0) {
                gg.drawString(this.font, "Died " + e.deaths() + "x", listX + 220, y + 7, 0xFF777777, false);
            }
        }

        // simple scroll hint
        if (total == 0) {
            gg.drawString(this.font, Component.literal("(no snapshots yet)").withStyle(ChatFormatting.GRAY),
                    listX + 6, listY + 8, 0xFFFFFFFF, false);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    /*
     * We already call super.renderBackground(...) manually in our render method, so we must NOOP this
     * to prevent NeoForge/vanilla background blur from being drawn on top of our custom panel.
     */
    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (background is rendered explicitly in render())
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int left = (this.width - W) / 2;
            int top = (this.height - H) / 2;
            int listX = left + LIST_PAD;
            int listY = top + 30;
            int listW = W - LIST_PAD * 2;
            int listH = H - 30 - 40;

            if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
                int row = (int) ((mouseY - listY) / ROW_H);
                int idx = scroll + row;
                if (idx >= 0 && idx < entries.size()) {
                    PacketOpenRespawnAnchorScreen.Entry e = entries.get(idx);
                    if (e != null && e.respawnId() != null) {
                        try {
                            ClientNetwork.sendToServer(new PacketRespawnInfoQuery(
                                    anchorPos.getX(), anchorPos.getY(), anchorPos.getZ(),
                                    e.respawnId().getMostSignificantBits(),
                                    e.respawnId().getLeastSignificantBits()
                            ));
                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed to send respawn info query", t);
                        }
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0.0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        int delta = (int) Math.signum(scrollY);
        scroll -= delta;
        return true;
    }

    private static String parseName(String json) {
        try {
            if (json == null || json.isEmpty()) return "Villager";
            var mc = Minecraft.getInstance();
            var level = mc == null ? null : mc.level;
            var provider = level == null ? null : level.registryAccess();
            Component c = provider == null
                    ? null
                    : Component.Serializer.fromJson(json, provider);
            if (c == null) return "Villager";
            return c.getString();
        } catch (Throwable ignored) {
            return "Villager";
        }
    }

    private static String shortenProf(String id) {
        try {
            if (id == null || id.isEmpty()) return "(unknown)";
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return id;
            return rl.getPath();
        } catch (Throwable ignored) {
            return id == null ? "(unknown)" : id;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
