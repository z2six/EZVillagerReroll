// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/SearchCatalogScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogData;
import org.z2six.ezvillagerreroll.network.PacketSearchCatalogQuery;
import org.z2six.ezvillagerreroll.network.PacketStartAutoSearch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SearchCatalogScreen extends Screen {

    private final MerchantScreen parent;
    private int villagerEntityId;

    private final List<ItemStack> catalogAll = new ArrayList<>();
    private final List<ItemStack> catalogFiltered = new ArrayList<>();

    // selection by stable-ish key (item + components patch)
    private final Set<String> selectedKeys = new HashSet<>();

    private EditBox searchBox;
    private Button btnCancel;
    private Button btnRequest;

    private boolean awaitingServerData = true;

    // Hourly cost preview (server-auth computed & sent in PacketSearchCatalogData)
    private int offerCount = -1;
    private int lockedCount = -1;
    private int effectivePaidOffers = -1;
    private int manualCost = -1;
    private int hourlyCost = -1;

    // Grid/scroll
    private int scrollRow = 0;

    private static final int ITEM_SIZE = 18;
    private static final int PAD = 4;
    private static final int SCROLLBAR_W = 8;
    private static final int GRID_LEFT = 20;
    private static final int GRID_TOP = 90;
    private static final int GRID_BOTTOM_PAD = 40;   // bottom UI area
    private static final int GRID_RIGHT_PAD = 20;    // right margin
    private static final int SCROLLBAR_GAP = 2;

    // Inline icon (emerald) rendering
    private static final int INLINE_ICON_SIZE = 16;
    private static final int INLINE_ICON_GAP = 4;
    private static final int INLINE_Z = 200; // above background; below tooltips
    private static final int COLOR_WHITE_OPAQUE = 0xFFFFFFFF;

    private SimpleScrollBar scrollBar;

    // For “name + level” parsing (Bane of Arthropods III, Impaling V, Protection 4, etc.)
    private static final Pattern TRAILING_LEVEL = Pattern.compile("^(.*?)(?:\\s+([0-9]+|[IVXLCDM]+))\\s*$", Pattern.CASE_INSENSITIVE);

    public SearchCatalogScreen(MerchantScreen parent) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = parent;
        this.villagerEntityId = -1;
    }

    public SearchCatalogScreen(int villagerEntityId, List<ItemStack> catalog) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = null;
        this.villagerEntityId = villagerEntityId;
        if (catalog != null) this.catalogAll.addAll(catalog);
        this.awaitingServerData = false;
        rebuildFilteredAndSorted();
    }

    public void applyCatalogFromServer(PacketSearchCatalogData msg) {
        try {
            if (msg == null) return;
            this.villagerEntityId = msg.villagerEntityId();

            this.catalogAll.clear();
            List<ItemStack> items = msg.catalog();
            if (items != null) {
                for (ItemStack s : items) {
                    if (s == null || s.isEmpty()) continue;
                    this.catalogAll.add(s.copy());
                }
            }

            this.offerCount = msg.offerCount();
            this.lockedCount = msg.lockedCount();
            this.effectivePaidOffers = msg.effectivePaidOffers();
            this.manualCost = msg.manualCost();
            this.hourlyCost = msg.hourlyCost();

            this.awaitingServerData = false;

            EZVillagerReroll.LOG().info(
                    "[EZVR] SearchCatalogScreen received catalog: villagerEntityId={} items={} offerCount={} lockedCount={} effectivePaidOffers={} manualCost={} hourlyCost={}",
                    villagerEntityId, this.catalogAll.size(), offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost
            );

            rebuildFilteredAndSorted();
            scrollRow = 0;
            clampScroll();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer(PacketSearchCatalogData) failed", t);
        }
    }

    // Backwards-compatible old signature (kept so other call-sites won't crash)
    public void applyCatalogFromServer(int villagerEntityId, List<ItemStack> items) {
        try {
            applyCatalogFromServer(PacketSearchCatalogData.minimal(villagerEntityId, items));
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer(int,List) wrapper failed", t);
        }
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;

            searchBox = new EditBox(this.font, cx - 110, 18, 220, 18, Component.translatable("ezvr.catalog.search"));
            searchBox.setMaxLength(64);
            searchBox.setResponder(s -> {
                try {
                    EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen search changed: '{}'", s);
                    rebuildFilteredAndSorted();
                    scrollRow = 0;
                    clampScroll();
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] search responder failed (soft): {}", t.toString());
                }
            });
            addRenderableWidget(searchBox);

            btnCancel = Button.builder(Component.translatable("ezvr.catalog.cancel"), b -> closeToParentOrNull())
                    .pos(cx - 110, this.height - 28)
                    .size(100, 20)
                    .build();

            btnRequest = Button.builder(Component.translatable("ezvr.catalog.request"), b -> {
                        try {
                            if (villagerEntityId < 0) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked but villagerEntityId not set yet; ignoring.");
                                return;
                            }

                            List<ItemStack> req = new ArrayList<>();
                            for (ItemStack s : catalogAll) {
                                if (s == null || s.isEmpty()) continue;
                                String k = keyOf(s);
                                if (!selectedKeys.contains(k)) continue;
                                req.add(s.copy());
                            }

                            if (req.isEmpty()) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked with empty selection; ignoring.");
                                return;
                            }

                            ClientNetwork.sendToServer(new PacketStartAutoSearch(villagerEntityId, req));
                            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen: sent PacketStartAutoSearch villagerEntityId={} items={}",
                                    villagerEntityId, req.size());

                            // Request means: close this UI AND close the merchant container.
                            closeAllAndCloseContainer();

                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen: request send failed", t);
                        }
                    })
                    .pos(cx + 10, this.height - 28)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnRequest);

            // Create scrollbar once we know screen dims
            int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
            scrollBar = new SimpleScrollBar(computeScrollbarX(), GRID_TOP, SCROLLBAR_W, gridH);

            // Redundant query (safe)
            if (awaitingServerData && villagerEntityId >= 0) {
                try {
                    ClientNetwork.sendToServer(new PacketSearchCatalogQuery(villagerEntityId));
                    EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.init: sent redundant PacketSearchCatalogQuery(villagerEntityId={})", villagerEntityId);
                } catch (Throwable ignored) {}
            }

            rebuildFilteredAndSorted();
            clampScroll();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.init failed", t);
        }
    }

    /**
     * Cancel / ESC should not set screen null while keeping the trade container open.
     * That combination can leave the client stuck “in a trade session” and unable to reopen the villager.
     *
     * So:
     * - if we have a parent MerchantScreen, restore it
     * - otherwise fall back to null
     */
    private void closeToParentOrNull() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            if (this.parent != null) {
                mc.setScreen(this.parent);
            } else {
                mc.setScreen(null);
            }
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.closeToParentOrNull failed (soft): {}", t.toString());
        }
    }

    private void closeAllAndCloseContainer() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {}

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player != null) mc.player.closeContainer();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.closeAllAndCloseContainer failed (soft): {}", t.toString());
        }
    }

    @Override
    public void onClose() {
        // ESC should behave like Cancel (restore parent), not “close to null”
        closeToParentOrNull();
    }

    private int computeGridBottom() {
        try {
            return this.height - GRID_BOTTOM_PAD;
        } catch (Throwable t) {
            return this.height - 40;
        }
    }

    private int computeScrollbarX() {
        try {
            // Right edge: width - right pad - scrollbar width
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W;
        } catch (Throwable t) {
            return this.width - 20 - SCROLLBAR_W;
        }
    }

    private int computeGridRight() {
        try {
            // Grid area ends before scrollbar + gap
            int right = computeScrollbarX() - SCROLLBAR_GAP;
            return Math.max(GRID_LEFT + ITEM_SIZE, right);
        } catch (Throwable t) {
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W - SCROLLBAR_GAP;
        }
    }

    /**
     * Render and hit-testing MUST share the exact same column math.
     * We compute cols based strictly on the actual grid pixel width.
     */
    private int computeCols() {
        try {
            int gridRight = computeGridRight();
            int gridW = Math.max(1, gridRight - GRID_LEFT);
            // If we place N items: x = left + col*(ITEM_SIZE+PAD).
            // Total width occupied = N*ITEM_SIZE + (N-1)*PAD.
            // This equals N*(ITEM_SIZE+PAD) - PAD.
            // So we solve for N: N*(ITEM_SIZE+PAD) - PAD <= gridW  =>  N <= (gridW + PAD)/(ITEM_SIZE+PAD)
            int cols = (gridW + PAD) / (ITEM_SIZE + PAD);
            return Math.max(1, cols);
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeVisibleRows() {
        try {
            int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
            int rows = gridH / (ITEM_SIZE + PAD);
            return Math.max(1, rows);
        } catch (Throwable t) {
            return 1;
        }
    }

    private int computeTotalRows() {
        try {
            int cols = computeCols();
            int count = catalogFiltered.size();
            return (count + cols - 1) / cols;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void clampScroll() {
        try {
            int totalRows = computeTotalRows();
            int visibleRows = computeVisibleRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollRow < 0) scrollRow = 0;
            if (scrollRow > maxRow) scrollRow = maxRow;
        } catch (Throwable ignored) {}
    }

    private void rebuildFilteredAndSorted() {
        try {
            catalogFiltered.clear();

            String q = "";
            try {
                q = (searchBox == null) ? "" : String.valueOf(searchBox.getValue());
            } catch (Throwable ignored) {}

            String query = (q == null) ? "" : q.trim().toLowerCase(Locale.ROOT);

            if (catalogAll.isEmpty()) return;

            List<Entry> tmp = new ArrayList<>(catalogAll.size());
            for (ItemStack s : catalogAll) {
                if (s == null || s.isEmpty()) continue;

                Entry e = buildEntry(s);
                if (e == null) continue;

                if (!query.isEmpty() && !matchesQuery(e.searchBlobLower, query)) continue;
                tmp.add(e);
            }

            // Sort by:
            // 1) Item name A-Z (primaryName)
            // 2) "Detail base" A-Z (e.g., "Bane of Arthropods")
            // 3) Parsed numeric level ascending (I, II, III, 4, V, X, etc.)
            // 4) Raw detail tie-breaker
            // 5) Blob tie-breaker
            tmp.sort((a, b) -> {
                int c;

                c = a.primaryName.compareToIgnoreCase(b.primaryName);
                if (c != 0) return c;

                c = a.detailBase.compareToIgnoreCase(b.detailBase);
                if (c != 0) return c;

                c = Integer.compare(a.detailLevel, b.detailLevel);
                if (c != 0) return c;

                c = a.secondaryDetail.compareToIgnoreCase(b.secondaryDetail);
                if (c != 0) return c;

                return a.searchBlobLower.compareTo(b.searchBlobLower);
            });

            for (Entry e : tmp) catalogFiltered.add(e.stack);

            EZVillagerReroll.LOG().debug("[EZVR] rebuildFilteredAndSorted: all={} filtered={} query='{}'",
                    catalogAll.size(), catalogFiltered.size(), query);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] rebuildFilteredAndSorted failed", t);
        }
    }

    private static boolean matchesQuery(String haystackLower, String queryLower) {
        try {
            if (haystackLower == null) return false;
            if (queryLower == null || queryLower.isBlank()) return true;

            String[] parts = queryLower.split("\\s+");
            for (String p : parts) {
                if (p.isBlank()) continue;
                if (!haystackLower.contains(p)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Entry buildEntry(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return null;

            Minecraft mc = Minecraft.getInstance();
            Player player = (mc == null) ? null : mc.player;

            String name = safeString(() -> s.getHoverName().getString());

            List<Component> lines = List.of();
            try {
                if (player != null) {
                    lines = TooltipCompat.getTooltipLines(s, player, TooltipFlag.Default.NORMAL);
                }
            } catch (Throwable ignored) {}

            // Build search blob and pick a “best detail line” for sorting.
            String bestDetailLine = "";
            StringBuilder blob = new StringBuilder(256);
            if (!name.isBlank()) blob.append(name);

            if (lines != null && !lines.isEmpty()) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = "";
                    try { line = lines.get(i).getString(); } catch (Throwable ignored) {}
                    if (line == null || line.isBlank()) continue;

                    blob.append('\n').append(line);

                    // For sorting: prefer the first meaningful line AFTER the title line.
                    // For Enchanted Books, this is typically the enchant line.
                    if (i > 0 && bestDetailLine.isBlank()) {
                        bestDetailLine = line.trim();
                    }
                }
            }

            // Add components patch to blob for broader “modded details” matching.
            try {
                Object patch = s.getComponentsPatch();
                if (patch != null) blob.append("\n").append(patch.toString());
            } catch (Throwable ignored) {}

            String blobLower = blob.toString().toLowerCase(Locale.ROOT);

            // Parse detail into base + level for stable grouping (Bane of Arthropods + 3)
            ParsedDetail pd = parseDetail(bestDetailLine);

            Entry e = new Entry();
            e.stack = s;
            e.primaryName = (name == null) ? "" : name;
            e.secondaryDetail = (bestDetailLine == null) ? "" : bestDetailLine;
            e.detailBase = pd.base;
            e.detailLevel = pd.level;
            e.searchBlobLower = blobLower;
            return e;

        } catch (Throwable t) {
            return null;
        }
    }

    private static final class ParsedDetail {
        final String base;
        final int level;

        ParsedDetail(String base, int level) {
            this.base = (base == null) ? "" : base;
            this.level = Math.max(0, level);
        }
    }

    private static ParsedDetail parseDetail(String detail) {
        try {
            if (detail == null) return new ParsedDetail("", 0);
            String d = detail.trim();
            if (d.isEmpty()) return new ParsedDetail("", 0);

            Matcher m = TRAILING_LEVEL.matcher(d);
            if (!m.matches()) {
                return new ParsedDetail(d, 0);
            }

            String base = (m.group(1) == null) ? d : m.group(1).trim();
            String lvl = (m.group(2) == null) ? "" : m.group(2).trim();

            int level = 0;
            if (!lvl.isEmpty()) {
                if (isAllDigits(lvl)) {
                    try { level = Integer.parseInt(lvl); } catch (Throwable ignored) { level = 0; }
                } else {
                    level = romanToInt(lvl);
                }
            }

            return new ParsedDetail(base, level);

        } catch (Throwable t) {
            return new ParsedDetail(detail, 0);
        }
    }

    private static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static int romanToInt(String roman) {
        try {
            if (roman == null) return 0;
            String r = roman.trim().toUpperCase(Locale.ROOT);
            if (r.isEmpty()) return 0;

            int sum = 0;
            int prev = 0;
            for (int i = r.length() - 1; i >= 0; i--) {
                int v = switch (r.charAt(i)) {
                    case 'I' -> 1;
                    case 'V' -> 5;
                    case 'X' -> 10;
                    case 'L' -> 50;
                    case 'C' -> 100;
                    case 'D' -> 500;
                    case 'M' -> 1000;
                    default -> 0;
                };
                if (v == 0) return 0;
                if (v < prev) sum -= v;
                else {
                    sum += v;
                    prev = v;
                }
            }
            return Math.max(0, sum);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String safeString(SupplierThrows<String> sup) {
        try {
            String s = sup.get();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    @FunctionalInterface
    private interface SupplierThrows<T> { T get() throws Throwable; }

    private static final class Entry {
        ItemStack stack;
        String primaryName;
        String secondaryDetail;

        // new sort fields
        String detailBase;
        int detailLevel;

        String searchBlobLower;
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

    /**
     * MC 1.21.1 ContainerEventHandler signature uses 4 params:
     * mouseScrolled(mouseX, mouseY, scrollX, scrollY)
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        try {
            if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

            int step = (scrollY > 0) ? -1 : 1;
            scrollRow += step;
            clampScroll();

            return true;
        } catch (Throwable t) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseClicked(mouseX, mouseY, button)) return true;

            if (!awaitingServerData) {
                int gridBottom = computeGridBottom();
                int gridRight = computeGridRight();

                if (mouseX >= GRID_LEFT && mouseX < gridRight && mouseY >= GRID_TOP && mouseY < gridBottom) {
                    int cols = computeCols();
                    int visibleRows = computeVisibleRows();

                    int relX = (int) mouseX - GRID_LEFT;
                    int relY = (int) mouseY - GRID_TOP;

                    int col = relX / (ITEM_SIZE + PAD);
                    int row = relY / (ITEM_SIZE + PAD);

                    if (col >= 0 && col < cols && row >= 0 && row < visibleRows) {
                        int index = (scrollRow + row) * cols + col;
                        if (index >= 0 && index < catalogFiltered.size()) {
                            ItemStack clicked = catalogFiltered.get(index);
                            String k = keyOf(clicked);

                            if (button == 0) {
                                if (selectedKeys.contains(k)) selectedKeys.remove(k);
                                else selectedKeys.add(k);

                                EZVillagerReroll.LOG().debug("[EZVR] Catalog selection toggled: key={} selectedCount={}", k, selectedKeys.size());
                                return true;
                            }
                        }
                    }
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.mouseClicked failed (soft): {}", t.toString());
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        try {
            if (scrollBar != null && scrollBar.mouseReleased(mouseX, mouseY, button)) return true;
        } catch (Throwable ignored) {}
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        try {
            if (scrollBar != null && scrollBar.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                int maxRow = Math.max(0, computeTotalRows() - computeVisibleRows());
                scrollRow = scrollBar.getScrollRowFromThumb(scrollRow, maxRow);
                clampScroll();
                return true;
            }
        } catch (Throwable ignored) {}
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            // Recompute scrollbar on resize dynamically (prevents stale x/h causing drift after resizing).
            try {
                int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
                if (scrollBar != null) scrollBar.setBounds(computeScrollbarX(), GRID_TOP, SCROLLBAR_W, gridH);
            } catch (Throwable ignored) {}

            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            gg.drawCenteredString(this.font, Component.translatable("ezvr.catalog.header"), this.width / 2, 44, 0xFFFFFF);

            if (awaitingServerData) {
                gg.drawCenteredString(this.font, Component.literal("Loading…").withStyle(ChatFormatting.GRAY), this.width / 2, 66, 0xFFFFFF);
                return;
            }

            // Line 1: counts
            gg.drawCenteredString(this.font,
                    Component.literal("Items available: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(catalogFiltered.size())).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("   Selected: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(selectedKeys.size())).withStyle(ChatFormatting.WHITE)),
                    this.width / 2, 66, 0xFFFFFF);

            // Line 2: hourly cost preview (colored + emerald icons inline)
            renderHourlyPreviewLine(gg, 78);

            int gridBottom = computeGridBottom();
            int gridRight = computeGridRight();

            int cols = computeCols();
            int visibleRows = computeVisibleRows();

            int startIndex = scrollRow * cols;
            int endIndex = Math.min(catalogFiltered.size(), startIndex + visibleRows * cols);

            int idx = startIndex;

            for (int row = 0; row < visibleRows && idx < endIndex; row++) {
                int y = GRID_TOP + row * (ITEM_SIZE + PAD);

                for (int col = 0; col < cols && idx < endIndex; col++, idx++) {
                    int x = GRID_LEFT + col * (ITEM_SIZE + PAD);

                    // Safety: don't render outside gridRight (should be consistent with cols math, but keep it soft)
                    if (x + ITEM_SIZE > gridRight) continue;

                    ItemStack s = catalogFiltered.get(idx);
                    if (s == null) continue;

                    String k = keyOf(s);
                    boolean sel = selectedKeys.contains(k);
                    if (sel) {
                        int outline = 0xFF66FF66;
                        gg.renderOutline(x - 1, y - 1, ITEM_SIZE + 2, ITEM_SIZE + 2, outline);
                    }

                    gg.renderItem(s, x, y);
                    gg.renderItemDecorations(this.font, s, x, y);
                }

                if (y + ITEM_SIZE > gridBottom) break;
            }

            // Tooltip on hover (vanilla stack tooltip)
            if (mouseX >= GRID_LEFT && mouseX < gridRight && mouseY >= GRID_TOP && mouseY < gridBottom) {
                int relX = (int) mouseX - GRID_LEFT;
                int relY = (int) mouseY - GRID_TOP;

                int col = relX / (ITEM_SIZE + PAD);
                int row = relY / (ITEM_SIZE + PAD);

                if (col >= 0 && col < cols && row >= 0 && row < visibleRows) {
                    int hoverIndex = (scrollRow + row) * cols + col;
                    if (hoverIndex >= 0 && hoverIndex < catalogFiltered.size()) {
                        ItemStack hover = catalogFiltered.get(hoverIndex);
                        if (hover != null && !hover.isEmpty()) {
                            ezvr$renderVanillaItemTooltip(gg, hover, mouseX, mouseY);
                        }
                    }
                }
            }

            int totalRows = computeTotalRows();
            int maxRow = Math.max(0, totalRows - visibleRows);
            if (scrollBar != null) {
                scrollBar.render(gg, scrollRow, maxRow, totalRows, visibleRows);
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.render failed", t);
        }
    }

    private void renderHourlyPreviewLine(GuiGraphics gg, int y) {
        try {
            if (gg == null || this.font == null) return;
            if (hourlyCost < 0) return;

            InlineLinePlan plan = buildHourlyInlinePlan();
            if (plan == null || plan.parts.isEmpty()) return;

            int totalW = 0;
            for (InlinePart p : plan.parts) {
                if (p == null) continue;
                int w = this.font.width(p.text == null ? Component.empty() : p.text);
                if (p.hasEmeraldAfter) w += INLINE_ICON_GAP + INLINE_ICON_SIZE;
                totalW += w;
            }

            int startX = (this.width / 2) - (totalW / 2);

            gg.pose().pushPose();
            gg.pose().translate(0.0D, 0.0D, (double) INLINE_Z);

            int x = startX;
            for (InlinePart p : plan.parts) {
                if (p == null) continue;

                Component c = p.text == null ? Component.empty() : p.text;
                gg.drawString(this.font, c, x, y, COLOR_WHITE_OPAQUE, true);

                int w = this.font.width(c);
                x += w;

                if (p.hasEmeraldAfter) {
                    int iconX = x + INLINE_ICON_GAP;
                    int iconY = y + Math.max(0, (this.font.lineHeight - INLINE_ICON_SIZE) / 2) - 3;

                    try {
                        ItemStack em = new ItemStack(Items.EMERALD);
                        gg.renderItem(em, iconX, iconY);
                        gg.renderItemDecorations(this.font, em, iconX, iconY);
                    } catch (Throwable t) {
                        EZVillagerReroll.LOG().debug("[EZVR] renderHourlyPreviewLine emerald draw failed (soft): {}", t.toString());
                    }

                    x = iconX + INLINE_ICON_SIZE;
                }
            }

            gg.pose().popPose();

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] renderHourlyPreviewLine failed (soft): {}", t.toString());
        }
    }

    private InlineLinePlan buildHourlyInlinePlan() {
        try {
            InlineLinePlan plan = new InlineLinePlan();

            // "Hourly cost: <n> [emerald]"
            plan.parts.add(new InlinePart(
                    Component.literal("Hourly cost: ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(String.valueOf(Math.max(0, hourlyCost))).withStyle(ChatFormatting.WHITE)),
                    true
            ));

            // spacing
            plan.parts.add(new InlinePart(Component.literal("   "), false));

            // "Manual: <n> [emerald]"
            if (manualCost >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Manual: ").withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(String.valueOf(Math.max(0, manualCost))).withStyle(ChatFormatting.WHITE)),
                        true
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

            // "Paid offers: <n>"
            if (effectivePaidOffers >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Paid offers: ").withStyle(ChatFormatting.RED)
                                .append(Component.literal(String.valueOf(Math.max(0, effectivePaidOffers))).withStyle(ChatFormatting.WHITE)),
                        false
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

            // "Locked: a/b"
            if (lockedCount >= 0 || offerCount >= 0) {
                String a = (lockedCount >= 0) ? String.valueOf(Math.max(0, lockedCount)) : "?";
                String b = (offerCount >= 0) ? String.valueOf(Math.max(0, offerCount)) : "?";

                plan.parts.add(new InlinePart(
                        Component.literal("Locked: ").withStyle(ChatFormatting.DARK_RED)
                                .append(Component.literal(a).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal("/").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(b).withStyle(ChatFormatting.WHITE)),
                        false
                ));
            }

            // Trim trailing spacing parts (avoid centering drift if last is spaces)
            while (!plan.parts.isEmpty()) {
                InlinePart last = plan.parts.get(plan.parts.size() - 1);
                if (last != null && last.text != null && "   ".equals(last.text.getString())) {
                    plan.parts.remove(plan.parts.size() - 1);
                } else break;
            }

            return plan;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] buildHourlyInlinePlan failed (soft): {}", t.toString());
            return null;
        }
    }

    private static final class InlineLinePlan {
        final List<InlinePart> parts = new ArrayList<>();
    }

    private static final class InlinePart {
        final Component text;
        final boolean hasEmeraldAfter;

        InlinePart(Component text, boolean hasEmeraldAfter) {
            this.text = text;
            this.hasEmeraldAfter = hasEmeraldAfter;
        }
    }

    private void ezvr$renderVanillaItemTooltip(GuiGraphics gg, ItemStack stack, double mouseX, double mouseY) {
        try {
            if (gg == null || this.font == null) return;
            if (stack == null || stack.isEmpty()) return;

            gg.renderTooltip(this.font, stack, (int) mouseX, (int) mouseY);
        } catch (Throwable t) {
            try {
                gg.renderTooltip(
                        this.font,
                        java.util.List.of(stack.getHoverName()),
                        java.util.Optional.empty(),
                        (int) mouseX,
                        (int) mouseY
                );
            } catch (Throwable ignored) {}

            EZVillagerReroll.LOG().debug("[EZVR] ezvr$renderVanillaItemTooltip failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------
    // Tooltip compat (handles 1.21.1 signature differences safely)
    // ------------------------------------------------------------
    private static final class TooltipCompat {
        private static Method mGetTooltipLines_New; // (TooltipContext, Player, TooltipFlag)
        private static Method mGetTooltipLines_Old; // (Player, TooltipFlag)
        private static boolean lookedUp = false;

        static List<Component> getTooltipLines(ItemStack stack, Player player, TooltipFlag flag) {
            try {
                if (!lookedUp) lookupMethods();
                if (stack == null) return List.of();
                if (player == null) return List.of(stack.getHoverName());

                if (mGetTooltipLines_New != null) {
                    Object ctx = createTooltipContext(player);
                    if (ctx != null) {
                        @SuppressWarnings("unchecked")
                        List<Component> res = (List<Component>) mGetTooltipLines_New.invoke(stack, ctx, player, flag);
                        return res == null ? List.of(stack.getHoverName()) : res;
                    }
                }

                if (mGetTooltipLines_Old != null) {
                    @SuppressWarnings("unchecked")
                    List<Component> res = (List<Component>) mGetTooltipLines_Old.invoke(stack, player, flag);
                    return res == null ? List.of(stack.getHoverName()) : res;
                }

                return List.of(stack.getHoverName());

            } catch (Throwable t) {
                return List.of(stack == null ? Component.literal("") : stack.getHoverName());
            }
        }

        private static void lookupMethods() {
            lookedUp = true;
            try {
                Class<?> tooltipContextClz = Class.forName("net.minecraft.world.item.TooltipContext");
                try {
                    mGetTooltipLines_New = ItemStack.class.getMethod("getTooltipLines", tooltipContextClz, Player.class, TooltipFlag.class);
                    EZVillagerReroll.LOG().debug("[EZVR] TooltipCompat: found ItemStack.getTooltipLines(TooltipContext, Player, TooltipFlag)");
                } catch (Throwable ignored) {
                    mGetTooltipLines_New = null;
                }
            } catch (Throwable ignored) {
                mGetTooltipLines_New = null;
            }

            try {
                mGetTooltipLines_Old = ItemStack.class.getMethod("getTooltipLines", Player.class, TooltipFlag.class);
                EZVillagerReroll.LOG().debug("[EZVR] TooltipCompat: found ItemStack.getTooltipLines(Player, TooltipFlag)");
            } catch (Throwable ignored) {
                mGetTooltipLines_Old = null;
            }
        }

        private static Object createTooltipContext(Player player) {
            try {
                Class<?> tooltipContextClz = Class.forName("net.minecraft.world.item.TooltipContext");
                Object level = player.level();

                try {
                    Method of = tooltipContextClz.getMethod("of", level.getClass().getInterfaces().length > 0 ? level.getClass().getInterfaces()[0] : level.getClass());
                    return of.invoke(null, level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Method of = tooltipContextClz.getMethod("of", levelClz);
                    return of.invoke(null, level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Constructor<?> c = tooltipContextClz.getConstructor(levelClz);
                    return c.newInstance(level);
                } catch (Throwable ignored) {}

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    Constructor<?> c = tooltipContextClz.getConstructor(levelClz, boolean.class);
                    return c.newInstance(level, false);
                } catch (Throwable ignored) {}

                return null;

            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------
    // Minimal draggable scrollbar helper
    // ------------------------------------------------------------
    private static final class SimpleScrollBar {
        private int x, y, w, h;

        private boolean dragging = false;
        private double lastMouseY = 0;

        SimpleScrollBar(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        void setBounds(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            if (this.h < 1) this.h = 1;
            if (this.w < 1) this.w = 1;
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
