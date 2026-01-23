package org.z2six.villageroverhaul.server.ai;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.menu.VillagerInventoryMenu;
import org.z2six.villageroverhaul.server.RecruitService;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-authoritative combat loadout handling.
 *
 * <p>Players interact only with two "combat loadout" slots in {@link org.z2six.villageroverhaul.menu.VillagerInventoryMenu}.
 * When the villager enters a combat mode (FLEE/DEFEND/AGGRESSIVE) or movement PATROL, we temporarily equip those
 * loadout items into the villager's real main/off-hand slots and stash whatever the villager was holding.</p>
 */
public final class VillagerCombatLoadoutService {
    private static final String TAG = "ezvr_combat_loadout";

    // GUI slots (player-visible in VillagerInventoryScreen)
    private static final String K_GUI_MAIN = "gui_main";
    private static final String K_GUI_OFF = "gui_off";

    // Equipped loadout (server-only; used to self-heal while active)
    private static final String K_EQ_MAIN = "eq_main";
    private static final String K_EQ_OFF = "eq_off";

    // Hidden stash (never shown to player; used to restore hands on exit)
    private static final String K_STASH_MAIN = "stash_main";
    private static final String K_STASH_OFF = "stash_off";

    // Slot identity stamps stored on the item (so we can detect held loadout items)
    private static final String K_ID_MAIN = "id_main";
    private static final String K_ID_OFF = "id_off";
    private static final String ITEM_K_ID = "ezvr_combat_loadout_id";
    private static final String ITEM_K_SLOT = "ezvr_combat_loadout_slot";
    private static final String SLOT_MAIN = "main";
    private static final String SLOT_OFF = "off";

    // Prevent enforcement while a player is interacting with the UI.
    private static final String K_MENU_OPEN = "menu_open";
    private static final String K_WAS_ACTIVE = "was_active";

    private static final long ENFORCE_EVERY_TICKS = 10L;

    private static final String PD_OFFHAND_BROKE_TICK = "ezvr_loadout_off_broke_tick";

    private static final Set<Villager> TRACKED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Map<Villager, Long> LAST_ENFORCE_AT = new WeakHashMap<>();

    private VillagerCombatLoadoutService() {}

    public static void track(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;
            TRACKED.add(vill);
        } catch (Throwable ignored) {}
    }

    public static void tick(MinecraftServer server) {
        try {
            if (server == null) return;
            if (TRACKED.isEmpty()) return;

            Iterator<Villager> it = TRACKED.iterator();
            while (it.hasNext()) {
                Villager vill = it.next();
                if (vill == null || vill.level() == null || vill.level().isClientSide() || !vill.isAlive()) {
                    it.remove();
                    continue;
                }

                if (!RecruitService.isRecruited(vill)) continue;
                if (isMenuOpen(vill)) continue;
                if (VillagerBrain.isUiPaused(vill)) continue;

                long now = vill.level().getGameTime();
                Long last = LAST_ENFORCE_AT.get(vill);
                if (last != null && (now - last) < ENFORCE_EVERY_TICKS) continue;
                LAST_ENFORCE_AT.put(vill, now);

                enforce(vill, "tick");
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatLoadoutService.tick failed", t);
        }
    }

    public static Container createMenuContainer(Villager vill) {
        if (vill == null) return new SimpleContainer(2);
        return new MenuContainer(vill);
    }

    public static void prepareForInventoryOpen(Villager vill) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            setMenuOpen(vill, true);
            VillagerBrain.setUiPaused(vill, true);

            // Always show the registered combat items in the GUI slots when opening the menu,
            // even if they are currently equipped in real hands.
            prepareMenuView(vill, "menu_open");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatLoadoutService.prepareForInventoryOpen failed", t);
        }
    }

    public static void onInventoryMenuClosed(Villager vill, Container menuLoadoutContainerOrNull) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            if (menuLoadoutContainerOrNull != null && menuLoadoutContainerOrNull.getContainerSize() >= 2) {
                saveGuiFromContainer(vill, menuLoadoutContainerOrNull);
            }

            setMenuOpen(vill, false);
            VillagerBrain.setUiPaused(vill, false);

            enforce(vill, "menu_close");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatLoadoutService.onInventoryMenuClosed failed", t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Enforcement
    // -----------------------------------------------------------------------------------------

    private static void enforce(Villager vill, String reason) {
        try {
            if (vill == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            ensureIdsExist(vill);
            HolderLookup.Provider lookup = safeLookup(vill);
            CompoundTag root = getOrCreate(vill);

            boolean active = isActiveState(vill);
            boolean wasActive = root.getBoolean(K_WAS_ACTIVE);

            if (active && !wasActive) {
                onEnterActive(vill, root, lookup, reason);
            } else if (!active && wasActive) {
                onExitActive(vill, root, lookup, reason);
            }

            if (active) {
                enforceActiveTick(vill, root, lookup, reason);
            } else {
                enforceInactiveTick(vill, root, lookup, reason);
            }

            syncClientLoadout(vill, root, lookup);
            root.putBoolean(K_WAS_ACTIVE, active);
        } catch (Throwable ignored) {}
    }

    private static void syncClientLoadout(Villager vill, CompoundTag root, HolderLookup.Provider lookup) {
        try {
            if (vill == null || root == null || lookup == null) return;
            if (!(vill instanceof VillagerOverhaulRenderAccess acc)) return;

            ItemStack guiMain = readStack(root, K_GUI_MAIN, lookup);
            ItemStack guiOff = readStack(root, K_GUI_OFF, lookup);
            ItemStack eqMain = readStack(root, K_EQ_MAIN, lookup);
            ItemStack eqOff = readStack(root, K_EQ_OFF, lookup);

            ItemStack desiredMain = (guiMain != null && !guiMain.isEmpty()) ? guiMain : eqMain;
            ItemStack desiredOff = (guiOff != null && !guiOff.isEmpty()) ? guiOff : eqOff;

            if (desiredMain == null) desiredMain = ItemStack.EMPTY;
            if (desiredOff == null) desiredOff = ItemStack.EMPTY;

            ItemStack normalizedMain = desiredMain.isEmpty() ? ItemStack.EMPTY : desiredMain.copy();
            if (!normalizedMain.isEmpty()) normalizedMain.setCount(1);

            ItemStack normalizedOff = desiredOff.isEmpty() ? ItemStack.EMPTY : desiredOff.copy();
            if (!normalizedOff.isEmpty()) normalizedOff.setCount(1);

            ItemStack curMain = acc.ezvr$getCombatLoadoutMain();
            ItemStack curOff = acc.ezvr$getCombatLoadoutOff();

            if (!sameForSync(curMain, normalizedMain)) {
                acc.ezvr$setCombatLoadoutMain(normalizedMain);
            }
            if (!sameForSync(curOff, normalizedOff)) {
                acc.ezvr$setCombatLoadoutOff(normalizedOff);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean sameForSync(ItemStack a, ItemStack b) {
        try {
            if (a == null) a = ItemStack.EMPTY;
            if (b == null) b = ItemStack.EMPTY;
            if (a.isEmpty() && b.isEmpty()) return true;
            if (a.isEmpty() != b.isEmpty()) return false;
            return ItemStack.isSameItemSameComponents(a, b) && a.getCount() == b.getCount();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isActiveState(Villager vill) {
        try {
            if (vill == null) return false;
            // Active means we should equip the registered combat loadout into real hands.
            // This must depend on *engagement*, not on the villager's configured combat mode setting.
            //
            // Rules:
            // - Always equip while patrolling.
            // - Equip while combat engaged, but only for DEFEND/AGGRESSIVE (never for FLEE).
            if (VillagerBrain.getMode(vill) == VillagerBrain.Mode.PATROL) return true;

            if (!VillagerBrain.isCombatEngaged(vill)) return false;

            VillagerBrain.CombatMode cm = VillagerBrain.getCombatMode(vill);
            return cm == VillagerBrain.CombatMode.DEFEND || cm == VillagerBrain.CombatMode.AGGRESSIVE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void enforceActiveTick(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String reason) {
        try {
            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            // If we are active and have GUI loadout items (e.g. just closed the menu),
            // promote GUI -> EQ (canonical) and clear GUI.
            ItemStack eqMain = readStack(root, K_EQ_MAIN, lookup);
            ItemStack guiMain = readStack(root, K_GUI_MAIN, lookup);
            if ((eqMain == null || eqMain.isEmpty()) && guiMain != null && !guiMain.isEmpty()) {
                stamp(guiMain, mainId, SLOT_MAIN);
                writeStack(root, K_EQ_MAIN, guiMain, lookup);
                writeStack(root, K_GUI_MAIN, ItemStack.EMPTY, lookup);
                eqMain = guiMain;
            }

            ItemStack eqOff = readStack(root, K_EQ_OFF, lookup);
            ItemStack guiOff = readStack(root, K_GUI_OFF, lookup);
            if ((eqOff == null || eqOff.isEmpty()) && guiOff != null && !guiOff.isEmpty()) {
                stamp(guiOff, offId, SLOT_OFF);
                writeStack(root, K_EQ_OFF, guiOff, lookup);
                writeStack(root, K_GUI_OFF, ItemStack.EMPTY, lookup);
                eqOff = guiOff;
            }

            ItemStack curMain = vill.getMainHandItem();
            ItemStack curOff = vill.getOffhandItem();

            // MAINHAND: self-heal if the hand doesn't match the equipped loadout.
            boolean skipMain = false;
            try {
                long until = vill.getPersistentData().getLong("ezvr_loadout_skip_main_until");
                long now = vill.level() == null ? 0L : vill.level().getGameTime();
                skipMain = until > 0L && now < until;
            } catch (Throwable ignored) {}

            if (!skipMain && eqMain != null && !eqMain.isEmpty()) {
                if (curMain == null) curMain = ItemStack.EMPTY;

                if (curMain.isEmpty()) {
                    ItemStack toEquip = eqMain.copy();
                    if (!toEquip.isEmpty()) toEquip.setCount(1);
                    stamp(toEquip, mainId, SLOT_MAIN);
                    vill.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, toEquip);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_equip_main reason={}", vill.getUUID(), safe(reason));
                } else if (isStamped(curMain, mainId, SLOT_MAIN)) {
                    // If the held item has changed (durability, etc), update the equipped record so we don't
                    // "self-heal" by duplicating.
                    if (!ItemStack.isSameItemSameComponents(curMain, eqMain)) {
                        ItemStack upd = curMain.copy();
                        if (!upd.isEmpty()) upd.setCount(1);
                        writeStack(root, K_EQ_MAIN, upd, lookup);
                    }
                } else {
                    // Not stamped. If it's the same item type, adopt it; otherwise displace and equip.
                    boolean sameItem = false;
                    try { sameItem = curMain.getItem() == eqMain.getItem(); } catch (Throwable ignored) {}
                    if (sameItem) {
                        stamp(curMain, mainId, SLOT_MAIN);
                        ItemStack upd = curMain.copy();
                        if (!upd.isEmpty()) upd.setCount(1);
                        writeStack(root, K_EQ_MAIN, upd, lookup);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_adopt_main reason={}", vill.getUUID(), safe(reason));
                    } else {
                        storeOrDrop(vill, curMain, "active_main_displace");
                        ItemStack toEquip = eqMain.copy();
                        if (!toEquip.isEmpty()) toEquip.setCount(1);
                        stamp(toEquip, mainId, SLOT_MAIN);
                        vill.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, toEquip);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_equip_main reason={}", vill.getUUID(), safe(reason));
                    }
                }
            }

            // OFFHAND: self-heal if the hand doesn't match the equipped loadout.
            if (eqOff != null && !eqOff.isEmpty()) {
                if (curOff == null) curOff = ItemStack.EMPTY;

                if (curOff.isEmpty()) {
                    // If the offhand shield broke this tick, do NOT re-equip from EQ; clear the canonical record instead.
                    try {
                        long brokeAt = vill.getPersistentData().getLong(PD_OFFHAND_BROKE_TICK);
                        long now = vill.level() == null ? 0L : vill.level().getGameTime();
                        if (brokeAt > 0L && (brokeAt == now || brokeAt == (now - 1L))) {
                            writeStack(root, K_EQ_OFF, ItemStack.EMPTY, lookup);
                            writeStack(root, K_GUI_OFF, ItemStack.EMPTY, lookup);
                            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=off_broke_clear reason={}", vill.getUUID(), safe(reason));
                            return;
                        }
                    } catch (Throwable ignored) {}

                    ItemStack toEquip = eqOff.copy();
                    if (!toEquip.isEmpty()) toEquip.setCount(1);
                    stamp(toEquip, offId, SLOT_OFF);
                    vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, toEquip);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_equip_off reason={}", vill.getUUID(), safe(reason));
                } else if (isStamped(curOff, offId, SLOT_OFF)) {
                    if (!ItemStack.isSameItemSameComponents(curOff, eqOff)) {
                        ItemStack upd = curOff.copy();
                        if (!upd.isEmpty()) upd.setCount(1);
                        writeStack(root, K_EQ_OFF, upd, lookup);
                    }
                } else {
                    boolean sameItem = false;
                    try { sameItem = curOff.getItem() == eqOff.getItem(); } catch (Throwable ignored) {}
                    if (sameItem) {
                        stamp(curOff, offId, SLOT_OFF);
                        ItemStack upd = curOff.copy();
                        if (!upd.isEmpty()) upd.setCount(1);
                        writeStack(root, K_EQ_OFF, upd, lookup);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_adopt_off reason={}", vill.getUUID(), safe(reason));
                    } else {
                        storeOrDrop(vill, curOff, "active_off_displace");
                        ItemStack toEquip = eqOff.copy();
                        if (!toEquip.isEmpty()) toEquip.setCount(1);
                        stamp(toEquip, offId, SLOT_OFF);
                        vill.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, toEquip);
                        VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=active_equip_off reason={}", vill.getUUID(), safe(reason));
                    }
                }
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatLoadoutService.enforceActiveTick failed (soft): {}", t.toString());
        }
    }

    private static void enforceInactiveTick(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String reason) {
        try {
            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            ItemStack curMain = vill.getMainHandItem();
            ItemStack curOff = vill.getOffhandItem();

            // Safety: if EQ still exists while inactive, move it back to GUI and clear EQ.
            ItemStack eqMain = readStack(root, K_EQ_MAIN, lookup);
            if (eqMain != null && !eqMain.isEmpty()) {
                if (readStack(root, K_GUI_MAIN, lookup).isEmpty()) {
                    ItemStack toGui = eqMain.copy();
                    stamp(toGui, mainId, SLOT_MAIN);
                    writeStack(root, K_GUI_MAIN, toGui, lookup);
                } else {
                    storeOrDrop(vill, eqMain, "inactive_eq_main_gui_occupied");
                }
                writeStack(root, K_EQ_MAIN, ItemStack.EMPTY, lookup);
            }

            ItemStack eqOff = readStack(root, K_EQ_OFF, lookup);
            if (eqOff != null && !eqOff.isEmpty()) {
                if (readStack(root, K_GUI_OFF, lookup).isEmpty()) {
                    ItemStack toGui = eqOff.copy();
                    stamp(toGui, offId, SLOT_OFF);
                    writeStack(root, K_GUI_OFF, toGui, lookup);
                } else {
                    storeOrDrop(vill, eqOff, "inactive_eq_off_gui_occupied");
                }
                writeStack(root, K_EQ_OFF, ItemStack.EMPTY, lookup);
            }

            // Safety: if we ever find stamped loadout items still in hands while inactive, pull them back into GUI.
            if (curMain != null && !curMain.isEmpty() && isStamped(curMain, mainId, SLOT_MAIN)) {
                moveHandToGui(vill, root, lookup, net.minecraft.world.InteractionHand.MAIN_HAND, K_GUI_MAIN, curMain, "inactive_cleanup_main", reason);
            }
            if (curOff != null && !curOff.isEmpty() && isStamped(curOff, offId, SLOT_OFF)) {
                moveHandToGui(vill, root, lookup, net.minecraft.world.InteractionHand.OFF_HAND, K_GUI_OFF, curOff, "inactive_cleanup_off", reason);
            }

            // Never equip GUI loadout items into real hands while inactive.
            // Restore stashed original hands only if the hand is empty to avoid dropping/duplicating.
            restoreStashToHandIfEmpty(vill, root, lookup, K_STASH_MAIN, net.minecraft.world.InteractionHand.MAIN_HAND, "restore_main");
            restoreStashToHandIfEmpty(vill, root, lookup, K_STASH_OFF, net.minecraft.world.InteractionHand.OFF_HAND, "restore_off");

            // In NEUTRAL movement mode, ensure main-hand is empty so vanilla tasks (farming, etc) are not
            // disrupted by leftover combat food items (e.g. steak).
            try {
                if (VillagerBrain.getMode(vill) == VillagerBrain.Mode.NEUTRAL && !VillagerBrain.isCombatEngaged(vill)) {
                    ItemStack mh = vill.getMainHandItem();
                    if (mh != null && !mh.isEmpty()) {
                        boolean isFood = false;
                        try { isFood = mh.has(DataComponents.FOOD); } catch (Throwable ignored) { isFood = false; }
                        if (isFood) {
                            storeOrDrop(vill, mh, "neutral_clear_main_food");
                            clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "neutral_clear_main_food");
                        }
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatLoadoutService.enforceInactiveTick failed (soft): {}", t.toString());
        }
    }

    private static void onEnterActive(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String reason) {
        try {
            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            // Stash current hands (whatever the villager was holding before we equip combat loadout).
            ItemStack curMain = vill.getMainHandItem();
            if (curMain != null && !curMain.isEmpty()) {
                stashOrStoreDisplacedHand(vill, root, lookup, K_STASH_MAIN, curMain, "stash_main_enter_active");
                clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "enter_active_clear_main");
            }
            ItemStack curOff = vill.getOffhandItem();
            if (curOff != null && !curOff.isEmpty()) {
                stashOrStoreDisplacedHand(vill, root, lookup, K_STASH_OFF, curOff, "stash_off_enter_active");
                clearHand(vill, net.minecraft.world.InteractionHand.OFF_HAND, "enter_active_clear_off");
            }

            // Promote GUI -> EQ (canonical storage during active), then equip.
            ItemStack guiMain = readStack(root, K_GUI_MAIN, lookup);
            if (guiMain != null && !guiMain.isEmpty()) {
                stamp(guiMain, mainId, SLOT_MAIN);
                writeStack(root, K_EQ_MAIN, guiMain, lookup);
                writeStack(root, K_GUI_MAIN, ItemStack.EMPTY, lookup);
            }
            ItemStack guiOff = readStack(root, K_GUI_OFF, lookup);
            if (guiOff != null && !guiOff.isEmpty()) {
                stamp(guiOff, offId, SLOT_OFF);
                writeStack(root, K_EQ_OFF, guiOff, lookup);
                writeStack(root, K_GUI_OFF, ItemStack.EMPTY, lookup);
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=enter_active reason={}", vill.getUUID(), safe(reason));
        } catch (Throwable ignored) {}
    }

    private static void onExitActive(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String reason) {
        try {
            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            ItemStack curMain = vill.getMainHandItem();
            ItemStack curOff = vill.getOffhandItem();
            ItemStack eqMain = readStack(root, K_EQ_MAIN, lookup);
            ItemStack eqOff = readStack(root, K_EQ_OFF, lookup);

            // Main: prefer loadout item actually in hand; otherwise fall back to EQ.
            if (curMain != null && !curMain.isEmpty()) {
                boolean looksLikeLoadout = isStamped(curMain, mainId, SLOT_MAIN) || (eqMain != null && !eqMain.isEmpty() && ItemStack.isSameItemSameComponents(curMain, eqMain));
                if (looksLikeLoadout) {
                    moveHandToGui(vill, root, lookup, net.minecraft.world.InteractionHand.MAIN_HAND, K_GUI_MAIN, curMain, "exit_active_main", reason);
                } else {
                    storeOrDrop(vill, curMain, "exit_active_main_unexpected");
                    clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "exit_active_clear_main");
                }
            } else if (eqMain != null && !eqMain.isEmpty()) {
                if (readStack(root, K_GUI_MAIN, lookup).isEmpty()) {
                    ItemStack toGui = eqMain.copy();
                    stamp(toGui, mainId, SLOT_MAIN);
                    writeStack(root, K_GUI_MAIN, toGui, lookup);
                } else {
                    storeOrDrop(vill, eqMain, "exit_active_main_gui_occupied");
                }
            }

            // Off: prefer loadout item actually in hand; otherwise fall back to EQ.
            if (curOff != null && !curOff.isEmpty()) {
                boolean looksLikeLoadout = isStamped(curOff, offId, SLOT_OFF) || (eqOff != null && !eqOff.isEmpty() && ItemStack.isSameItemSameComponents(curOff, eqOff));
                if (looksLikeLoadout) {
                    moveHandToGui(vill, root, lookup, net.minecraft.world.InteractionHand.OFF_HAND, K_GUI_OFF, curOff, "exit_active_off", reason);
                } else {
                    storeOrDrop(vill, curOff, "exit_active_off_unexpected");
                    clearHand(vill, net.minecraft.world.InteractionHand.OFF_HAND, "exit_active_clear_off");
                }
            } else if (eqOff != null && !eqOff.isEmpty()) {
                if (readStack(root, K_GUI_OFF, lookup).isEmpty()) {
                    ItemStack toGui = eqOff.copy();
                    stamp(toGui, offId, SLOT_OFF);
                    writeStack(root, K_GUI_OFF, toGui, lookup);
                } else {
                    storeOrDrop(vill, eqOff, "exit_active_off_gui_occupied");
                }
            }

            writeStack(root, K_EQ_MAIN, ItemStack.EMPTY, lookup);
            writeStack(root, K_EQ_OFF, ItemStack.EMPTY, lookup);

            // Restore stash if hands are empty.
            restoreStashToHandIfEmpty(vill, root, lookup, K_STASH_MAIN, net.minecraft.world.InteractionHand.MAIN_HAND, "restore_main_exit_active");
            restoreStashToHandIfEmpty(vill, root, lookup, K_STASH_OFF, net.minecraft.world.InteractionHand.OFF_HAND, "restore_off_exit_active");

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=exit_active reason={}", vill.getUUID(), safe(reason));
        } catch (Throwable ignored) {}
    }

    private static void prepareMenuView(Villager vill, String reason) {
        try {
            ensureIdsExist(vill);
            HolderLookup.Provider lookup = safeLookup(vill);
            CompoundTag root = getOrCreate(vill);

            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            ItemStack guiMain = readStack(root, K_GUI_MAIN, lookup);
            ItemStack guiOff = readStack(root, K_GUI_OFF, lookup);

            ItemStack eqMain = readStack(root, K_EQ_MAIN, lookup);
            ItemStack eqOff = readStack(root, K_EQ_OFF, lookup);

            ItemStack handMain = vill.getMainHandItem();
            ItemStack handOff = vill.getOffhandItem();

            // Prefer EQ -> GUI (and clear EQ) so the menu always shows the registered combat items.
            if ((guiMain == null || guiMain.isEmpty()) && eqMain != null && !eqMain.isEmpty()) {
                ItemStack toGui = eqMain.copy();
                stamp(toGui, mainId, SLOT_MAIN);
                writeStack(root, K_GUI_MAIN, toGui, lookup);
                writeStack(root, K_EQ_MAIN, ItemStack.EMPTY, lookup);
                if (handMain != null && !handMain.isEmpty()) {
                    clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "menu_view_clear_main");
                }
            }
            if ((guiOff == null || guiOff.isEmpty()) && eqOff != null && !eqOff.isEmpty()) {
                ItemStack toGui = eqOff.copy();
                stamp(toGui, offId, SLOT_OFF);
                writeStack(root, K_GUI_OFF, toGui, lookup);
                writeStack(root, K_EQ_OFF, ItemStack.EMPTY, lookup);
                if (handOff != null && !handOff.isEmpty()) {
                    clearHand(vill, net.minecraft.world.InteractionHand.OFF_HAND, "menu_view_clear_off");
                }
            }

            // Fallback: if we still don't have GUI items, but hands contain stamped combat items, capture them.
            guiMain = readStack(root, K_GUI_MAIN, lookup);
            guiOff = readStack(root, K_GUI_OFF, lookup);

            if ((guiMain == null || guiMain.isEmpty()) && handMain != null && !handMain.isEmpty() && isStamped(handMain, mainId, SLOT_MAIN)) {
                writeStack(root, K_GUI_MAIN, handMain, lookup);
                clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "menu_view_capture_main");
            }

            if ((guiOff == null || guiOff.isEmpty()) && handOff != null && !handOff.isEmpty() && isStamped(handOff, offId, SLOT_OFF)) {
                writeStack(root, K_GUI_OFF, handOff, lookup);
                clearHand(vill, net.minecraft.world.InteractionHand.OFF_HAND, "menu_view_capture_off");
            }

            // Migration fallback: if we are currently in an active state and the hands still have items but we
            // couldn't prove stamping, capture them so the menu always shows what's equipped.
            guiMain = readStack(root, K_GUI_MAIN, lookup);
            if ((guiMain == null || guiMain.isEmpty()) && isActiveState(vill) && handMain != null && !handMain.isEmpty()) {
                writeStack(root, K_GUI_MAIN, handMain, lookup);
                clearHand(vill, net.minecraft.world.InteractionHand.MAIN_HAND, "menu_view_capture_main_unstamped");
            }
            guiOff = readStack(root, K_GUI_OFF, lookup);
            if ((guiOff == null || guiOff.isEmpty()) && isActiveState(vill) && handOff != null && !handOff.isEmpty()) {
                writeStack(root, K_GUI_OFF, handOff, lookup);
                clearHand(vill, net.minecraft.world.InteractionHand.OFF_HAND, "menu_view_capture_off_unstamped");
            }

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=menu_view_sync reason={}", vill.getUUID(), safe(reason));
        } catch (Throwable ignored) {}
    }

    private static void moveHandToGui(Villager vill, CompoundTag root, HolderLookup.Provider lookup, net.minecraft.world.InteractionHand hand, String guiKey, ItemStack inHand, String why, String reason) {
        try {
            if (vill == null || root == null || lookup == null) return;
            if (hand == null || guiKey == null) return;
            if (inHand == null || inHand.isEmpty()) return;

            ItemStack existing = readStack(root, guiKey, lookup);
            if (existing.isEmpty()) {
                writeStack(root, guiKey, inHand.copy(), lookup);
            } else {
                // If GUI already has the same registered item, just clear the hand (no store/drop).
                boolean same = ItemStack.isSameItemSameComponents(existing, inHand);
                if (!same) {
                    storeOrDrop(vill, inHand, why + "_gui_occupied");
                }
            }
            clearHand(vill, hand, why);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=hand_to_gui why={} reason={}",
                    vill.getUUID(), safe(why), safe(reason));
        } catch (Throwable ignored) {}
    }

    // -----------------------------------------------------------------------------------------
    // Menu container persistence
    // -----------------------------------------------------------------------------------------

    private static final class MenuContainer extends SimpleContainer {
        private final Villager vill;
        private boolean suppressSaves = false;

        private MenuContainer(Villager vill) {
            super(2);
            this.vill = vill;

            try {
                ensureIdsExist(vill);
                HolderLookup.Provider lookup = safeLookup(vill);
                CompoundTag root = getOrCreate(vill);
                // IMPORTANT: SimpleContainer#setItem calls setChanged(). We must NOT persist while we are
                // still initializing (otherwise slot 0 save will wipe slot 1, etc).
                suppressSaves = true;
                super.setItem(0, readStack(root, K_GUI_MAIN, lookup));
                super.setItem(1, readStack(root, K_GUI_OFF, lookup));
                suppressSaves = false;
            } catch (Throwable ignored) {}
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            ItemStack toSet = (stack == null) ? ItemStack.EMPTY : stack.copy();
            if (!toSet.isEmpty()) toSet.setCount(1);
            super.setItem(index, toSet);
            try {
                if (vill != null && vill.level() != null && !vill.level().isClientSide()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=menu_set idx={} item={}",
                            vill.getUUID(), index, safeItem(toSet));
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public void setChanged() {
            try {
                if (suppressSaves) return;
                saveGuiFromContainer(vill, this);
            } catch (Throwable ignored) {}
        }
    }

    private static void saveGuiFromContainer(Villager vill, Container c) {
        try {
            if (vill == null || c == null) return;
            ensureIdsExist(vill);

            HolderLookup.Provider lookup = safeLookup(vill);
            CompoundTag root = getOrCreate(vill);

            UUID mainId = root.getUUID(K_ID_MAIN);
            UUID offId = root.getUUID(K_ID_OFF);

            ItemStack main = c.getItem(0);
            ItemStack off = c.getItem(1);

            if (main == null) main = ItemStack.EMPTY;
            if (off == null) off = ItemStack.EMPTY;

            try {
                if (vill.level() != null && !vill.level().isClientSide()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=menu_save_before main={} off={}",
                            vill.getUUID(), safeItem(main), safeItem(off));
                }
            } catch (Throwable ignored) {}

            if (!main.isEmpty()) {
                if (main.getCount() > 1) {
                    main = main.copy();
                    main.setCount(1);
                }
                stamp(main, mainId, SLOT_MAIN);
            }
            if (!off.isEmpty()) {
                if (off.getCount() > 1) {
                    off = off.copy();
                    off.setCount(1);
                }
                stamp(off, offId, SLOT_OFF);
            }

            writeStack(root, K_GUI_MAIN, main, lookup);
            writeStack(root, K_GUI_OFF, off, lookup);

            try {
                if (vill.level() != null && !vill.level().isClientSide()) {
                    ItemStack nowMain = readStack(root, K_GUI_MAIN, lookup);
                    ItemStack nowOff = readStack(root, K_GUI_OFF, lookup);
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=menu_save_after main={} off={}",
                            vill.getUUID(), safeItem(nowMain), safeItem(nowOff));
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // -----------------------------------------------------------------------------------------
    // Stash / restore helpers
    // -----------------------------------------------------------------------------------------

    private static void stashOrStoreDisplacedHand(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String stashKey, ItemStack displaced, String why) {
        try {
            if (vill == null || root == null) return;
            if (displaced == null || displaced.isEmpty()) return;

            ItemStack existing = readStack(root, stashKey, lookup);
            if (existing.isEmpty()) {
                writeStack(root, stashKey, displaced, lookup);
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=stash why={}", vill.getUUID(), safe(why));
            } else {
                storeOrDrop(vill, displaced, why + "_stash_occupied");
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreStashToHandIfAny(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String stashKey, net.minecraft.world.InteractionHand hand, String why) {
        try {
            if (vill == null || root == null || hand == null) return;

            ItemStack stashed = readStack(root, stashKey, lookup);
            if (stashed.isEmpty()) return;

            ItemStack cur = (hand == net.minecraft.world.InteractionHand.MAIN_HAND) ? vill.getMainHandItem() : vill.getOffhandItem();
            if (cur != null && !cur.isEmpty()) return;

            vill.setItemInHand(hand, stashed);
            writeStack(root, stashKey, ItemStack.EMPTY, lookup);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=restore why={}", vill.getUUID(), safe(why));
        } catch (Throwable ignored) {}
    }

    private static void restoreStashToHandIfEmpty(Villager vill, CompoundTag root, HolderLookup.Provider lookup, String stashKey, net.minecraft.world.InteractionHand hand, String why) {
        restoreStashToHandIfAny(vill, root, lookup, stashKey, hand, why);
    }

    private static void clearHand(Villager vill, net.minecraft.world.InteractionHand hand, String reason) {
        try {
            if (vill == null || hand == null) return;
            if (vill.level() == null || vill.level().isClientSide()) return;

            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY, safe(reason)); } catch (Throwable ignored) {}
            } else {
                try { VillagerBrain.notifyManualHandSet(vill, net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY, safe(reason)); } catch (Throwable ignored) {}
            }

            vill.setItemInHand(hand, ItemStack.EMPTY);
        } catch (Throwable ignored) {}
    }

    private static void storeOrDrop(Villager vill, ItemStack stack, String why) {
        try {
            if (vill == null) return;
            if (stack == null || stack.isEmpty()) return;

            if (tryStoreInVillagerPickupInventory(vill, stack.copy())) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=store why={}", vill.getUUID(), safe(why));
                return;
            }

            try {
                vill.spawnAtLocation(stack.copy());
            } catch (Throwable ignored) {}
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] [loadout] villager={} action=drop why={}", vill.getUUID(), safe(why));

        } catch (Throwable ignored) {}
    }

    private static boolean tryStoreInVillagerPickupInventory(Villager vill, ItemStack stack) {
        try {
            if (vill == null) return false;
            if (stack == null || stack.isEmpty()) return true;

            Container inv = VillagerInventoryMenu.tryGetVillagerPickupInventory(vill);
            if (inv == null) return false;

            ItemStack remaining = stack.copy();

            int size = inv.getContainerSize();
            for (int i = 0; i < size && !remaining.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot == null || slot.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(slot, remaining)) continue;

                int max = Math.min(slot.getMaxStackSize(), inv.getMaxStackSize());
                int can = Math.max(0, max - slot.getCount());
                if (can <= 0) continue;

                int move = Math.min(can, remaining.getCount());
                if (move <= 0) continue;

                slot.grow(move);
                remaining.shrink(move);
                inv.setChanged();
            }

            for (int i = 0; i < size && !remaining.isEmpty(); i++) {
                ItemStack slot = inv.getItem(i);
                if (slot != null && !slot.isEmpty()) continue;

                int max = Math.min(remaining.getMaxStackSize(), inv.getMaxStackSize());
                ItemStack toPut = remaining.copy();
                if (toPut.getCount() > max) toPut.setCount(max);

                inv.setItem(i, toPut);
                remaining.shrink(toPut.getCount());
                inv.setChanged();
            }

            return remaining.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // -----------------------------------------------------------------------------------------
    // NBT / Identity helpers
    // -----------------------------------------------------------------------------------------

    private static void ensureIdsExist(Villager vill) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreate(vill);
            if (!root.hasUUID(K_ID_MAIN)) root.putUUID(K_ID_MAIN, UUID.randomUUID());
            if (!root.hasUUID(K_ID_OFF)) root.putUUID(K_ID_OFF, UUID.randomUUID());
        } catch (Throwable ignored) {}
    }

    private static boolean isMenuOpen(Villager vill) {
        try {
            if (vill == null) return false;
            CompoundTag root = getOrCreate(vill);
            return root.getBoolean(K_MENU_OPEN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setMenuOpen(Villager vill, boolean open) {
        try {
            if (vill == null) return;
            CompoundTag root = getOrCreate(vill);
            if (open) root.putBoolean(K_MENU_OPEN, true);
            else root.remove(K_MENU_OPEN);
        } catch (Throwable ignored) {}
    }

    private static CompoundTag getOrCreate(Villager vill) {
        CompoundTag pd = vill.getPersistentData();
        if (!pd.contains(TAG, Tag.TAG_COMPOUND)) {
            pd.put(TAG, new CompoundTag());
        }
        return pd.getCompound(TAG);
    }

    private static HolderLookup.Provider safeLookup(Villager vill) {
        try {
            if (vill == null || vill.level() == null) return null;
            return vill.level().registryAccess();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ItemStack readStack(CompoundTag root, String key, HolderLookup.Provider lookup) {
        try {
            if (root == null || key == null || key.isBlank()) return ItemStack.EMPTY;
            if (!root.contains(key, Tag.TAG_COMPOUND)) return ItemStack.EMPTY;
            CompoundTag st = root.getCompound(key);
            ItemStack out;
            try {
                out = ItemStack.parseOptional(lookup, st);
            } catch (Throwable ignored) {
                out = ItemStack.EMPTY;
            }
            return (out == null) ? ItemStack.EMPTY : out;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static void writeStack(CompoundTag root, String key, ItemStack stack, HolderLookup.Provider lookup) {
        try {
            if (root == null || key == null || key.isBlank()) return;
            if (stack == null || stack.isEmpty()) {
                root.remove(key);
                return;
            }
            Tag tag = stack.saveOptional(lookup);
            if (tag instanceof CompoundTag ct) {
                root.put(key, ct);
            }
        } catch (Throwable ignored) {}
    }

    private static void stamp(ItemStack stack, UUID id, String slot) {
        try {
            if (stack == null || stack.isEmpty()) return;
            if (id == null || slot == null) return;
            CompoundTag custom = getCustomDataCopy(stack);
            custom.putUUID(ITEM_K_ID, id);
            custom.putString(ITEM_K_SLOT, slot);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
        } catch (Throwable ignored) {}
    }

    private static boolean isStamped(ItemStack stack, UUID id, String slot) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            if (id == null || slot == null) return false;
            CompoundTag custom = getCustomDataCopy(stack);
            if (!custom.hasUUID(ITEM_K_ID)) return false;
            if (!id.equals(custom.getUUID(ITEM_K_ID))) return false;
            return slot.equalsIgnoreCase(custom.getString(ITEM_K_SLOT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static CompoundTag getCustomDataCopy(ItemStack stack) {
        try {
            if (stack == null) return new CompoundTag();
            CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
            if (cd == null) return new CompoundTag();
            try {
                return cd.copyTag();
            } catch (Throwable ignored) {
                return new CompoundTag();
            }
        } catch (Throwable ignored) {
            return new CompoundTag();
        }
    }

    private static String safe(Object o) {
        try { return o == null ? "null" : String.valueOf(o); } catch (Throwable ignored) { return "err"; }
    }

    private static String safeItem(ItemStack st) {
        try {
            if (st == null || st.isEmpty()) return "empty";
            String id = "unknown";
            try { id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())); } catch (Throwable ignored) {}
            int n = 0;
            try { n = st.getCount(); } catch (Throwable ignored) {}
            return n + "x" + id;
        } catch (Throwable ignored) {
            return "err";
        }
    }
}
