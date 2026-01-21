// neoforge/src/main/java/org/z2six/villageroverhaul/client/PatrolRouteListScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolRouteDelete;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolRouteRename;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolRouteStart;
import org.z2six.villageroverhaul.network.patrol.PacketPatrolRoutesData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PatrolRouteListScreen extends Screen {

    private static final int ROW_H = 20;

    private final Screen parent;
    private final int villagerEntityId;
    private final ArrayList<PacketPatrolRoutesData.RouteEntry> routes;

    private int selected = -1;

    private int listX, listY, listW, listH;
    private int scrollRow = 0;
    private int visibleRows = 1;
    private SimpleScrollBar scrollBar;

    private Button startBtn;
    private Button renameBtn;
    private Button deleteBtn;

    public PatrolRouteListScreen(Screen parent, int villagerEntityId, List<PacketPatrolRoutesData.RouteEntry> routes) {
        super(Component.literal("Patrol Routes"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
        this.routes = new ArrayList<>(routes == null ? List.of() : routes);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        listW = 260;
        listH = 120;
        listX = cx - listW / 2;
        listY = cy - 70;

        visibleRows = Math.max(1, listH / ROW_H);
        scrollBar = new SimpleScrollBar(listX + listW + 2, listY, 8, listH);

        int bw = 84;
        int bh = 20;
        int by = listY + listH + 10;

        startBtn = this.addRenderableWidget(Button.builder(Component.literal("Start"), b -> startSelected())
                .bounds(cx - bw - 90, by, bw, bh).build());
        renameBtn = this.addRenderableWidget(Button.builder(Component.literal("Rename"), b -> renameSelected())
                .bounds(cx - bw / 2, by, bw, bh).build());
        deleteBtn = this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected())
                .bounds(cx + 90, by, bw, bh).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> closeToParent())
                .bounds(cx - 110, by + 25, 220, 20).build());

        updateButtons();
    }

    private void updateButtons() {
        boolean ok = selected >= 0 && selected < routes.size();
        if (startBtn != null) startBtn.active = ok;
        if (renameBtn != null) renameBtn.active = ok;
        if (deleteBtn != null) deleteBtn.active = ok;
    }

    private void startSelected() {
        try {
            if (selected < 0 || selected >= routes.size()) return;
            PacketPatrolRoutesData.RouteEntry e = routes.get(selected);
            if (e == null || e.routeId() == null) return;
            ClientNetwork.sendToServer(new PacketPatrolRouteStart(villagerEntityId, e.routeId()));
            toast("Patrol started.", ChatFormatting.YELLOW);
            closeAll();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolRouteListScreen.startSelected failed", t);
        }
    }

    private void deleteSelected() {
        try {
            if (selected < 0 || selected >= routes.size()) return;
            PacketPatrolRoutesData.RouteEntry e = routes.get(selected);
            if (e == null || e.routeId() == null) return;

            ClientNetwork.sendToServer(new PacketPatrolRouteDelete(villagerEntityId, e.routeId()));
            routes.remove(selected);
            if (selected >= routes.size()) selected = routes.size() - 1;
            clampScroll();
            updateButtons();
            toast("Route deleted.", ChatFormatting.RED);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolRouteListScreen.deleteSelected failed", t);
        }
    }

    private void renameSelected() {
        try {
            if (selected < 0 || selected >= routes.size()) return;
            PacketPatrolRoutesData.RouteEntry e = routes.get(selected);
            if (e == null || e.routeId() == null) return;
            Minecraft.getInstance().setScreen(new PatrolRouteRenameScreen(this, villagerEntityId, e.routeId(), e.name()));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] PatrolRouteListScreen.renameSelected failed", t);
        }
    }

    void applyRenameLocal(UUID routeId, String newName) {
        try {
            if (routeId == null) return;
            for (int i = 0; i < routes.size(); i++) {
                PacketPatrolRoutesData.RouteEntry e = routes.get(i);
                if (e == null || e.routeId() == null) continue;
                if (routeId.equals(e.routeId())) {
                    routes.set(i, new PacketPatrolRoutesData.RouteEntry(routeId, newName, e.typeId(), e.waypointCount()));
                    break;
                }
            }
        } catch (Throwable ignored) {}
    }

    private void toast(String msg, ChatFormatting fmt) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg).withStyle(fmt), true);
            }
        } catch (Throwable ignored) {}
    }

    private void closeToParent() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(parent);
        } catch (Throwable ignored) {}
    }

    private void closeAll() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // Custom background fill (avoid NeoForge menu blur).
        gg.fill(0, 0, this.width, this.height, 0xC0101010);

        int cx = this.width / 2;
        gg.drawCenteredString(this.font, "Saved patrol routes", cx, listY - 14, 0xFFFFFFFF);

        gg.fill(listX, listY, listX + listW, listY + listH, 0x80101010);

        if (routes.isEmpty()) {
            gg.drawCenteredString(this.font, "(No routes saved)", cx, listY + 50, 0xFFAAAAAA);
        } else {
            int maxRow = Math.max(0, routes.size() - visibleRows);
            scrollRow = Math.max(0, Math.min(maxRow, scrollRow));

            int y = listY;
            int startIdx = scrollRow;
            int endIdx = Math.min(routes.size(), startIdx + visibleRows);

            for (int i = startIdx; i < endIdx; i++) {
                PacketPatrolRoutesData.RouteEntry e = routes.get(i);
                int rowY = y + (i - startIdx) * ROW_H;

                int bg = (i == selected) ? 0xA0333333 : 0x60202020;
                gg.fill(listX + 2, rowY, listX + listW - 2, rowY + ROW_H - 1, bg);

                String name = (e == null || e.name() == null || e.name().isBlank()) ? ("Route " + (i + 1)) : e.name();
                String type = (e == null || e.typeId() == null) ? "" : e.typeId();
                int wc = e == null ? 0 : e.waypointCount();

                gg.drawString(this.font, name, listX + 6, rowY + 6, 0xFFFFFFFF, false);
                String meta = (type.isBlank() ? "" : ("(" + type + ") ")) + wc + " wp";
                gg.drawString(this.font, meta,
                        listX + listW - 6 - this.font.width(meta), rowY + 6, 0xFFAAAAAA, false);
            }

            scrollBar.render(gg, scrollRow, maxRow, routes.size(), visibleRows);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // NO-OP: Screen.render() would otherwise draw NeoForge's blurred menu background
        // over our custom list rendering (but still under widgets).
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseClicked(mx, my, button)) return true;

            if (button == 0 && mx >= listX && mx < (listX + listW) && my >= listY && my < (listY + listH)) {
                int row = (int) ((my - listY) / ROW_H);
                int idx = scrollRow + row;
                if (idx >= 0 && idx < routes.size()) {
                    selected = idx;
                    updateButtons();
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (scrollBar != null && scrollBar.mouseReleased(mx, my, button)) return true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        try {
            if (scrollBar != null && scrollBar.mouseDragged(mx, my, button, dragX, dragY)) {
                int maxRow = Math.max(0, routes.size() - visibleRows);
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxRow);
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        try {
            if (routes.isEmpty()) return super.mouseScrolled(mx, my, dx, dy);
            int maxRow = Math.max(0, routes.size() - visibleRows);
            scrollRow -= (int) Math.signum(dy);
            scrollRow = Math.max(0, Math.min(maxRow, scrollRow));
            return true;
        } catch (Throwable ignored) {}
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void clampScroll() {
        int maxRow = Math.max(0, routes.size() - visibleRows);
        scrollRow = Math.max(0, Math.min(maxRow, scrollRow));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Minimal draggable scrollbar helper (no dependencies)
    private static final class SimpleScrollBar {
        private final int x, y, w, h;

        private boolean dragging = false;
        private double lastMouseY = 0;

        SimpleScrollBar(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = Math.max(1, h);
        }

        void render(GuiGraphics gg, int scrollRow, int maxRow, int totalRows, int visibleRows) {
            try {
                gg.fill(x, y, x + w, y + h, 0x80000000);

                if (totalRows <= visibleRows) {
                    gg.fill(x, y, x + w, y + h, 0xA0FFFFFF);
                    return;
                }

                int thumbH = Math.max(12, (int) Math.round((h * (visibleRows / (double) totalRows))));
                int travel = Math.max(1, h - thumbH);

                double t = (maxRow <= 0) ? 0.0 : (scrollRow / (double) maxRow);
                int thumbY = y + (int) Math.round(travel * t);

                gg.fill(x, thumbY, x + w, thumbY + thumbH, dragging ? 0xE0FFFFFF : 0xC0FFFFFF);
            } catch (Throwable ignored) {}
        }

        private boolean isOver(double mx, double my) {
            return mx >= x && mx < (x + w) && my >= y && my < (y + h);
        }

        boolean mouseClicked(double mx, double my, int button) {
            try {
                if (button != 0) return false;
                if (!isOver(mx, my)) return false;
                dragging = true;
                lastMouseY = my;
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        boolean mouseReleased(double mx, double my, int button) {
            if (button != 0) return false;
            if (!dragging) return false;
            dragging = false;
            return true;
        }

        boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
            if (!dragging || button != 0) return false;
            lastMouseY = my;
            return true;
        }

        int getScrollRowFromThumb(int currentRow, int maxRow) {
            try {
                if (!dragging) return currentRow;
                if (maxRow <= 0) return 0;

                double rel = (lastMouseY - y) / (double) h;
                if (rel < 0) rel = 0;
                if (rel > 1) rel = 1;

                int next = (int) Math.round(maxRow * rel);
                if (next < 0) next = 0;
                if (next > maxRow) next = maxRow;
                return next;
            } catch (Throwable ignored) {
                return currentRow;
            }
        }
    }
}
