package org.z2six.villageroverhaul.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.server.VillagerAccessGate;

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

    // Villager pickup inventory (real slots): 4x2 BELOW the model
    // (Centered under model box)
    public static final int VILL_INV_X0 = 46;
    public static final int VILL_INV_Y0 = 162;

    // Player inventory: RIGHT of model
    // (3 rows + hotbar)
    public static final int PLAYER_SLOTS_X0 = 142;
    public static final int PLAYER_SLOTS_Y0 = 46;

    // (These two are no longer used for labels, but kept for consistency/debug)
    public static final int PLAYER_INV_X = PLAYER_SLOTS_X0 - 8;
    public static final int PLAYER_INV_Y = PLAYER_SLOTS_Y0 - 8;

    private final int villagerEntityId;
    private final Container villagerInv; // size 8

    public VillagerInventoryMenu(int containerId, Inventory playerInv, int villagerEntityId, Container villagerInv) {
        super(ModMenus.VILLAGER_INVENTORY.get(), containerId);
        this.villagerEntityId = villagerEntityId;
        this.villagerInv = (villagerInv == null) ? new SimpleContainer(8) : villagerInv;

        // REAL slots: villager pickup inv (8)
        addVillagerInventorySlots(this.villagerInv);

        // REAL slots: player inventory + hotbar
        addPlayerInventory(playerInv);
    }

    /**
     * NeoForge menu factory constructor: (int, Inventory, FriendlyByteBuf)
     * Client-side instance uses a SimpleContainer(8); server will sync contents.
     */
    public VillagerInventoryMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        this(containerId, playerInv, safeReadVillagerId(buf), new SimpleContainer(8));
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

            // Layout:
            // 0..7    = villager pickup inv (8)
            // 8..34   = player main (27)
            // 35..43  = hotbar (9)
            final int VILL_START = 0;
            final int VILL_END_EXCL = 8;

            final int PLAYER_INV_START = 8;
            final int PLAYER_INV_END_EXCL = 8 + 27;

            final int HOTBAR_START = PLAYER_INV_END_EXCL;
            final int HOTBAR_END_EXCL = HOTBAR_START + 9;

            if (index >= VILL_START && index < VILL_END_EXCL) {
                // villager -> player (inv + hotbar)
                if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_END_EXCL, true)) return empty;
            } else if (index >= PLAYER_INV_START && index < HOTBAR_END_EXCL) {
                // player -> villager
                if (!this.moveItemStackTo(stackInSlot, VILL_START, VILL_END_EXCL, false)) {
                    // if can't go to villager, do normal inv<->hotbar swap
                    if (index < HOTBAR_START) {
                        if (!this.moveItemStackTo(stackInSlot, HOTBAR_START, HOTBAR_END_EXCL, false)) return empty;
                    } else {
                        if (!this.moveItemStackTo(stackInSlot, PLAYER_INV_START, HOTBAR_START, false)) return empty;
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
                    return new VillagerInventoryMenu(containerId, playerInv, id, pickup);
                },
                net.minecraft.network.chat.Component.literal("Villager Inventory")
        );
    }
}
