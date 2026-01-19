// neoforge\src\main\java\org\z2six\villageroverhaul\menu\VillagerInventoryMenu.java
package org.z2six.villageroverhaul.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.VillagerAccessGate;
import org.z2six.villageroverhaul.server.ai.VillagerBrain;

import java.lang.reflect.Method;

public final class VillagerInventoryMenu extends AbstractContainerMenu {

    // =========================
    // GUI SIZE (SHRUNK)
    // =========================
    public static final int IMAGE_W = 316;
    public static final int IMAGE_H = 214;

    // =========================
    // REAL SLOTS POSITIONS
    // (Must match VillagerInventoryScreen layout)
    // =========================

    // Equipment slots (6) LEFT side
    public static final int EQUIP_X = 10;
    public static final int ARMOR_Y0 = 40;  // head, chest, legs, feet
    public static final int HANDS_Y0 = 132; // mainhand, offhand

    // Villager pickup inventory (real slots): 4x2 BELOW the model
    public static final int VILL_INV_X0 = 46;
    public static final int VILL_INV_Y0 = 162;

    // Player inventory: RIGHT of model
    public static final int PLAYER_SLOTS_X0 = 142;
    public static final int PLAYER_SLOTS_Y0 = 46;

    // (Kept for consistency/debug)
    public static final int PLAYER_INV_X = PLAYER_SLOTS_X0 - 8;
    public static final int PLAYER_INV_Y = PLAYER_SLOTS_Y0 - 8;

    // =========================
    // Slot ranges (indices)
    // =========================
    private static final int EQUIP_START = 0;
    private static final int EQUIP_COUNT = 6;
    private static final int EQUIP_END_EXCL = EQUIP_START + EQUIP_COUNT;

    private static final int VILL_START = EQUIP_END_EXCL;      // 6
    private static final int VILL_COUNT = 8;
    private static final int VILL_END_EXCL = VILL_START + VILL_COUNT; // 14

    private static final int PLAYER_INV_START = VILL_END_EXCL; // 14
    private static final int PLAYER_INV_COUNT = 27;
    private static final int PLAYER_INV_END_EXCL = PLAYER_INV_START + PLAYER_INV_COUNT; // 41

    private static final int HOTBAR_START = PLAYER_INV_END_EXCL; // 41
    private static final int HOTBAR_COUNT = 9;
    private static final int HOTBAR_END_EXCL = HOTBAR_START + HOTBAR_COUNT; // 50

    private final int villagerEntityId;
    private final Container villagerInv; // size 8
    private final Container villagerEquip; // size 6 (proxy to EquipmentSlot)
    private final LivingEntity serverVillagerRefOrNull;

    public VillagerInventoryMenu(int containerId, Inventory playerInv, int villagerEntityId, Container villagerInv) {
        this(containerId, playerInv, villagerEntityId, villagerInv, null);
    }

    /**
     * Server-side convenience: pass the actual villager entity so equipment slots are direct and fast.
     */
    public VillagerInventoryMenu(int containerId, Inventory playerInv, int villagerEntityId, Container villagerInv, LivingEntity serverVillagerRefOrNull) {
        super(ModMenus.VILLAGER_INVENTORY.get(), containerId);
        this.villagerEntityId = villagerEntityId;
        this.villagerInv = (villagerInv == null) ? new SimpleContainer(8) : villagerInv;
        this.serverVillagerRefOrNull = serverVillagerRefOrNull;

        // Real equipment proxy (works on BOTH sides; client resolves entity via reflection)
        this.villagerEquip = new EntityEquipmentContainer(villagerEntityId, serverVillagerRefOrNull);

        // REAL slots: equipment first (indices 0..5)
        addEquipmentSlots(this.villagerEquip);

        // REAL slots: villager pickup inv (indices 6..13)
        addVillagerInventorySlots(this.villagerInv);

        // REAL slots: player inventory + hotbar (indices 14..49)
        addPlayerInventory(playerInv);
    }

    /**
     * NeoForge menu factory constructor: (int, Inventory, FriendlyByteBuf)
     * Client-side instance uses a SimpleContainer(8); server will sync contents for villager pickup inventory.
     * Equipment slots read from entity on client, via reflection lookup by entityId.
     */
    public VillagerInventoryMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        this(containerId, playerInv, safeReadVillagerId(buf), new SimpleContainer(8), null);
    }

    public int getVillagerEntityId() {
        return villagerEntityId;
    }

    @Override
    public boolean stillValid(Player player) {
        // IMPORTANT: enforce gate on SERVER so hacked clients cannot keep it open.
        try {
            if (!(player instanceof ServerPlayer sp)) return true; // client-side: let server decide

            var level = sp.serverLevel();
            if (level == null) return false;

            Entity e = level.getEntity(this.villagerEntityId);
            if (!(e instanceof Villager vill)) return false;
            if (!vill.isAlive()) return false;

            // HARD GATE
            if (!VillagerAccessGate.canUseControls(vill, sp)) return false;

            // Optional proximity (like vanilla containers)
            double dist2 = sp.distanceToSqr(vill);
            return dist2 <= (8.0 * 8.0);

        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        try {
            ItemStack empty = ItemStack.EMPTY;
            if (index < 0 || index >= this.slots.size()) return empty;

            Slot slot = this.slots.get(index);
            if (slot == null || !slot.hasItem()) return empty;

            ItemStack stackInSlot = slot.getItem();
            ItemStack copy = stackInSlot.copy();

            // Ranges are defined above.

            if (index >= EQUIP_START && index < EQUIP_END_EXCL) {
                // equipment -> player
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END_EXCL, true)) return empty;
            } else if (index >= VILL_START && index < VILL_END_EXCL) {
                // villager pickup -> player
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END_EXCL, true)) return empty;
            } else if (index >= PLAYER_INV_START && index < HOTBAR_END_EXCL) {
                // player -> try equipment first, then villager pickup, then inv<->hotbar swap
                if (!this.moveItemStackTo(stackInSlot, EQUIP_START, EQUIP_END_EXCL, false)) {
                    if (!this.moveItemStackTo(stackInSlot, VILL_START, VILL_END_EXCL, false)) {
                        if (index < HOTBAR_START) {
                            if (!this.moveItemStackTo(stackInSlot, HOTBAR_START, HOTBAR_END_EXCL, false)) return empty;
                        } else {
                            if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_START, false)) return empty;
                        }
                    }
                }
            } else {
                return empty;
            }

            if (stackInSlot.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();

            return copy;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerInventoryMenu.quickMoveStack failed (soft): {}", t.toString());
            return ItemStack.EMPTY;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Add slots
    // -----------------------------------------------------------------------------------------

    private void addEquipmentSlots(Container equip) {
        // Armor: head, chest, legs, feet
        this.addSlot(new EquipmentProxySlot(equip, 0, EQUIP_X, ARMOR_Y0 + 0 * 22, EquipmentSlot.HEAD,  "Helmet"));
        this.addSlot(new EquipmentProxySlot(equip, 1, EQUIP_X, ARMOR_Y0 + 1 * 22, EquipmentSlot.CHEST, "Chestplate"));
        this.addSlot(new EquipmentProxySlot(equip, 2, EQUIP_X, ARMOR_Y0 + 2 * 22, EquipmentSlot.LEGS,  "Leggings"));
        this.addSlot(new EquipmentProxySlot(equip, 3, EQUIP_X, ARMOR_Y0 + 3 * 22, EquipmentSlot.FEET,  "Boots"));

        // Hands: main, off
        this.addSlot(new EquipmentProxySlot(equip, 4, EQUIP_X, HANDS_Y0 + 0 * 22, EquipmentSlot.MAINHAND, "Main Hand"));
        this.addSlot(new EquipmentProxySlot(equip, 5, EQUIP_X, HANDS_Y0 + 1 * 22, EquipmentSlot.OFFHAND,  "Offhand"));
    }

    private void addVillagerInventorySlots(Container inv) {
        // 4x2 = 8
        for (int i = 0; i < 8; i++) {
            int col = i % 4;
            int row = i / 4;

            int x = VILL_INV_X0 + (col * 18);
            int y = VILL_INV_Y0 + (row * 18);

            this.addSlot(new Slot(inv, i, x, y));
        }
    }

    private void addPlayerInventory(Inventory inv) {
        // 3 rows of 9 (main inventory)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + (row * 9) + col;
                int x = PLAYER_SLOTS_X0 + (col * 18);
                int y = PLAYER_SLOTS_Y0 + (row * 18);
                this.addSlot(new Slot(inv, slotIndex, x, y));
            }
        }

        // hotbar
        for (int col = 0; col < 9; col++) {
            int slotIndex = col;
            int x = PLAYER_SLOTS_X0 + (col * 18);
            int y = PLAYER_SLOTS_Y0 + 58;
            this.addSlot(new Slot(inv, slotIndex, x, y));
        }
    }

    private static int safeReadVillagerId(FriendlyByteBuf buf) {
        try {
            if (buf == null) return 0;
            return buf.readVarInt();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Equipment proxy container + slot
    // -----------------------------------------------------------------------------------------

    /**
     * Container(6) that maps indices to villager equipment slots.
     * Works on server with direct reference; on client resolves entity via reflection (Minecraft.getInstance()).
     */
    public static final class EntityEquipmentContainer implements Container {
        private final int entityId;
        private final LivingEntity serverRefOrNull;

        public EntityEquipmentContainer(int entityId, LivingEntity serverRefOrNull) {
            this.entityId = entityId;
            this.serverRefOrNull = serverRefOrNull;
        }

        @Override
        public int getContainerSize() {
            return 6;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < 6; i++) {
                if (!getItem(i).isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            try {
                LivingEntity le = resolve();
                if (le == null) return ItemStack.EMPTY;

                EquipmentSlot slot = mapIndex(index);
                if (slot == null) return ItemStack.EMPTY;

                // FIX: use hand APIs for hands
                if (slot == EquipmentSlot.MAINHAND) {
                    ItemStack st = le.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
                    return st == null ? ItemStack.EMPTY : st;
                }
                if (slot == EquipmentSlot.OFFHAND) {
                    ItemStack st = le.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
                    return st == null ? ItemStack.EMPTY : st;
                }

                ItemStack st = le.getItemBySlot(slot);
                return st == null ? ItemStack.EMPTY : st;

            } catch (Throwable ignored) {
                return ItemStack.EMPTY;
            }
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            try {
                if (count <= 0) return ItemStack.EMPTY;

                LivingEntity le = resolve();
                if (le == null) return ItemStack.EMPTY;

                EquipmentSlot slot = mapIndex(index);
                if (slot == null) return ItemStack.EMPTY;

                ItemStack cur = getItem(index);
                if (cur.isEmpty()) return ItemStack.EMPTY;

                ItemStack out = cur.copy();

                // FIX: use hand APIs for hands
                if (slot == EquipmentSlot.MAINHAND) {
                    // IMPORTANT: tell VillagerBrain this was intentional
                    VillagerBrain.notifyManualHandSet(le, EquipmentSlot.MAINHAND, ItemStack.EMPTY, "menu_remove");
                    le.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    setChanged();
                    return out;
                }
                if (slot == EquipmentSlot.OFFHAND) {
                    // IMPORTANT: tell VillagerBrain this was intentional
                    VillagerBrain.notifyManualHandSet(le, EquipmentSlot.OFFHAND, ItemStack.EMPTY, "menu_remove");
                    le.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
                    setChanged();
                    return out;
                }

                le.setItemSlot(slot, ItemStack.EMPTY);
                setChanged();
                return out;

            } catch (Throwable ignored) {
                return ItemStack.EMPTY;
            }
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            return removeItem(index, 64);
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            try {
                LivingEntity le = resolve();
                if (le == null) return;

                EquipmentSlot slot = mapIndex(index);
                if (slot == null) return;

                ItemStack toSet = (stack == null) ? ItemStack.EMPTY : stack;

                // FIX: use hand APIs for hands
                if (slot == EquipmentSlot.MAINHAND) {
                    if (toSet.isEmpty()) {
                        VillagerBrain.notifyManualHandSet(le, EquipmentSlot.MAINHAND, ItemStack.EMPTY, "menu_clear");
                    }
                    le.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, toSet);
                    setChanged();

                    // IMPORTANT: tell VillagerBrain this was intentional
                    VillagerBrain.notifyManualHandSet(le, EquipmentSlot.MAINHAND, toSet, "menu_set");
                    return;
                }
                if (slot == EquipmentSlot.OFFHAND) {
                    if (toSet.isEmpty()) {
                        VillagerBrain.notifyManualHandSet(le, EquipmentSlot.OFFHAND, ItemStack.EMPTY, "menu_clear");
                    }
                    le.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, toSet);
                    setChanged();

                    // IMPORTANT: tell VillagerBrain this was intentional
                    VillagerBrain.notifyManualHandSet(le, EquipmentSlot.OFFHAND, toSet, "menu_set");
                    return;
                }

                le.setItemSlot(slot, toSet);
                setChanged();

            } catch (Throwable ignored) {}
        }

        @Override
        public void setChanged() {
            // no-op; entity tracking should sync
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < 6; i++) setItem(i, ItemStack.EMPTY);
        }

        private static EquipmentSlot mapIndex(int i) {
            return switch (i) {
                case 0 -> EquipmentSlot.HEAD;
                case 1 -> EquipmentSlot.CHEST;
                case 2 -> EquipmentSlot.LEGS;
                case 3 -> EquipmentSlot.FEET;
                case 4 -> EquipmentSlot.MAINHAND;
                case 5 -> EquipmentSlot.OFFHAND;
                default -> null;
            };
        }

        private LivingEntity resolve() {
            try {
                if (serverRefOrNull != null) return serverRefOrNull;

                Class<?> mcClz = Class.forName("net.minecraft.client.Minecraft");
                Object mc = mcClz.getMethod("getInstance").invoke(null);
                if (mc == null) return null;

                Object level = mcClz.getField("level").get(mc);
                if (level == null) return null;

                Method getEntity = level.getClass().getMethod("getEntity", int.class);
                Object e = getEntity.invoke(level, this.entityId);
                if (e instanceof LivingEntity le) return le;

                return null;

            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static final class EquipmentProxySlot extends Slot {
        private final EquipmentSlot equipSlot;
        private final String emptyLabel;

        public EquipmentProxySlot(Container container, int index, int x, int y, EquipmentSlot equipSlot, String emptyLabel) {
            super(container, index, x, y);
            this.equipSlot = equipSlot;
            this.emptyLabel = emptyLabel == null ? "" : emptyLabel;
        }

        public EquipmentSlot getEquipSlot() { return equipSlot; }
        public String getEmptyLabel() { return emptyLabel; }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            try {
                if (stack == null || stack.isEmpty()) return false;
                if (equipSlot == null) return false;

                // Hands: allow anything (we can tighten later if desired)
                if (equipSlot == EquipmentSlot.MAINHAND || equipSlot == EquipmentSlot.OFFHAND) {
                    return true;
                }

                // ARMOR: STRICT. Only allow items that belong in THIS slot.
                // 1) Vanilla armor
                if (stack.getItem() instanceof ArmorItem ai) {
                    return ai.getEquipmentSlot() == equipSlot;
                }

                // 2) Version-safe: LivingEntity.getEquipmentSlotForItem(stack)
                try {
                    Method m = LivingEntity.class.getMethod("getEquipmentSlotForItem", ItemStack.class);
                    Object out = m.invoke(null, stack);
                    if (out instanceof EquipmentSlot es) {
                        return es == equipSlot;
                    }
                } catch (Throwable ignored) {}

                // If we can't prove it belongs in this armor slot, reject.
                return false;

            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    // ---------------------------
    // Server helper for MenuProvider (uses reflection to get villager pickup inv)
    // ---------------------------

    public static Container tryGetVillagerPickupInventory(Villager vill) {
        try {
            if (vill == null) return null;

            Method m = null;
            Class<?> c = vill.getClass();
            while (c != null && c != Object.class) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm == null) continue;
                    if (!"getInventory".equals(mm.getName())) continue;
                    if (mm.getParameterCount() != 0) continue;
                    mm.setAccessible(true);
                    m = mm;
                    break;
                }
                if (m != null) break;
                c = c.getSuperclass();
            }
            if (m == null) return null;

            Object out = m.invoke(vill);
            if (out instanceof Container cont) return cont;

            return null;

        } catch (Throwable ignored) {
            return null;
        }
    }

    public static SimpleMenuProvider providerFor(ServerPlayer sp, Villager vill) {
        final int id = (vill == null) ? 0 : vill.getId();
        return new SimpleMenuProvider(
                (containerId, playerInv, player) -> {
                    Container pickup = tryGetVillagerPickupInventory(vill);
                    if (pickup == null) pickup = new SimpleContainer(8);

                    // IMPORTANT: pass villager ref so equipment proxy is direct on server
                    return new VillagerInventoryMenu(containerId, playerInv, id, pickup, vill);
                },
                net.minecraft.network.chat.Component.literal("Villager Inventory")
        );
    }
}
