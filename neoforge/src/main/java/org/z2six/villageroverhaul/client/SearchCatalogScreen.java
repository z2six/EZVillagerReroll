// neoforge\src\main\java\org\z2six\villageroverhaul\client\SearchCatalogScreen.java
package org.z2six.villageroverhaul.client;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.fml.ModList;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.ClientTradeLockCache;
import org.z2six.villageroverhaul.network.autoReroll.PacketSearchCatalogData;
import org.z2six.villageroverhaul.network.autoReroll.PacketSearchCatalogQuery;
import org.z2six.villageroverhaul.network.autoReroll.PacketStartAutoSearch;

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
    private long lockMask = 0L;
    private int effectivePaidOffers = -1;
    private int manualCost = -1;
    private int hourlyCost = -1;

    // Player-global accumulated value in V units (server-auth; indexed by CatalogBuilder.keyOf)
    private final Map<String, Long> accumulatedValueVByKey = new HashMap<>();

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
    private static final Pattern TRAILING_LEVEL =
            Pattern.compile("^(.*?)(?:\\s+([0-9]+|[IVXLCDM]+))\\s*$", Pattern.CASE_INSENSITIVE);

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

    // Backwards-compatible old signature (kept so other call-sites won't crash)
    public void applyCatalogFromServer(int villagerEntityId, List<ItemStack> items) {
        try {
            applyCatalogFromServer(PacketSearchCatalogData.minimal(villagerEntityId, items));
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] applyCatalogFromServer(int,List) wrapper failed", t);
        }
    }

    public void applyCatalogFromServer(PacketSearchCatalogData msg) {
        try {
            if (msg == null) return;
            this.villagerEntityId = msg.villagerEntityId();

            this.catalogAll.clear();
            this.accumulatedValueVByKey.clear();
            List<ItemStack> items = msg.catalog();
            List<Long> values = msg.accumulatedValueV();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    ItemStack s = items.get(i);
                    if (s == null || s.isEmpty()) continue;
                    this.catalogAll.add(s.copy());

                    try {
                        String k = keyOf(s);
                        if (k != null && !k.isBlank()) {
                            long vv = 0L;
                            if (values != null && i >= 0 && i < values.size()) {
                                Long v = values.get(i);
                                vv = v == null ? 0L : Math.max(0L, v);
                            }
                            long prev = accumulatedValueVByKey.getOrDefault(k, 0L);
                            if (vv > prev) accumulatedValueVByKey.put(k, vv);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            this.offerCount = msg.offerCount();
            this.lockedCount = msg.lockedCount();
            this.lockMask = msg.lockMask();
            this.effectivePaidOffers = msg.effectivePaidOffers();
            this.manualCost = msg.manualCost();
            this.hourlyCost = msg.hourlyCost();

            this.awaitingServerData = false;

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] SearchCatalogScreen received catalog: villagerEntityId={} items={} offerCount={} lockedCount={} effectivePaidOffers={} manualCost={} hourlyCost={}",
                    villagerEntityId, this.catalogAll.size(), offerCount, lockedCount, effectivePaidOffers, manualCost, hourlyCost
            );

            rebuildFilteredAndSorted();

            // Prune selection to ensure you can't keep “already offered” items selected if state updated.
            pruneSelectionAgainstAlreadyOffered();

            scrollRow = 0;
            clampScroll();

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] applyCatalogFromServer(PacketSearchCatalogData) failed", t);
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
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen search changed: '{}'", s);
                    rebuildFilteredAndSorted();
                    scrollRow = 0;
                    clampScroll();
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] search responder failed (soft): {}", t.toString());
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
                                VillagerOverhaul.LOG().warn("[VillagerOverhaul] Request clicked but villagerEntityId not set yet; ignoring.");
                                return;
                            }

                            List<ItemStack> req = new ArrayList<>();
                            for (ItemStack s : catalogAll) {
                                if (s == null || s.isEmpty()) continue;
                                String k = keyOf(s);
                                if (!selectedKeys.contains(k)) continue;

                                // Hard safety: never request items already offered in current menu.
                                if (isCatalogKeyDisabledBecauseAlreadyOffered(k)) continue;

                                req.add(s.copy());
                            }

                            if (req.isEmpty()) {
                                VillagerOverhaul.LOG().warn("[VillagerOverhaul] Request clicked with empty selection (or all were already offered); ignoring.");
                                return;
                            }

                            ClientNetwork.sendToServer(new PacketStartAutoSearch(villagerEntityId, req));
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen: sent PacketStartAutoSearch villagerEntityId={} items={}",
                                    villagerEntityId, req.size());

                            closeAllAndCloseContainer();

                        } catch (Throwable t) {
                            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchCatalogScreen: request send failed", t);
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

            // Redundant catalog query (safe)
            if (awaitingServerData && villagerEntityId >= 0) {
                try {
                    ClientNetwork.sendToServer(new PacketSearchCatalogQuery(villagerEntityId));
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen.init: sent redundant PacketSearchCatalogQuery(villagerEntityId={})", villagerEntityId);
                } catch (Throwable ignored) {}
            }

            rebuildFilteredAndSorted();
            clampScroll();

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchCatalogScreen.init failed", t);
        }
    }

    /**
     * Renders an item at a specified alpha (0..1).
     * We explicitly enable blending because item rendering paths can otherwise appear fully opaque.
     */
    private void ezvr$renderItemWithAlpha(GuiGraphics gg, ItemStack stack, int x, int y, float alpha) {
        try {
            if (gg == null) return;
            if (stack == null || stack.isEmpty()) return;

            float a = alpha;
            if (a < 0.0f) a = 0.0f;
            if (a > 1.0f) a = 1.0f;

            // Always restore shader color so we don't poison downstream rendering.
            try {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, a);
                gg.renderItem(stack, x, y);
            } finally {
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ezvr$renderItemWithAlpha failed (soft): {}", t.toString());
            try {
                gg.renderItem(stack, x, y);
            } catch (Throwable ignored) {}
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen.closeToParentOrNull failed (soft): {}", t.toString());
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
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen.closeAllAndCloseContainer failed (soft): {}", t.toString());
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
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W;
        } catch (Throwable t) {
            return this.width - 20 - SCROLLBAR_W;
        }
    }

    private int computeGridRight() {
        try {
            int right = computeScrollbarX() - SCROLLBAR_GAP;
            return Math.max(GRID_LEFT + ITEM_SIZE, right);
        } catch (Throwable t) {
            return this.width - GRID_RIGHT_PAD - SCROLLBAR_W - SCROLLBAR_GAP;
        }
    }

    private int computeCols() {
        try {
            int gridRight = computeGridRight();
            int gridW = Math.max(1, gridRight - GRID_LEFT);
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

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] rebuildFilteredAndSorted: all={} filtered={} query='{}'",
                    catalogAll.size(), catalogFiltered.size(), query);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] rebuildFilteredAndSorted failed", t);
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

            String bestDetailLine = "";
            StringBuilder blob = new StringBuilder(256);
            if (!name.isBlank()) blob.append(name);

            if (lines != null && !lines.isEmpty()) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = "";
                    try { line = lines.get(i).getString(); } catch (Throwable ignored) {}
                    if (line == null || line.isBlank()) continue;

                    blob.append('\n').append(line);

                    if (i > 0 && bestDetailLine.isBlank()) {
                        bestDetailLine = line.trim();
                    }
                }
            }

            try {
                Object patch = s.getComponentsPatch();
                if (patch != null) blob.append("\n").append(patch.toString());
            } catch (Throwable ignored) {}

            String blobLower = blob.toString().toLowerCase(Locale.ROOT);

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
                            if (clicked == null || clicked.isEmpty()) return true;

                            String k = keyOf(clicked);

                            if (isCatalogKeyDisabledBecauseAlreadyOffered(k)) {
                                if (button == 0) {
                                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Catalog click ignored (already offered): key={}", k);
                                    return true;
                                }
                                return true;
                            }

                            if (button == 0) {
                                if (selectedKeys.contains(k)) selectedKeys.remove(k);
                                else selectedKeys.add(k);

                                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Catalog selection toggled: key={} selectedCount={}", k, selectedKeys.size());
                                return true;
                            }
                        }
                    }
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] SearchCatalogScreen.mouseClicked failed (soft): {}", t.toString());
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isCatalogKeyDisabledBecauseAlreadyOffered(String key) {
        try {
            if (key == null || key.isBlank()) return false;
            Set<String> disabled = computeAlreadyOfferedResultKeysSafe();
            return disabled.contains(key);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] isCatalogKeyDisabledBecauseAlreadyOffered failed (soft): {}", t.toString());
            return false;
        }
    }

    private void pruneSelectionAgainstAlreadyOffered() {
        try {
            if (selectedKeys.isEmpty()) return;

            Set<String> disabled = computeAlreadyOfferedResultKeysSafe();
            if (disabled.isEmpty()) return;

            int before = selectedKeys.size();
            for (Iterator<String> it = selectedKeys.iterator(); it.hasNext(); ) {
                String k = it.next();
                if (k != null && disabled.contains(k)) it.remove();
            }
            int after = selectedKeys.size();

            if (after != before) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Catalog selection pruned (already offered): {} -> {}", before, after);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] pruneSelectionAgainstAlreadyOffered failed (soft): {}", t.toString());
        }
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
            try {
                int gridH = Math.max(1, computeGridBottom() - GRID_TOP);
                if (scrollBar != null) scrollBar.setBounds(computeScrollbarX(), GRID_TOP, SCROLLBAR_W, gridH);
            } catch (Throwable ignored) {}

            final Set<String> disabledKeys = computeAlreadyOfferedResultKeysSafe();
            final Set<String> lockedKeys = computeLockedOfferedResultKeysSafe();

            try {
                if (!disabledKeys.isEmpty() && !selectedKeys.isEmpty()) {
                    boolean removed = false;
                    for (Iterator<String> it = selectedKeys.iterator(); it.hasNext(); ) {
                        String k = it.next();
                        if (k != null && disabledKeys.contains(k)) {
                            it.remove();
                            removed = true;
                        }
                    }
                    if (removed) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] Catalog selection pruned during render (already offered) (selectedNow={})", selectedKeys.size());
                    }
                }
            } catch (Throwable ignored) {}

            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            gg.drawCenteredString(this.font, Component.translatable("ezvr.catalog.header"), this.width / 2, 44, 0xFFFFFF);

            if (awaitingServerData) {
                gg.drawCenteredString(this.font, Component.literal("Loading…").withStyle(ChatFormatting.GRAY), this.width / 2, 66, 0xFFFFFF);
                return;
            }

            gg.drawCenteredString(this.font,
                    Component.literal("Items available: ").withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(catalogFiltered.size())).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("   Selected: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(String.valueOf(selectedKeys.size())).withStyle(ChatFormatting.WHITE)),
                    this.width / 2, 66, 0xFFFFFF);

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

                    if (x + ITEM_SIZE > gridRight) continue;

                    ItemStack s = catalogFiltered.get(idx);
                    if (s == null || s.isEmpty()) continue;

                    String k = keyOf(s);
                    boolean disabled = (k != null && disabledKeys.contains(k));
                    boolean locked = (k != null && lockedKeys.contains(k));

                    boolean sel = !disabled && selectedKeys.contains(k);
                    if (sel || locked) {
                        int outline = 0xFF66FF66;
                        gg.renderOutline(x - 1, y - 1, ITEM_SIZE + 2, ITEM_SIZE + 2, outline);
                    }

                    if (disabled) {
                        // light darkening so the “disabled” state reads even for bright items
                        gg.fill(x, y, x + ITEM_SIZE, y + ITEM_SIZE, 0x30000000);

                        // actual item texture at 50% opacity
                        ezvr$renderItemWithAlpha(gg, s, x, y, 0.5f);

                        // keep decorations readable
                        gg.renderItemDecorations(this.font, s, x, y);

                        // subtle outline
                        gg.renderOutline(x, y, ITEM_SIZE, ITEM_SIZE, 0x60FFFFFF);
                    } else {
                        gg.renderItem(s, x, y);
                        gg.renderItemDecorations(this.font, s, x, y);
                    }
                }

                if (y + ITEM_SIZE > gridBottom) break;
            }

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
            VillagerOverhaul.LOG().error("[VillagerOverhaul] SearchCatalogScreen.render failed", t);
        }
    }

    private Set<String> computeAlreadyOfferedResultKeysSafe() {
        Set<String> out = new HashSet<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return out;

            if (!(mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu)) {
                return out;
            }

            net.minecraft.world.item.trading.MerchantOffers offers;
            try {
                offers = menu.getOffers();
            } catch (Throwable t) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] computeAlreadyOfferedResultKeysSafe: menu.getOffers() failed (soft): {}", t.toString());
                return out;
            }

            if (offers == null || offers.isEmpty()) return out;

            int limit = Math.min(offers.size(), 128);
            for (int i = 0; i < limit; i++) {
                try {
                    net.minecraft.world.item.trading.MerchantOffer offer = offers.get(i);
                    if (offer == null) continue;

                    ItemStack res = offer.getResult();
                    if (res == null || res.isEmpty()) continue;

                    String k = keyOf(res);
                    if (k != null && !k.isBlank()) out.add(k);
                } catch (Throwable ignored) {}
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] computeAlreadyOfferedResultKeysSafe: offers={} disabledKeys={}", offers.size(), out.size());
            return out;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] computeAlreadyOfferedResultKeysSafe failed (soft): {}", t.toString());
            return out;
        }
    }

    private Set<String> computeLockedOfferedResultKeysSafe() {
        Set<String> out = new HashSet<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return out;
            if (!(mc.player.containerMenu instanceof MerchantMenu menu)) return out;

            long activeMask = resolveCurrentLockMask(menu);
            if (activeMask == 0L) return out;

            var offers = menu.getOffers();
            if (offers == null || offers.isEmpty()) return out;

            int limit = Math.min(Math.min(offers.size(), 63), 128);
            for (int i = 0; i < limit; i++) {
                if ((activeMask & (1L << i)) == 0L) continue;

                try {
                    var offer = offers.get(i);
                    if (offer == null) continue;

                    ItemStack res = offer.getResult();
                    if (res == null || res.isEmpty()) continue;

                    String k = keyOf(res);
                    if (k != null && !k.isBlank()) out.add(k);
                } catch (Throwable ignored) {}
            }

            return out;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] computeLockedOfferedResultKeysSafe failed (soft): {}", t.toString());
            return out;
        }
    }

    private long resolveCurrentLockMask(MerchantMenu menu) {
        try {
            long cached = ClientTradeLockCache.getMaskForContainer(menu.containerId);
            if (cached != 0L) return cached;
            return lockMask;
        } catch (Throwable t) {
            return lockMask;
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
                        ItemStack icon = ClientCostIcon.costIcon();
                        gg.renderItem(icon, iconX, iconY);
                        gg.renderItemDecorations(this.font, icon, iconX, iconY);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] renderHourlyPreviewLine emerald draw failed (soft): {}", t.toString());
                    }

                    x = iconX + INLINE_ICON_SIZE;
                }
            }

            gg.pose().popPose();

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] renderHourlyPreviewLine failed (soft): {}", t.toString());
        }
    }

    private InlineLinePlan buildHourlyInlinePlan() {
        try {
            InlineLinePlan plan = new InlineLinePlan();

            plan.parts.add(new InlinePart(
                    Component.literal("Hourly cost: ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(String.valueOf(Math.max(0, hourlyCost))).withStyle(ChatFormatting.WHITE)),
                    true
            ));

            plan.parts.add(new InlinePart(Component.literal("   "), false));

            if (manualCost >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Manual: ").withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(String.valueOf(Math.max(0, manualCost))).withStyle(ChatFormatting.WHITE)),
                        true
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

            if (effectivePaidOffers >= 0) {
                plan.parts.add(new InlinePart(
                        Component.literal("Paid offers: ").withStyle(ChatFormatting.RED)
                                .append(Component.literal(String.valueOf(Math.max(0, effectivePaidOffers))).withStyle(ChatFormatting.WHITE)),
                        false
                ));
                plan.parts.add(new InlinePart(Component.literal("   "), false));
            }

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

            while (!plan.parts.isEmpty()) {
                InlinePart last = plan.parts.get(plan.parts.size() - 1);
                if (last != null && last.text != null && "   ".equals(last.text.getString())) {
                    plan.parts.remove(plan.parts.size() - 1);
                } else break;
            }

            return plan;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] buildHourlyInlinePlan failed (soft): {}", t.toString());
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

            Minecraft mc = Minecraft.getInstance();
            Player player = (mc == null) ? null : mc.player;

            long vUnits = 0L;
            try {
                String k = keyOf(stack);
                vUnits = (k == null) ? 0L : accumulatedValueVByKey.getOrDefault(k, 0L);
                if (vUnits < 0L) vUnits = 0L;
            } catch (Throwable ignored) {}

            if (player != null) {
                TooltipFlag flag = getTooltipFlagSafe(mc);
                List<Component> lines = TooltipCompat.getTooltipLines(stack, player, flag);
                ArrayList<Component> withExtra = new ArrayList<>(Math.max(1, lines == null ? 0 : lines.size()) + 1);
                if (lines != null) withExtra.addAll(lines);

                Component modLine = buildTooltipModLine(stack);
                if (modLine != null) {
                    withExtra.add(modLine);
                }

                if (vUnits > 0L) {
                    withExtra.add(
                            Component.literal(String.valueOf(vUnits)).withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal(" Rerolls ").withStyle(ChatFormatting.GRAY))
                                    .append(Component.literal("(").withStyle(ChatFormatting.DARK_GRAY))
                                    .append(ClientCostIcon.costText(formatEmeraldsFromV(vUnits)))
                                    .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY))
                    );
                }

                gg.renderTooltip(this.font, withExtra, TooltipCompat.getTooltipImage(stack), (int) mouseX, (int) mouseY);
            } else {
                gg.renderTooltip(this.font, stack, (int) mouseX, (int) mouseY);
            }
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

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ezvr$renderVanillaItemTooltip failed (soft): {}", t.toString());
        }
    }

    private static Component buildTooltipModLine(ItemStack stack) {
        try {
            String label = resolveTooltipModLabel(stack);
            if (label == null || label.isBlank()) return null;
            return Component.literal("Mod: ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(label).withStyle(ChatFormatting.BLUE));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveTooltipModLabel(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return "";

            String sourceNamespace = resolvePrimaryTooltipNamespace(stack);
            if (sourceNamespace == null || sourceNamespace.isBlank()) return "";

            if ("__multiple__".equals(sourceNamespace)) {
                return "Multiple Mods";
            }

            if ("minecraft".equals(sourceNamespace)) {
                return "Minecraft";
            }

            try {
                return ModList.get()
                        .getModContainerById(sourceNamespace)
                        .map(mc -> mc.getModInfo().getDisplayName())
                        .orElseGet(() -> prettifyNamespace(sourceNamespace));
            } catch (Throwable ignored) {
                return prettifyNamespace(sourceNamespace);
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String resolvePrimaryTooltipNamespace(ItemStack stack) {
        try {
            String enchantNs = resolveSingleEnchantmentNamespace(stack);
            if (enchantNs != null && !enchantNs.isBlank()) {
                return enchantNs;
            }
        } catch (Throwable ignored) {}

        try {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId != null && itemId.getNamespace() != null && !itemId.getNamespace().isBlank()) {
                return itemId.getNamespace();
            }
        } catch (Throwable ignored) {}

        return "";
    }

    private static String resolveSingleEnchantmentNamespace(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return "";
            if (!stack.has(DataComponents.ENCHANTMENTS) && !stack.has(DataComponents.STORED_ENCHANTMENTS)) return "";

            ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            if (enchantments == null || enchantments.isEmpty()) return "";

            LinkedHashSet<String> namespaces = new LinkedHashSet<>();
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
                if (entry == null) continue;
                Holder<Enchantment> holder = entry.getKey();
                if (holder == null) continue;

                String ns = holder.unwrapKey()
                        .map(key -> key.location().getNamespace())
                        .orElse("");
                if (ns.isBlank()) continue;
                namespaces.add(ns);
                if (namespaces.size() > 1) {
                    return "__multiple__";
                }
            }

            return namespaces.isEmpty() ? "" : namespaces.iterator().next();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String prettifyNamespace(String namespace) {
        try {
            if (namespace == null || namespace.isBlank()) return "";
            String[] parts = namespace.split("[_\\-.]+");
            StringBuilder out = new StringBuilder(namespace.length() + 8);
            for (String part : parts) {
                if (part == null || part.isBlank()) continue;
                if (out.length() > 0) out.append(' ');
                if (part.length() == 1) {
                    out.append(Character.toUpperCase(part.charAt(0)));
                } else {
                    out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
            }
            return out.isEmpty() ? namespace : out.toString();
        } catch (Throwable ignored) {
            return namespace == null ? "" : namespace;
        }
    }

    private static TooltipFlag getTooltipFlagSafe(Minecraft mc) {
        try {
            boolean adv = false;
            try {
                if (mc != null && mc.options != null) {
                    adv = mc.options.advancedItemTooltips;
                }
            } catch (Throwable ignored) {
                adv = false;
            }
            return adv ? TooltipFlag.Default.ADVANCED : TooltipFlag.Default.NORMAL;
        } catch (Throwable ignored) {
            return TooltipFlag.Default.NORMAL;
        }
    }

    private static String formatEmeraldsFromV(long vUnits) {
        try {
            int costPerOffer = 0;
            try {
                var snap = org.z2six.villageroverhaul.network.ClientSyncedConfig.get();
                costPerOffer = snap == null ? 0 : Math.max(0, snap.costPerOffer);
            } catch (Throwable ignored) {
                costPerOffer = 0;
            }
            long cost = Math.max(0L, vUnits) * (long) Math.max(0, costPerOffer);
            if (cost < 0L) cost = Long.MAX_VALUE;
            return String.valueOf(cost);
        } catch (Throwable ignored) {
            return "0";
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
        private static Method mGetTooltipLines_New; // (Item$TooltipContext, Player, TooltipFlag)
        private static Method mGetTooltipLines_Old; // (Player, TooltipFlag)
        private static Method mGetTooltipImage; // getTooltipImage(): Optional
        private static Method mTooltipContextOfLevel; // Item$TooltipContext.of(Level)
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

        static java.util.Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
            try {
                if (!lookedUp) lookupMethods();
                if (stack == null) return java.util.Optional.empty();
                if (mGetTooltipImage == null) return java.util.Optional.empty();
                Object res = mGetTooltipImage.invoke(stack);
                if (res instanceof java.util.Optional<?> opt) {
                    Object inner = opt.orElse(null);
                    if (inner instanceof TooltipComponent tc) {
                        return java.util.Optional.of(tc);
                    }
                }
                return java.util.Optional.empty();
            } catch (Throwable ignored) {
                return java.util.Optional.empty();
            }
        }

        private static void lookupMethods() {
            lookedUp = true;
            try {
                Class<?> tooltipContextClz = Class.forName("net.minecraft.world.item.Item$TooltipContext");
                try {
                    mGetTooltipLines_New = ItemStack.class.getMethod("getTooltipLines", tooltipContextClz, Player.class, TooltipFlag.class);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipCompat: found ItemStack.getTooltipLines(Item$TooltipContext, Player, TooltipFlag)");
                } catch (Throwable ignored) {
                    mGetTooltipLines_New = null;
                }

                try {
                    Class<?> levelClz = Class.forName("net.minecraft.world.level.Level");
                    mTooltipContextOfLevel = tooltipContextClz.getMethod("of", levelClz);
                } catch (Throwable ignored) {
                    mTooltipContextOfLevel = null;
                }
            } catch (Throwable ignored) {
                mGetTooltipLines_New = null;
                mTooltipContextOfLevel = null;
            }

            try {
                mGetTooltipLines_Old = ItemStack.class.getMethod("getTooltipLines", Player.class, TooltipFlag.class);
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipCompat: found ItemStack.getTooltipLines(Player, TooltipFlag)");
            } catch (Throwable ignored) {
                mGetTooltipLines_Old = null;
            }

            try {
                mGetTooltipImage = ItemStack.class.getMethod("getTooltipImage");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] TooltipCompat: found ItemStack.getTooltipImage()");
            } catch (Throwable ignored) {
                mGetTooltipImage = null;
            }
        }

        private static Object createTooltipContext(Player player) {
            try {
                if (player == null) return null;
                if (mTooltipContextOfLevel == null) return null;
                Object level = null;
                try { level = player.level(); } catch (Throwable ignored) { level = null; }
                if (level == null) return null;
                return mTooltipContextOfLevel.invoke(null, level);
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
