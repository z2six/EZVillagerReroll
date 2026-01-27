// neoforge\src\main\java\org\z2six\villageroverhaul\client\AutoSearchPaymentScreen.java
package org.z2six.villageroverhaul.client;

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
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.TooltipFlag;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.network.autoReroll.PacketDeclineAutoSearchSettlement;
import org.z2six.villageroverhaul.network.autoReroll.PacketOpenAutoSearchPaymentScreen;
import org.z2six.villageroverhaul.network.autoReroll.PacketPayAutoSearchSettlement;

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
 *   Villager XP gained
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

    /**
     * Total villager XP that will be granted ONLY if Pay succeeds.
     * - If < 0, display will show "?" as unknown.
     */
    private final int totalVillagerXp;

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

    // reroll count
    private final int rerollCount;

    // Player-global accumulated V for items shown in this UI (server-auth; keyed by CatalogBuilder.keyOf)
    private final Map<String, Long> tooltipValueVByKey;

    // UI
    private Button btnPay;
    private Button btnDecline;
    private boolean sentAction = false;
    private Component statusLineOverride = null;

    /**
     * Back-compat constructor: older call-sites can keep using it.
     * We attempt to pull total XP + rerollCount from the client packet decode caches.
     */
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
        this(
                villagerEntityId,
                hourlyCost,
                finalCost,
                elapsedTicks,
                offersIfPay,
                offersIfDecline,
                declineLockMask,
                requestedTargets,
                PacketOpenAutoSearchPaymentScreen.popClientTotalVillagerXp(villagerEntityId),
                PacketOpenAutoSearchPaymentScreen.popClientRerollCount(villagerEntityId)
        );
    }

    /**
     * Existing constructor (call-sites that already pass XP can keep using it).
     * rerollCount will be pulled from cache if present, else 0.
     */
    public AutoSearchPaymentScreen(
            int villagerEntityId,
            int hourlyCost,
            int finalCost,
            int elapsedTicks,
            ListTag offersIfPay,
            ListTag offersIfDecline,
            long declineLockMask,
            List<String> requestedTargets,
            int totalVillagerXp
    ) {
        this(
                villagerEntityId,
                hourlyCost,
                finalCost,
                elapsedTicks,
                offersIfPay,
                offersIfDecline,
                declineLockMask,
                requestedTargets,
                totalVillagerXp,
                PacketOpenAutoSearchPaymentScreen.popClientRerollCount(villagerEntityId)
        );
    }

    /**
     * Full constructor.
     */
    public AutoSearchPaymentScreen(
            int villagerEntityId,
            int hourlyCost,
            int finalCost,
            int elapsedTicks,
            ListTag offersIfPay,
            ListTag offersIfDecline,
            long declineLockMask,
            List<String> requestedTargets,
            int totalVillagerXp,
            int rerollCount
    ) {
        super(Component.translatable("ezvr.auto_search.payment.title"));
        this.villagerEntityId = villagerEntityId;
        this.hourlyCost = Math.max(0, hourlyCost);
        this.finalCost = Math.max(0, finalCost);
        this.elapsedTicks = Math.max(0, elapsedTicks);

        this.totalVillagerXp = (totalVillagerXp < 0 ? -1 : Math.max(0, totalVillagerXp));
        this.rerollCount = Math.max(0, rerollCount);
        this.tooltipValueVByKey = PacketOpenAutoSearchPaymentScreen.popClientTooltipVByKey(villagerEntityId);

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

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen opened: villagerEntityId={} hourlyCost={} finalCost={} elapsedTicks={} totalVillagerXp={} rerollCount={} payOffersTag={} declineOffersTag={} lockMask={} requestedTargets={}",
                    villagerEntityId, hourlyCost, finalCost, elapsedTicks, totalVillagerXp, rerollCount,
                    offersIfPayTag == null ? -1 : offersIfPayTag.size(),
                    offersIfDeclineTag == null ? -1 : offersIfDeclineTag.size(),
                    Long.toUnsignedString(declineLockMask),
                    this.requestedTargets.size()
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoSearchPaymentScreen.init failed", t);
        }
    }

    private void layoutButtons() {
        try {
            if (btnPay == null || btnDecline == null) return;

            // Mirror the exact render layout so buttons land under the correct rows.
            int cx = this.width / 2;

            int y0 = 18;
            int yTitle = y0;
            int yTime = yTitle + 18;

            //  line
            int yRerolls = yTime + 14;

            int yHourly = yRerolls + 14;
            int yFinal = yHourly + 16;

            int yXp = yFinal + 16;
            int yHint = yXp + 16;

            int yPayRow = yHint + 18;
            int yPayButton = yPayRow + SLOT + 8;

            int yDeclineRow = yPayButton + 28;
            int yDeclineButton = yDeclineRow + SLOT + 8;

            // Clamp into screen height so it doesn't go off-screen on small windows.
            int bottomPad = 10;
            int maxBtnY = Math.max(bottomPad, this.height - bottomPad - 20);

            yPayButton = Math.min(yPayButton, maxBtnY);
            yDeclineButton = Math.min(yDeclineButton, maxBtnY);

            btnPay.setPosition(cx - (btnPay.getWidth() / 2), yPayButton);
            btnDecline.setPosition(cx - (btnDecline.getWidth() / 2), yDeclineButton);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.layoutButtons: payBtnY={} declineBtnY={} height={}",
                    yPayButton, yDeclineButton, this.height);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.layoutButtons failed (soft): {}", t.toString());
        }
    }

    private void decodeSnapshotsIfPossible() {
        try {
            if (offersIfPayDecoded != null && offersIfDeclineDecoded != null && payResults != null && declineResults != null && foundPayIndices != null) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: decodeSnapshotsIfPossible skipped (no client level).");
                return;
            }

            if (offersIfPayDecoded == null) offersIfPayDecoded = decodeOffersList(mc, offersIfPayTag);
            if (offersIfDeclineDecoded == null) offersIfDeclineDecoded = decodeOffersList(mc, offersIfDeclineTag);

            if (payResults == null) payResults = extractResults(offersIfPayDecoded);
            if (declineResults == null) declineResults = extractResults(offersIfDeclineDecoded);

            if (foundPayIndices == null) foundPayIndices = computeFoundPayIndices(payResults, declineResults);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: decoded snapshots (payOffers={} declineOffers={} payResults={} declineResults={} foundPayIndices={})",
                    offersIfPayDecoded == null ? -1 : offersIfPayDecoded.size(),
                    offersIfDeclineDecoded == null ? -1 : offersIfDeclineDecoded.size(),
                    payResults == null ? -1 : payResults.size(),
                    declineResults == null ? -1 : declineResults.size(),
                    foundPayIndices == null ? -1 : foundPayIndices.size()
            );

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.decodeSnapshotsIfPossible failed (soft): {}", t.toString());
        }
    }

    private Set<Integer> computeFoundPayIndices(List<ItemStack> pay, List<ItemStack> decline) {
        try {
            HashSet<Integer> out = new HashSet<>();

            if (pay == null || pay.isEmpty()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: pay empty.");
                return out;
            }
            if (requestedTargets == null || requestedTargets.isEmpty()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: requestedTargets empty -> no yellow highlights.");
                return out;
            }

            int n = pay.size();
            for (int i = 0; i < n; i++) {
                ItemStack p = safeCopy(pay.get(i));
                if (p.isEmpty()) continue;

                if (!isRequestedResultStack(p)) continue;

                boolean changed = true;
                if (decline != null && i < decline.size()) {
                    ItemStack d = safeCopy(decline.get(i));
                    changed = !sameItemSameComponentsSafe(p, d);
                }

                if (changed) {
                    out.add(i);

                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: YELLOW idx={} itemId={} keyOf={}",
                                i,
                                safeItemIdString(p),
                                safeCatalogKeyOf(p)
                        );
                    }
                } else {
                    if (VillagerOverhaul.LOG().isDebugEnabled()) {
                        VillagerOverhaul.LOG().debug(
                                "[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: matched requested but unchanged idx={} itemId={} keyOf={}",
                                i,
                                safeItemIdString(p),
                                safeCatalogKeyOf(p)
                        );
                    }
                }
            }

            if (out.isEmpty() && VillagerOverhaul.LOG().isDebugEnabled()) {
                String oneTarget = requestedTargets.stream().findFirst().orElse("<none>");
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: 0 matches. requestedTargetsSize={} exampleTarget={}",
                        requestedTargets.size(), oneTarget);

                int sample = Math.min(5, pay.size());
                for (int i = 0; i < sample; i++) {
                    ItemStack p = safeCopy(pay.get(i));
                    if (p.isEmpty()) continue;
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: samplePay idx={} itemId={} keyOf={}",
                            i, safeItemIdString(p), safeCatalogKeyOf(p));
                }
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices: computed={} from paySize={} declineSize={} requestedTargets={}",
                    out.size(),
                    pay.size(),
                    decline == null ? -1 : decline.size(),
                    requestedTargets.size());

            return out;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.computeFoundPayIndices failed (soft): {}", t.toString());
            return new HashSet<>();
        }
    }

    private static String safeItemIdString(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return "";
            return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean sameItemSameComponentsSafe(ItemStack a, ItemStack b) {
        try {
            if (a == null) a = ItemStack.EMPTY;
            if (b == null) b = ItemStack.EMPTY;

            try {
                Method m = ItemStack.class.getMethod("isSameItemSameComponents", ItemStack.class, ItemStack.class);
                Object r = m.invoke(null, a, b);
                if (r instanceof Boolean bb) return bb;
            } catch (Throwable ignored) {}

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
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: offer decode failed (idx={}): {}", idx, msg)
                    ).ifPresent(offers::add);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: offer decode threw (idx={}): {}", idx, t.toString());
                }
            }

            return offers;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.decodeOffersList failed (soft): {}", t.toString());
            return null;
        }
    }

    private void onPay() {
        try {
            if (sentAction) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: Pay ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] AutoSearchPaymentScreen: Pay ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            statusLineOverride = Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY);
            setButtonsActive(false);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: sending PacketPayAutoSearchSettlement(villagerEntityId={})", villagerEntityId);
            Network.sendToServer(new PacketPayAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoSearchPaymentScreen.onPay failed", t);
            sentAction = false;
            statusLineOverride = null;
            setButtonsActive(true);
        }
    }

    private void onDecline() {
        try {
            if (sentAction) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: Decline ignored (already sent action).");
                return;
            }
            if (villagerEntityId < 0) {
                VillagerOverhaul.LOG().warn("[VillagerOverhaul] AutoSearchPaymentScreen: Decline ignored (villagerEntityId<0).");
                return;
            }

            sentAction = true;
            statusLineOverride = Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY);
            setButtonsActive(false);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen: sending PacketDeclineAutoSearchSettlement(villagerEntityId={})", villagerEntityId);
            Network.sendToServer(new PacketDeclineAutoSearchSettlement(villagerEntityId));

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoSearchPaymentScreen.onDecline failed", t);
            sentAction = false;
            statusLineOverride = null;
            setButtonsActive(true);
        }
    }

    public void onPaymentFailed(String reason) {
        try {
            sentAction = false;
            setButtonsActive(true);

            String r = reason == null ? "" : reason.trim();
            if ("not_enough_emeralds".equalsIgnoreCase(r)
                    || "not_enough_currency".equalsIgnoreCase(r)
                    || "not_enough".equalsIgnoreCase(r)) {
                statusLineOverride = Component.literal("Not enough currency.").withStyle(ChatFormatting.RED);
            } else {
                statusLineOverride = Component.literal("Payment failed.").withStyle(ChatFormatting.RED);
            }
        } catch (Throwable ignored) {}
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

            int y = 18;

            gg.drawCenteredString(this.font,
                    Component.literal("Auto-search complete").withStyle(ChatFormatting.GOLD),
                    cx, y, 0xFFFFFF);

            y += 18;

            int seconds = elapsedTicks / 20;
            drawCenteredKeyValueLine(gg, cx, y, "Time:", seconds + "s");

            // rerolls line
            y += 14;
            drawCenteredKeyValueLine(gg, cx, y, "Rerolls:", String.valueOf(Math.max(0, rerollCount)));

            y += 14;

            drawCostLineWithEmerald(gg, cx, y, "Hourly cost:", hourlyCost);

            y += 16;

            drawCostLineWithEmerald(gg, cx, y, "Final cost:", finalCost);

            y += 16;

            String xpStr = (totalVillagerXp < 0) ? "?" : String.valueOf(totalVillagerXp);
            drawCenteredKeyValueLine(gg, cx, y, "Villager XP gained:", xpStr);

            y += 16;

            gg.drawCenteredString(this.font,
                    statusLineOverride != null
                            ? statusLineOverride
                            : (sentAction
                                    ? Component.literal("Waiting for server…").withStyle(ChatFormatting.DARK_GRAY)
                                    : Component.literal("Pay to keep the offers, or decline to revert.").withStyle(ChatFormatting.DARK_GRAY)),
                    cx, y, 0xFFFFFF);

            // Pay items row (no label)
            y += 18;

            ItemStack hovered = ItemStack.EMPTY;

            hovered = renderResultsRow(
                    gg,
                    cx,
                    y,
                    payResults,
                    true,
                    mouseX,
                    mouseY,
                    hovered
            );

            y += SLOT + 8;

            // Decline items row (no label)
            y += 28;

            hovered = renderResultsRow(
                    gg,
                    cx,
                    y,
                    declineResults,
                    false,
                    mouseX,
                    mouseY,
                    hovered
            );

            if (hovered != null && !hovered.isEmpty()) {
                try {
                    renderTooltipWithRerolls(gg, hovered, mouseX, mouseY);
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.renderTooltip failed (soft): {}", t.toString());
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] AutoSearchPaymentScreen.render failed", t);
        }
    }

    private void renderTooltipWithRerolls(GuiGraphics gg, ItemStack stack, int mouseX, int mouseY) {
        try {
            if (gg == null || this.font == null) return;
            if (stack == null || stack.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            var player = mc == null ? null : mc.player;
            if (player == null) {
                gg.renderTooltip(this.font, stack, mouseX, mouseY);
                return;
            }

            TooltipFlag flag = getTooltipFlagSafe(mc);
            List<Component> lines = TooltipCompat.getTooltipLines(stack, player, flag);
            ArrayList<Component> withExtra = new ArrayList<>(Math.max(1, lines == null ? 0 : lines.size()) + 1);
            if (lines != null) withExtra.addAll(lines);

            long vUnits = 0L;
            try {
                String k = keyOf(stack);
                vUnits = (k == null) ? 0L : tooltipValueVByKey.getOrDefault(k, 0L);
                if (vUnits < 0L) vUnits = 0L;
            } catch (Throwable ignored) {}

            if (vUnits > 0L) {
                withExtra.add(
                        Component.literal(String.valueOf(vUnits)).withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(" Rerolls ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("(").withStyle(ChatFormatting.DARK_GRAY))
                                .append(ClientCostIcon.costText(formatEmeraldsFromV(vUnits)))
                                .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY))
                );
            }

            gg.renderTooltip(this.font, withExtra, TooltipCompat.getTooltipImage(stack), mouseX, mouseY);
        } catch (Throwable ignored) {
            gg.renderTooltip(this.font, stack, mouseX, mouseY);
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
            return "err|" + java.util.Objects.hashCode(s);
        }
    }

    private static final class TooltipCompat {
        private static Method mGetTooltipLines_New; // (Item$TooltipContext, Player, TooltipFlag)
        private static Method mGetTooltipLines_Old; // (Player, TooltipFlag)
        private static Method mGetTooltipImage; // getTooltipImage(): Optional
        private static Method mTooltipContextOfLevel; // Item$TooltipContext.of(Level)
        private static boolean lookedUp = false;

        static List<Component> getTooltipLines(ItemStack stack, net.minecraft.world.entity.player.Player player, TooltipFlag flag) {
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
            } catch (Throwable ignored) {
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
                    mGetTooltipLines_New = ItemStack.class.getMethod("getTooltipLines", tooltipContextClz, net.minecraft.world.entity.player.Player.class, TooltipFlag.class);
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
                mGetTooltipLines_Old = ItemStack.class.getMethod("getTooltipLines", net.minecraft.world.entity.player.Player.class, TooltipFlag.class);
            } catch (Throwable ignored) {
                mGetTooltipLines_Old = null;
            }

            try {
                mGetTooltipImage = ItemStack.class.getMethod("getTooltipImage");
            } catch (Throwable ignored) {
                mGetTooltipImage = null;
            }
        }

        private static Object createTooltipContext(net.minecraft.world.entity.player.Player player) {
            try {
                if (player == null) return null;
                if (mTooltipContextOfLevel == null) return null;
                Object level = null;
                try { level = player.level(); } catch (Throwable ignored) { level = null; }
                if (level == null) return null;
                return mTooltipContextOfLevel.invoke(null, level);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

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

                gg.fill(x - 1, y - 1, x + SLOT + 1, y + SLOT + 1, 0x66000000);

                if (!st.isEmpty()) {
                    gg.renderItem(st, x, y);
                    gg.renderItemDecorations(this.font, st, x, y);
                }

                if (isLockedIndex(i)) {
                    drawOutline(gg, x - 1, y - 1, SLOT + 2, SLOT + 2, OUTLINE_GREEN);
                }

                if (isPayRow && isFoundPayIndex(i)) {
                    drawOutline(gg, x - 1, y - 1, SLOT + 2, SLOT + 2, OUTLINE_YELLOW);
                }

                if (!st.isEmpty()) {
                    if (mouseX >= x && mouseX < (x + SLOT) && mouseY >= y && mouseY < (y + SLOT)) {
                        currentHovered = st;
                    }
                }
            }

            if (stacks.size() > shown) {
                gg.drawCenteredString(this.font,
                        Component.literal("+" + (stacks.size() - shown) + " more").withStyle(ChatFormatting.DARK_GRAY),
                        centerX, topY + SLOT + 4, 0xFFFFFF);
            }

            return currentHovered;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.renderResultsRow failed (soft): {}", t.toString());
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
            gg.fill(x, y, x + w, y + 1, argb);
            gg.fill(x, y + h - 1, x + w, y + h, argb);
            gg.fill(x, y, x + 1, y + h, argb);
            gg.fill(x + w - 1, y, x + w, y + h, argb);
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

            ItemStack icon = ClientCostIcon.costIcon();
            gg.renderItem(icon, iconX, iconY);
            gg.renderItemDecorations(this.font, icon, iconX, iconY);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.drawCostLineWithEmerald failed (soft): {}", t.toString());
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

    private boolean isRequestedResultStack(ItemStack result) {
        try {
            if (result == null || result.isEmpty()) return false;
            if (this.requestedTargets == null || this.requestedTargets.isEmpty()) return false;

            String key = safeCatalogKeyOf(result);
            if (key != null && !key.isBlank()) {
                boolean hit = this.requestedTargets.contains(key);
                if (hit && VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Yellow target match via CatalogBuilder.keyOf: key={} stack={}", key, String.valueOf(result));
                }
                if (hit) return true;
            }

            String id = safeItemIdString(result);
            if (id != null && !id.isBlank()) {
                boolean hit = this.requestedTargets.contains(id);
                if (hit && VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Yellow target match via plain item id fallback: id={} stack={}", id, String.valueOf(result));
                }
                return hit;
            }

            return false;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] isRequestedResultStack failed (soft): {}", t.toString());
            return false;
        }
    }

    private static String safeCatalogKeyOf(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return "";

            try {
                return org.z2six.villageroverhaul.server.CatalogBuilder.keyOf(stack);
            } catch (Throwable t) {
                if (VillagerOverhaul.LOG().isDebugEnabled()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] safeCatalogKeyOf: CatalogBuilder.keyOf failed (soft): {}", t.toString());
                }
                return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void onClose() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoSearchPaymentScreen.onClose failed (soft): {}", t.toString());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
