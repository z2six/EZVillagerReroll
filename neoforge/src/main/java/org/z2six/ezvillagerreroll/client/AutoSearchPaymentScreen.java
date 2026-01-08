// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/AutoSearchPaymentScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.network.PacketDeclineAutoSearchSettlement;
import org.z2six.ezvillagerreroll.network.PacketPayAutoSearchSettlement;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Settlement/payment UI for auto-search.
 *
 * Layout requirements:
 * - Each element on its own horizontal row (vertical stack):
 *   Auto-search complete
 *   Time
 *   Hourly cost
 *   Final cost
 *   Pay to keep the ...
 *   Row of items (if pay)  [no "IF YOU PAY" label]
 *   Pay button
 *   Row of items (if decline) [no "IF YOU DECLINE" label]
 *   Decline button
 *
 * Highlight rules:
 * - Green border on BOTH rows for indices that were locked BEFORE auto-search (declineLockMask bit).
 * - Yellow border on PAY row for indices that were "found" by auto-reroll (requested targets).
 *   This screen supports multiple requested-string formats (plain item id, or "key" strings that contain/extend it).
 */
public final class AutoSearchPaymentScreen extends Screen {

    private static final String TAG_WRAP_VALUE = "v";

    // visuals
    private static final int SLOT = 18;
    private static final int GAP = 2;
    private static final int STRIDE = SLOT + GAP;

    private static final int OUTLINE_GREEN = 0xFF00FF00;
    private static final int OUTLINE_YELLOW = 0xFFFFD000;

    private final int villagerEntityId;
    private final int hourlyCost;
    private final int finalCost;
    private final int elapsedTicks;

    // snapshots
    private final ListTag offersIfPayTag;
    private final ListTag offersIfDeclineTag;

    // "locks before" mask (apply to both rows)
    private final long declineLockMask;

    // requested targets strings (may be item ids, or richer keys)
    private final Set<String> requestedTargets;

    // decoded offers + extracted results
    private MerchantOffers offersIfPayDecoded;
    private MerchantOffers offersIfDeclineDecoded;
    private List<ItemStack> payResults;
    private List<ItemStack> declineResults;

    // computed found indices (yellow on PAY row)
    private Set<Integer> foundPayIndices;

    // UI
    private Button btnPay;
    private Button btnDecline;
    private boolean sentAction = false;

    public AutoSearchPaymentScreen(
            int villagerEntityId,
            int hourlyCost,
            int finalCost,
            int elapsedTicks,
            ListTag offersIfPay,
            ListTag offersIfDecline,
            long declineLockMask,
            List<String> requestedTargets
    ) {
        super(Component.translatable("ezvr.auto_search.payment.title"));
        this.villagerEntityId = villagerEntityId;
        this.hourlyCost = Math.max(0, hourlyCost);
        this.finalCost = Math.max(0, finalCost);
        this.elapsedTicks = Math.max(0, elapsedTicks);

        this.offersIfPayTag = deepCopyOfferList(offersIfPay);
        this.offersIfDeclineTag = deepCopyOfferList(offersIfDecline);
        this.declineLockMask = declineLockMask;

        HashSet<String> tmp = new HashSet<>();
        try {
            if (requestedTargets != null) {
                int n = Math.min(256, requestedTargets.size());
                for (int i = 0; i < n; i++) {
                    String s = requestedTargets.get(i);
                    if (s == null) continue;
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    tmp.add(s);
                }
            }
        } catch (Throwable ignored) {}
        this.requestedTargets = tmp;

        this.offersIfPayDecoded = null;
        this.offersIfDeclineDecoded = null;
        this.payResults = null;
        this.declineResults = null;
        this.foundPayIndices = null;
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    protected void init() {
        try {
            super.init();

            decodeSnapshotsIfPossible();

            // Create buttons first; positions set by layout pass.
            btnPay = Button.builder(Component.literal("Pay").withStyle(ChatFormatting.GREEN), b -> onPay())
                    .pos(0, 0)
                    .size(140, 20)
                    .build();

            btnDecline = Button.builder(Component.literal("Decline").withStyle(ChatFormatting.RED), b -> onDecline())
                    .pos(0, 0)
                    .size(140, 20)
                    .build();

            addRenderableWidget(btnPay);
            addRenderableWidget(btnDecline);

            // Set positions based on the requested vertical stack layout.
            layoutButtons();

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen opened: villagerEntityId={} hourlyCost={} finalCost={} elapsedTicks={} payOffersTag={} declineOffersTag={} lockMask={} requestedTargets={}",
                    villagerEntityId, hourlyCost, finalCost, elapsedTicks,
                    offersIfPayTag == null ? -1 : offersIfPayTag.size(),
                    offersIfDeclineTag == null ? -1 : offersIfDeclineTag.size(),
                    Long.toUnsignedString(declineLockMask),
                    this.requestedTargets.size()
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.init failed", t);
        }
    }

    private void layoutButtons() {
        try {
            if (btnPay == null || btnDecline == null) return;

            // Mirror the exact render layout so buttons land under the correct rows.
            int cx = this.width / 2;

            int y = 18;      // title
            y += 18;         // time
            y += 14;         // hourly
            y += 16;         // final
            y += 16;         // hint
            y += 18;         // pay row
            y += (SLOT + 8); // pay button
            y += 28;         // decline row
            y += (SLOT + 8); // decline button

            // But we need actual Y’s:
            int y0 = 18;
            int yTitle = y0;
            int yTime = yTitle + 18;
            int yHourly = yTime + 14;
            int yFinal = yHourly + 16;
            int yHint = yFinal + 16;

            int yPayRow = yHint + 18;
            int yPayButton = yPayRow + SLOT + 8;

            int yDeclineRow = yPayButton + 28;
            int yDeclineButton = yDeclineRow + SLOT + 8;

            // Clamp into screen height so it doesn't go off-screen on small windows.
            // If clamped, layout still stacks; worst case, buttons are near bottom.
            int bottomPad = 10;
            int maxBtnY = Math.max(bottomPad, this.height - bottomPad - 20);

            yPayButton = Math.min(yPayButton, maxBtnY);
            yDeclineButton = Math.min(yDeclineButton, maxBtnY);

            btnPay.setPosition(cx - (btnPay.getWidth() / 2), yPayButton);
            btnDecline.setPosition(cx - (btnDecline.getWidth() / 2), yDeclineButton);

            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.layoutButtons: payBtnY={} declineBtnY={} height={}",
                    yPayButton, yDeclineButton, this.height);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.layoutButtons failed (soft): {}", t.toString());
        }
    }

    private void decodeSnapshotsIfPossible() {
        try {
            if (offersIfPayDecoded != null && offersIfDeclineDecoded != null && payResults != null && declineResults != null && foundPayIndices != null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: decodeSnapshotsIfPossible skipped (no client level).");
                return;
            }

            if (offersIfPayDecoded == null) offersIfPayDecoded = decodeOffersList(mc, offersIfPayTag);
            if (offersIfDeclineDecoded == null) offersIfDeclineDecoded = decodeOffersList(mc, offersIfDeclineTag);

            if (payResults == null) payResults = extractResults(offersIfPayDecoded);
            if (declineResults == null) declineResults = extractResults(offersIfDeclineDecoded);

            if (foundPayIndices == null) foundPayIndices = computeFoundPayIndices(payResults, declineResults);

            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: decoded snapshots (payOffers={} declineOffers={} payResults={} declineResults={} foundPayIndices={})",
                    offersIfPayDecoded == null ? -1 : offersIfPayDecoded.size(),
                    offersIfDeclineDecoded == null ? -1 : offersIfDeclineDecoded.size(),
                    payResults == null ? -1 : payResults.size(),
                    declineResults == null ? -1 : declineResults.size(),
                    foundPayIndices == null ? -1 : foundPayIndices.size()
            );

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.decodeSnapshotsIfPossible failed (soft): {}", t.toString());
        }
    }

    private Set<Integer> computeFoundPayIndices(List<ItemStack> pay, List<ItemStack> decline) {
        try {
            HashSet<Integer> out = new HashSet<>();
            if (pay == null || pay.isEmpty()) return out;
            if (requestedTargets == null || requestedTargets.isEmpty()) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.computeFoundPayIndices: requestedTargets empty -> no yellow highlights.");
                return out;
            }

            int n = pay.size();
            for (int i = 0; i < n; i++) {
                ItemStack p = safeCopy(pay.get(i));
                if (p.isEmpty()) continue;

                // Must match a requested target (flexibly).
                if (!matchesRequestedFlexible(p)) continue;

                // Prefer highlighting only when the offer changed vs baseline (pre-search snapshot).
                boolean changed = true;
                if (decline != null && i < decline.size()) {
                    ItemStack d = safeCopy(decline.get(i));
                    changed = !sameItemSameComponentsSafe(p, d);
                }

                if (changed) out.add(i);
            }

            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.computeFoundPayIndices: computed={} from paySize={} declineSize={} requestedTargets={}",
                    out.size(),
                    pay == null ? -1 : pay.size(),
                    decline == null ? -1 : decline.size(),
                    requestedTargets.size());

            return out;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.computeFoundPayIndices failed (soft): {}", t.toString());
            return new HashSet<>();
        }
    }

    private boolean matchesRequestedFlexible(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return false;
            if (requestedTargets == null || requestedTargets.isEmpty()) return false;

            var key = BuiltInRegistries.ITEM.getKey(st.getItem());
            if (key == null) return false;

            String itemId = key.toString(); // "namespace:path"
            if (requestedTargets.contains(itemId)) return true;

            // Allow richer "key" strings, e.g.:
            // "minecraft:enchanted_book{components...}" or "minecraft:paper|..." or "minecraft:paper#..."
            for (String s : requestedTargets) {
                if (s == null) continue;
                if (s.equals(itemId)) return true;

                // strict prefix variants first
                if (s.startsWith(itemId + "{")) return true;
                if (s.startsWith(itemId + "|")) return true;
                if (s.startsWith(itemId + "#")) return true;
                if (s.startsWith(itemId + "@")) return true;
                if (s.startsWith(itemId + " ")) return true;

                // looser: contains (handles "key=namespace:path" formats)
                if (s.contains(itemId)) return true;
            }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean sameItemSameComponentsSafe(ItemStack a, ItemStack b) {
        try {
            if (a == null) a = ItemStack.EMPTY;
            if (b == null) b = ItemStack.EMPTY;

            // Try ItemStack.isSameItemSameComponents(a,b) (exists in recent versions).
            try {
                Method m = ItemStack.class.getMethod("isSameItemSameComponents", ItemStack.class, ItemStack.class);
                Object r = m.invoke(null, a, b);
                if (r instanceof Boolean bb) return bb;
            } catch (Throwable ignored) {}

            // Fallback: item equality only (less precise).
            return a.getItem() == b.getItem();
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<ItemStack> extractResults(MerchantOffers offers) {
        try {
            if (offers == null || offers.isEmpty()) return List.of();
            ArrayList<ItemStack> out = new ArrayList<>(offers.size());
            for (int i = 0; i < offers.size(); i++) {
                try {
                    MerchantOffer o = offers.get(i);
                    if (o == null) {
                        out.add(ItemStack.EMPTY);
                        continue;
                    }
                    ItemStack r = o.getResult();
                    out.add(r == null ? ItemStack.EMPTY : r.copy());
                } catch (Throwable t) {
                    out.add(ItemStack.EMPTY);
                }
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static ListTag deepCopyOfferList(ListTag src) {
        try {
            ListTag out = new ListTag();
            if (src == null) return out;

            int n = Math.min(256, src.size());
            for (int i = 0; i < n; i++) {
                try {
                    CompoundTag wrap = src.getCompound(i);
                    if (wrap != null) out.add(wrap.copy());
                } catch (Throwable ignored) {}
            }
            return out;
        } catch (Throwable t) {
            return new ListTag();
        }
    }

    private static MerchantOffers decodeOffersList(Minecraft mc, ListTag list) {
        try {
            if (mc == null || mc.level == null) return null;
            if (list == null || list.isEmpty()) return new MerchantOffers();

            var ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
            MerchantOffers offers = new MerchantOffers();

            int n = Math.min(256, list.size());
            for (int i = 0; i < n; i++) {
                final int idx = i;

                CompoundTag wrap;
                try {
                    wrap = list.getCompound(i);
                } catch (Throwable t) {
                    continue;
                }
                if (wrap == null) continue;

                Tag tag;
                try {
                    tag = wrap.get(TAG_WRAP_VALUE);
                } catch (Throwable t) {
                    tag = null;
                }
                if (tag == null) continue;

                try {
                    var res = MerchantOffer.CODEC.parse(ops, tag);
                    res.resultOrPartial(msg ->
                            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: offer decode failed (idx={}): {}", idx, msg)
                    ).ifPresent(offers::add);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: offer decode threw (idx={}): {}", idx, t.toString());
                }
            }

            return offers;
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.decodeOffersList failed (soft): {}", t.toString());
            return null;
        }
    }

    private void onPay() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Pay ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Pay ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketPayAutoSearchSettlement(villagerEntityId={})", villagerEntityId);
            Network.sendToServer(new PacketPayAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onPay failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void onDecline() {
        try {
            if (sentAction) {
                EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen: Decline ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                EZVillagerReroll.LOG().warn("[EZVR] AutoSearchPaymentScreen: Decline ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            setButtonsActive(false);

            EZVillagerReroll.LOG().info("[EZVR] AutoSearchPaymentScreen: sending PacketDeclineAutoSearchSettlement(villagerEntityId={})", villagerEntityId);
            Network.sendToServer(new PacketDeclineAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.onDecline failed", t);
            sentAction = false;
            setButtonsActive(true);
        }
    }

    private void setButtonsActive(boolean active) {
        try {
            if (btnPay != null) btnPay.active = active;
            if (btnDecline != null) btnDecline.active = active;
        } catch (Throwable ignored) {}
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        super.resize(mc, width, height);
        try {
            layoutButtons();
        } catch (Throwable ignored) {}
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            decodeSnapshotsIfPossible();

            int cx = this.width / 2;

            // --- Vertical stack layout (each on its own row) ---
            int y = 18;

            gg.drawCenteredString(this.font,
                    Component.literal("Auto-search complete").withStyle(ChatFormatting.GOLD),
                    cx, y, 0xFFFFFF);

            y += 18;

            int seconds = elapsedTicks / 20;
            drawCenteredKeyValueLine(gg, cx, y, "Time:", seconds + "s");

            y += 14;

            drawCostLineWithEmerald(gg, cx, y, "Hourly cost:", hourlyCost);

            y += 16;

            drawCostLineWithEmerald(gg, cx, y, "Final cost:", finalCost);

            y += 16;

            gg.drawCenteredString(this.font,
                    sentAction
                            ? Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY)
                            : Component.literal("Pay to keep the offers, or decline to revert.").withStyle(ChatFormatting.DARK_GRAY),
                    cx, y, 0xFFFFFF);

            // Pay items row (no label)
            y += 18;

            ItemStack hovered = ItemStack.EMPTY;

            hovered = renderResultsRow(
                    gg,
                    cx,
                    y,
                    payResults,
                    true,   // allow yellow highlight on pay row
                    mouseX,
                    mouseY,
                    hovered
            );

            // Pay button sits below the pay row; init/resize places it, but we keep it consistent if render runs before init finishes.
            y += SLOT + 8;

            // Decline items row (no label)
            // place it below the pay button visually; we don't use btnPay.y because that's UI state,
            // but we keep the same spacing so it matches layoutButtons().
            y += 28;

            hovered = renderResultsRow(
                    gg,
                    cx,
                    y,
                    declineResults,
                    false,  // no yellow highlight on decline row
                    mouseX,
                    mouseY,
                    hovered
            );

            // Tooltip last
            if (hovered != null && !hovered.isEmpty()) {
                try {
                    gg.renderTooltip(this.font, hovered, mouseX, mouseY);
                } catch (Throwable t) {
                    EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.renderTooltip failed (soft): {}", t.toString());
                }
            }

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] AutoSearchPaymentScreen.render failed", t);
        }
    }

    /**
     * Single-row item render:
     * - Green outline always for locked-before mask indices (both rows).
     * - Yellow outline only on pay row for computed foundPayIndices.
     */
    private ItemStack renderResultsRow(
            GuiGraphics gg,
            int centerX,
            int topY,
            List<ItemStack> stacks,
            boolean isPayRow,
            int mouseX,
            int mouseY,
            ItemStack currentHovered
    ) {
        try {
            if (stacks == null || stacks.isEmpty()) {
                gg.drawCenteredString(this.font,
                        Component.literal("No items").withStyle(ChatFormatting.DARK_GRAY),
                        centerX, topY + 4, 0xFFFFFF);
                return currentHovered;
            }

            int maxSlots = Math.max(1, (this.width - 40) / STRIDE);
            int shown = Math.min(maxSlots, stacks.size());

            int totalW = (shown * SLOT) + ((shown - 1) * GAP);
            int startX = centerX - (totalW / 2);

            for (int i = 0; i < shown; i++) {
                ItemStack st = safeCopy(stacks.get(i));

                int x = startX + (i * STRIDE);
                int y = topY;

                // subtle slot backdrop
                gg.fill(x - 1, y - 1, x + SLOT + 1, y + SLOT + 1, 0x66000000);

                if (!st.isEmpty()) {
                    gg.renderItem(st, x, y);
                    gg.renderItemDecorations(this.font, st, x, y);
                }

                // Green on BOTH rows for indices locked before.
                if (isLockedIndex(i)) {
                    drawOutline(gg, x - 1, y - 1, SLOT + 2, SLOT + 2, OUTLINE_GREEN);
                }

                // Yellow only on PAY row for found indices.
                if (isPayRow && isFoundPayIndex(i)) {
                    drawOutline(gg, x - 1, y - 1, SLOT + 2, SLOT + 2, OUTLINE_YELLOW);
                }

                // Hover -> tooltip
                if (!st.isEmpty()) {
                    if (mouseX >= x && mouseX < (x + SLOT) && mouseY >= y && mouseY < (y + SLOT)) {
                        currentHovered = st;
                    }
                }
            }

            // optional "more" indicator
            if (stacks.size() > shown) {
                gg.drawCenteredString(this.font,
                        Component.literal("+" + (stacks.size() - shown) + " more").withStyle(ChatFormatting.DARK_GRAY),
                        centerX, topY + SLOT + 4, 0xFFFFFF);
            }

            return currentHovered;

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.renderResultsRow failed (soft): {}", t.toString());
            return currentHovered;
        }
    }

    private boolean isLockedIndex(int idx) {
        try {
            if (idx < 0 || idx >= 63) return false;
            return (declineLockMask & (1L << idx)) != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isFoundPayIndex(int idx) {
        try {
            if (foundPayIndices == null || foundPayIndices.isEmpty()) return false;
            return foundPayIndices.contains(idx);
        } catch (Throwable t) {
            return false;
        }
    }

    private void drawOutline(GuiGraphics gg, int x, int y, int w, int h, int argb) {
        try {
            gg.fill(x, y, x + w, y + 1, argb);                 // top
            gg.fill(x, y + h - 1, x + w, y + h, argb);         // bottom
            gg.fill(x, y, x + 1, y + h, argb);                 // left
            gg.fill(x + w - 1, y, x + w, y + h, argb);         // right
        } catch (Throwable ignored) {}
    }

    private void drawCenteredKeyValueLine(GuiGraphics gg, int cx, int y, String key, String value) {
        try {
            Component c = Component.literal(key + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
            gg.drawCenteredString(this.font, c, cx, y, 0xFFFFFF);
        } catch (Throwable ignored) {}
    }

    private void drawCostLineWithEmerald(GuiGraphics gg, int cx, int y, String label, int value) {
        try {
            String labelStr = label + " ";
            String valueStr = String.valueOf(Math.max(0, value));

            int wLabel = this.font.width(labelStr);
            int wValue = this.font.width(valueStr);

            int iconW = 16;
            int pad = 4;

            int total = wLabel + wValue + pad + iconW;
            int x = cx - (total / 2);

            gg.drawString(this.font, Component.literal(labelStr).withStyle(ChatFormatting.GRAY), x, y, 0xFFFFFF, false);
            gg.drawString(this.font, Component.literal(valueStr).withStyle(ChatFormatting.WHITE), x + wLabel, y, 0xFFFFFF, false);

            int iconX = x + wLabel + wValue + pad;
            int iconY = y - 4;

            ItemStack em = new ItemStack(Items.EMERALD);
            gg.renderItem(em, iconX, iconY);
            gg.renderItemDecorations(this.font, em, iconX, iconY);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.drawCostLineWithEmerald failed (soft): {}", t.toString());
        }
    }

    private static ItemStack safeCopy(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return ItemStack.EMPTY;
            return s.copy();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * ESC closes locally; settlement remains pending server-side.
     */
    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] AutoSearchPaymentScreen.onClose failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
