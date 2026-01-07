// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/SearchCatalogScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketStartAutoSearch;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogQuery;

import java.util.*;

/**
 * Search + select items to request.
 * This is an overlay screen that can return to the MerchantScreen on cancel.
 */
public final class SearchCatalogScreen extends Screen {

    private final MerchantScreen parent;
    private int villagerEntityId;

    private final List<ItemStack> catalog = new ArrayList<>();
    private final List<ItemStack> filtered = new ArrayList<>();

    // Selection uses our key function to remain stable even if stacks are copied
    private final Set<String> selectedKeys = new HashSet<>();
    private final List<ItemStack> selectedStacks = new ArrayList<>();

    private EditBox searchBox;
    private Button btnCancel;
    private Button btnRequest;

    private boolean awaitingServerData = true;

    // Grid UI
    private int gridX, gridY, gridW, gridH;
    private int scrollRow = 0;
    private int maxScrollRow = 0;

    private static final int SLOT = 18;
    private static final int PAD = 4;

    public SearchCatalogScreen(MerchantScreen parent) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = parent;
        this.villagerEntityId = -1;
    }

    public SearchCatalogScreen(int villagerEntityId, List<ItemStack> catalog) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = null;
        this.villagerEntityId = villagerEntityId;
        if (catalog != null) this.catalog.addAll(catalog);
        this.awaitingServerData = false;
        rebuildFiltered();
    }

    public void applyCatalogFromServer(int villagerEntityId, List<ItemStack> items) {
        try {
            this.villagerEntityId = villagerEntityId;
            this.catalog.clear();
            if (items != null) this.catalog.addAll(items);
            this.awaitingServerData = false;

            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen received catalog: villagerEntityId={} items={}",
                    villagerEntityId, this.catalog.size());

            rebuildFiltered();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer failed", t);
        }
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;

            searchBox = new EditBox(this.font, cx - 110, 18, 220, 18, Component.translatable("ezvr.catalog.search"));
            searchBox.setMaxLength(64);
            searchBox.setValue("");
            searchBox.setResponder(s -> {
                try {
                    rebuildFiltered();
                } catch (Throwable ignored) {}
            });
            addRenderableWidget(searchBox);

            // Grid bounds
            gridX = cx - 110;
            gridY = 78;
            gridW = 220;
            gridH = this.height - 78 - 44; // leave bottom area for buttons

            btnCancel = Button.builder(Component.translatable("ezvr.catalog.cancel"), b -> {
                        try {
                            // Cancel should return to MerchantScreen (if still open) and NOT start search.
                            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen: Cancel clicked (villagerEntityId={})", villagerEntityId);
                            returnToParentOrClose();
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen: cancel failed", t);
                            safeCloseToNull();
                        }
                    })
                    .pos(cx - 110, this.height - 28)
                    .size(100, 20)
                    .build();

            btnRequest = Button.builder(Component.translatable("ezvr.catalog.request"), b -> {
                        try {
                            if (villagerEntityId < 0) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked but villagerEntityId not set yet; ignoring.");
                                return;
                            }

                            // Build request list from selected keys by taking representative stacks
                            List<ItemStack> req = new ArrayList<>();
                            for (ItemStack s : selectedStacks) {
                                if (s == null || s.isEmpty()) continue;
                                req.add(s.copy());
                                if (req.size() >= 512) break;
                            }

                            if (req.isEmpty()) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked but no selection; ignoring.");
                                return;
                            }

                            ClientNetwork.sendToServer(new PacketStartAutoSearch(villagerEntityId, req));
                            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen: sent PacketStartAutoSearch villagerEntityId={} items={}",
                                    villagerEntityId, req.size());
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen: request send failed", t);
                        }

                        // After Request: close catalog UI and close merchant container (per your steps)
                        safeCloseToNull();
                        tryCloseMerchantIfOpen();
                    })
                    .pos(cx + 10, this.height - 28)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnRequest);

            // If we were opened with a parent, ensure the server query is sent (in case caller didn’t)
            try {
                if (awaitingServerData && villagerEntityId >= 0) {
                    EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.init: awaiting data; villagerEntityId={}", villagerEntityId);
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.init failed", t);
        }
    }

    private void rebuildFiltered() {
        try {
            filtered.clear();

            String q = "";
            try {
                q = (searchBox == null) ? "" : searchBox.getValue();
            } catch (Throwable ignored) {}

            String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

            for (ItemStack s : catalog) {
                if (s == null || s.isEmpty()) continue;

                if (query.isEmpty()) {
                    filtered.add(s);
                    continue;
                }

                String name = "";
                try {
                    name = s.getHoverName().getString();
                } catch (Throwable ignored) {}

                if (name != null && name.toLowerCase(Locale.ROOT).contains(query)) {
                    filtered.add(s);
                }
            }

            // Stable order: by display name
            filtered.sort(Comparator.comparing(a -> {
                try {
                    return a.getHoverName().getString();
                } catch (Throwable t) {
                    return "";
                }
            }));

            // Recompute scroll bounds
            scrollRow = 0;
            maxScrollRow = computeMaxScrollRow();

            // Rebuild selectedStacks representatives (keep keys stable)
            rebuildSelectedStacks();

            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.rebuildFiltered: query='{}' filtered={}/{} selectedKeys={}",
                    query, filtered.size(), catalog.size(), selectedKeys.size());

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.rebuildFiltered failed", t);
        }
    }

    private void rebuildSelectedStacks() {
        try {
            selectedStacks.clear();
            if (selectedKeys.isEmpty()) return;

            // Prefer stacks from full catalog so we keep components
            for (ItemStack s : catalog) {
                if (s == null || s.isEmpty()) continue;
                String k = keyOf(s);
                if (selectedKeys.contains(k)) {
                    selectedStacks.add(s);
                    if (selectedStacks.size() >= 512) break;
                }
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] rebuildSelectedStacks failed (soft): {}", t.toString());
        }
    }

    private int computeMaxScrollRow() {
        try {
            int cols = Math.max(1, gridW / (SLOT + PAD));
            int rowsVisible = Math.max(1, gridH / (SLOT + PAD));

            int total = filtered.size();
            int totalRows = (int) Math.ceil(total / (double) cols);

            return Math.max(0, totalRows - rowsVisible);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void returnToParentOrClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            // If parent exists and player still has MerchantMenu open, restore it.
            if (parent != null && mc.player != null && mc.player.containerMenu instanceof MerchantMenu) {
                mc.setScreen(parent);
                EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen: returned to parent MerchantScreen.");
                return;
            }

            // Otherwise close.
            safeCloseToNull();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] returnToParentOrClose failed (soft): {}", t.toString());
            safeCloseToNull();
        }
    }

    private void safeCloseToNull() {
        try {
            if (this.minecraft != null) this.minecraft.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.safeCloseToNull failed (soft): {}", t.toString());
        }
    }

    private void tryCloseMerchantIfOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player != null) mc.player.closeContainer();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.tryCloseMerchantIfOpen failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (super.mouseClicked(mouseX, mouseY, button)) return true;
            if (awaitingServerData) return false;

            if (button != 0) return false; // LMB select

            if (mouseX < gridX || mouseX >= gridX + gridW || mouseY < gridY || mouseY >= gridY + gridH) {
                return false;
            }

            int cols = Math.max(1, gridW / (SLOT + PAD));

            int localX = (int) mouseX - gridX;
            int localY = (int) mouseY - gridY;

            int col = localX / (SLOT + PAD);
            int row = localY / (SLOT + PAD);

            if (col < 0 || row < 0) return false;
            if (col >= cols) return false;

            int idx = (scrollRow + row) * cols + col;
            if (idx < 0 || idx >= filtered.size()) return false;

            ItemStack s = filtered.get(idx);
            if (s == null || s.isEmpty()) return false;

            String k = keyOf(s);
            if (selectedKeys.contains(k)) {
                selectedKeys.remove(k);
                EZVillagerReroll.LOG().debug("[EZVR] Selection removed: {}", safeName(s));
            } else {
                selectedKeys.add(k);
                EZVillagerReroll.LOG().debug("[EZVR] Selection added: {}", safeName(s));
            }

            rebuildSelectedStacks();
            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.mouseClicked failed", t);
            return false;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        try {
            if (super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) return true;
            if (awaitingServerData) return false;

            if (mouseX < gridX || mouseX >= gridX + gridW || mouseY < gridY || mouseY >= gridY + gridH) {
                return false;
            }

            int max = computeMaxScrollRow();
            maxScrollRow = max;

            if (deltaY > 0) scrollRow = Math.max(0, scrollRow - 1);
            else if (deltaY < 0) scrollRow = Math.min(maxScrollRow, scrollRow + 1);

            return true;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.mouseScrolled failed (soft): {}", t.toString());
            return false;
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            gg.drawCenteredString(this.font, Component.translatable("ezvr.catalog.header"), this.width / 2, 44, 0xFFFFFF);

            if (awaitingServerData) {
                gg.drawCenteredString(this.font, Component.literal("Loading…"), this.width / 2, 66, 0xB0B0B0);
                return;
            }

            int available = filtered.size();
            int selected = selectedKeys.size();

            gg.drawCenteredString(
                    this.font,
                    Component.literal("Items available: " + available + " | Selected: " + selected),
                    this.width / 2,
                    66,
                    0xB0B0B0
            );

            renderGrid(gg, mouseX, mouseY);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.render failed", t);
        }
    }

    private void renderGrid(GuiGraphics gg, int mouseX, int mouseY) {
        try {
            // Background panel
            int bg = 0x66000000;
            gg.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, bg);

            int cols = Math.max(1, gridW / (SLOT + PAD));
            int rowsVisible = Math.max(1, gridH / (SLOT + PAD));

            int startIndex = scrollRow * cols;
            int endIndex = Math.min(filtered.size(), startIndex + cols * rowsVisible);

            int x0 = gridX;
            int y0 = gridY;

            int hoverIndex = -1;

            // Compute hover cell
            if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
                int localX = (int) mouseX - gridX;
                int localY = (int) mouseY - gridY;
                int col = localX / (SLOT + PAD);
                int row = localY / (SLOT + PAD);
                int idx = startIndex + row * cols + col;
                if (col >= 0 && col < cols && row >= 0 && row < rowsVisible && idx >= startIndex && idx < endIndex) {
                    hoverIndex = idx;
                }
            }

            int i = startIndex;
            int row = 0;
            int col = 0;

            while (i < endIndex) {
                ItemStack s = filtered.get(i);
                int x = x0 + col * (SLOT + PAD);
                int y = y0 + row * (SLOT + PAD);

                // Slot background
                int slotBg = 0x33000000;
                gg.fill(x, y, x + SLOT, y + SLOT, slotBg);

                if (s != null && !s.isEmpty()) {
                    gg.renderItem(s, x + 1, y + 1);
                    gg.renderItemDecorations(this.font, s, x + 1, y + 1);

                    String k = keyOf(s);
                    boolean sel = selectedKeys.contains(k);
                    if (sel) {
                        // selection outline
                        int outline = 0xFF66FF66;
                        gg.renderOutline(x, y, SLOT, SLOT, outline);
                    }

                    if (i == hoverIndex) {
                        // hover outline + tooltip
                        int outline = 0xFFFFFFFF;
                        gg.renderOutline(x, y, SLOT, SLOT, outline);

                        // Tooltip: use vanilla hover name
                        try {
                            gg.renderTooltip(this.font, s, (int) (mouseX), (int) (mouseY));
                        } catch (Throwable ignored) {}
                    }
                }

                i++;
                col++;
                if (col >= cols) {
                    col = 0;
                    row++;
                    if (row >= rowsVisible) break;
                }
            }

            // Scroll indicator (simple)
            maxScrollRow = computeMaxScrollRow();
            if (maxScrollRow > 0) {
                int barX = gridX + gridW + 6;
                int barY0 = gridY;
                int barY1 = gridY + gridH;

                gg.fill(barX, barY0, barX + 4, barY1, 0x33000000);

                double t = scrollRow / (double) maxScrollRow;
                int thumbH = Math.max(12, gridH / 6);
                int thumbY = barY0 + (int) ((gridH - thumbH) * t);

                gg.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0x99FFFFFF);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.renderGrid failed", t);
        }
    }

    private static String keyOf(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return "empty";

            String itemPart = String.valueOf(s.getItem());

            String compPart;
            try {
                Object patch = s.getComponentsPatch();
                compPart = (patch == null) ? "noComponents" : patch.toString();
            } catch (Throwable ignored) {
                compPart = s.toString();
            }

            return itemPart + "|" + compPart;
        } catch (Throwable t) {
            return "err|" + Objects.hashCode(s);
        }
    }

    private static String safeName(ItemStack s) {
        try {
            return s == null ? "null" : s.getHoverName().getString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
