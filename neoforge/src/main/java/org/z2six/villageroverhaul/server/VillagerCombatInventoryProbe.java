// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerCombatInventoryProbe.java
package org.z2six.villageroverhaul.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Logs what equipment/inventory "slots" a villager already has (and what's in them),
 * and provides a placeholder hook for later "ensure combat inventory" logic.
 *
 * IMPORTANT:
 * - Equipment slots (armor/offhand/mainhand) already exist for all LivingEntity types.
 * - Extra "inventory slots" beyond vanilla villager pickup inventory will need to be implemented by us later
 *   (persistent data / custom container / custom menu).
 */
public final class VillagerCombatInventoryProbe {

    private VillagerCombatInventoryProbe() {}

    // Separate from stats tag; this is just for one-time spawn/join logging.
    private static final String TAG_ROOT = "ezvr_combat_inv_probe";
    private static final String K_LOGGED = "logged_v1";

    // Placeholder: we will flip this later once we actually implement “add things we need”
    // (e.g., persistent equipment container + GUI, ownership gates, syncing, etc.)
    private static final boolean ENABLE_MUTATIONS = false;

    public static void inspectAndPrepareIfNeeded(Entity e) {
        try {
            if (e == null) return;
            if (!(e instanceof Villager vill)) return; // focus villager for now
            if (vill.level() == null || vill.level().isClientSide()) return;

            if (alreadyLogged(vill)) return;
            markLogged(vill);

            HolderLookup.Provider lookup = safeLookup(vill);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ===== VillagerCombatInventoryProbe BEGIN entityId={} uuid={} =====",
                    vill.getId(), vill.getUUID());

            // 1) Check the "things we need" (equipment slots exist on LivingEntity)
            logEquipmentSlotSupportAndContents(vill, lookup);

            // 2) Check and dump villager pickup inventory (carrots/etc)
            logVillagerPickupInventory(vill, lookup);

            // 3) Log additional useful state (helps debugging later)
            logOtherSignals(vill, lookup);

            // 4) Placeholder hook for “add things we need”
            ensureCombatInventoryPlaceholder(vill);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] ===== VillagerCombatInventoryProbe END entityId={} uuid={} =====",
                    vill.getId(), vill.getUUID());

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] VillagerCombatInventoryProbe.inspectAndPrepareIfNeeded failed (soft): {}", t.toString());
        }
    }

    private static HolderLookup.Provider safeLookup(Entity e) {
        try {
            if (e == null || e.level() == null) return null;
            // In modern MC, RegistryAccess implements HolderLookup.Provider
            return e.level().registryAccess();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logEquipmentSlotSupportAndContents(LivingEntity le, HolderLookup.Provider lookup) {
        try {
            boolean hasMainhand = supportsSlot(le, EquipmentSlot.MAINHAND);
            boolean hasOffhand  = supportsSlot(le, EquipmentSlot.OFFHAND);
            boolean hasHead     = supportsSlot(le, EquipmentSlot.HEAD);
            boolean hasChest    = supportsSlot(le, EquipmentSlot.CHEST);
            boolean hasLegs     = supportsSlot(le, EquipmentSlot.LEGS);
            boolean hasFeet     = supportsSlot(le, EquipmentSlot.FEET);

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Combat slot support: mainhand={} offhand={} armor[head={},chest={},legs={},feet={}]",
                    hasMainhand, hasOffhand, hasHead, hasChest, hasLegs, hasFeet
            );

            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = ItemStack.EMPTY;
                boolean ok = false;
                try {
                    stack = le.getItemBySlot(slot);
                    ok = true;
                } catch (Throwable ignored) {
                    ok = false;
                }

                String slotName = (slot == null) ? "null" : slot.getName();
                if (slotName == null) slotName = String.valueOf(slot);

                if (!ok) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] Slot {}: <unreadable>", slotName);
                    continue;
                }

                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Slot {}: {}",
                        slotName.toLowerCase(Locale.ROOT),
                        stackToString(stack, lookup)
                );
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] logEquipmentSlotSupportAndContents failed (soft): {}", t.toString());
        }
    }

    private static boolean supportsSlot(LivingEntity le, EquipmentSlot slot) {
        try {
            if (le == null || slot == null) return false;
            le.getItemBySlot(slot);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logVillagerPickupInventory(Villager vill, HolderLookup.Provider lookup) {
        try {
            Container inv = tryGetVillagerInventoryViaMethod(vill);
            if (inv == null) inv = tryFindContainerByReflection(vill);

            if (inv == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Villager pickup inventory: <not found>");
                return;
            }

            int size = safeContainerSize(inv);
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Villager pickup inventory found: class={} size={}",
                    inv.getClass().getName(), size);

            for (int i = 0; i < size; i++) {
                ItemStack st = ItemStack.EMPTY;
                try { st = inv.getItem(i); } catch (Throwable ignored) { st = ItemStack.EMPTY; }
                VillagerOverhaul.LOG().debug("[VillagerOverhaul]  inv[{}] = {}", i, stackToString(st, lookup));
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] logVillagerPickupInventory failed (soft): {}", t.toString());
        }
    }

    private static Container tryGetVillagerInventoryViaMethod(Villager vill) {
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

    private static Container tryFindContainerByReflection(Object holder) {
        try {
            if (holder == null) return null;

            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f == null) continue;
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (v instanceof Container cont) return cont;
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }

            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int safeContainerSize(Container c) {
        try {
            if (c == null) return 0;
            int s = c.getContainerSize();
            if (s < 0) s = 0;
            if (s > 256) s = 256; // sanity bound for logging
            return s;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void logOtherSignals(Villager vill, HolderLookup.Provider lookup) {
        try {
            String prof = "?";
            String type = "?";
            int level = -1;
            try { prof = String.valueOf(vill.getVillagerData().getProfession()); } catch (Throwable ignored) {}
            try { type = String.valueOf(vill.getVillagerData().getType()); } catch (Throwable ignored) {}
            try { level = vill.getVillagerData().getLevel(); } catch (Throwable ignored) {}

            boolean canPickup = false;
            try { canPickup = vill.canPickUpLoot(); } catch (Throwable ignored) {}

            boolean baby = false;
            try { baby = vill.isBaby(); } catch (Throwable ignored) {}

            VillagerOverhaul.LOG().debug(
                    "[VillagerOverhaul] Villager misc: profession={} type={} level={} isBaby={} canPickUpLoot={}",
                    prof, type, level, baby, canPickup
            );

            try {
                int i = 0;
                for (ItemStack st : vill.getHandSlots()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] HandSlots[{}] = {}", i++, stackToString(st, lookup));
                }
            } catch (Throwable ignored) {}

            try {
                int i = 0;
                for (ItemStack st : vill.getArmorSlots()) {
                    VillagerOverhaul.LOG().debug("[VillagerOverhaul] ArmorSlots[{}] = {}", i++, stackToString(st, lookup));
                }
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {
            // soft
        }
    }

    private static void ensureCombatInventoryPlaceholder(Villager vill) {
        try {
            if (vill == null) return;

            if (!ENABLE_MUTATIONS) {
                VillagerOverhaul.LOG().debug(
                        "[VillagerOverhaul] Combat inventory ensure: placeholder (mutations disabled). " +
                                "Later this will initialize custom equipment storage + syncing + GUI."
                );
                return;
            }

            // ============================================================
            // PLACEHOLDER: future logic goes here (DO NOT EXECUTE YET)
            // ============================================================
            // Example direction (not implemented):
            // - attach persistent container in villager persistent data
            // - ensure it has N slots (armor/offhand/mainhand + extra storage)
            // - migrate from any legacy formats
            // - if needed, mirror equipped items into vanilla EquipmentSlot
            // ============================================================

        } catch (Throwable ignored) {}
    }

    private static boolean alreadyLogged(Entity e) {
        try {
            CompoundTag pd = e.getPersistentData();
            if (pd == null) return false;
            if (!pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) return false;

            CompoundTag root = pd.getCompound(TAG_ROOT);
            return root.getBoolean(K_LOGGED);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markLogged(Entity e) {
        try {
            CompoundTag pd = e.getPersistentData();
            if (pd == null) return;

            CompoundTag root;
            if (pd.contains(TAG_ROOT, Tag.TAG_COMPOUND)) root = pd.getCompound(TAG_ROOT);
            else root = new CompoundTag();

            root.putBoolean(K_LOGGED, true);
            pd.put(TAG_ROOT, root);
        } catch (Throwable ignored) {}
    }

    /**
     * Modern-safe “dump everything” string:
     * - Item id + count
     * - Damage if applicable
     * - Serialized tag-ish representation via ItemStack#save(lookup, CompoundTag)
     *
     * This replaces the old hasTag/getTag API (removed in data-components versions).
     */
    private static String stackToString(ItemStack st, HolderLookup.Provider lookup) {
        try {
            if (st == null || st.isEmpty()) return "<empty>";

            String itemId;
            try {
                itemId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()));
            } catch (Throwable t) {
                itemId = String.valueOf(st.getItem());
            }

            int count = st.getCount();

            int dmg = 0;
            boolean dmgable = false;
            try {
                dmgable = st.isDamageableItem();
                dmg = st.getDamageValue();
            } catch (Throwable ignored) {}

            String extra = "";
            try {
                if (lookup != null) {
                    Tag saved = st.save(lookup, new CompoundTag());
                    if (saved != null) {
                        String s = saved.toString();
                        if (s.length() > 180) s = s.substring(0, 180) + "…";
                        extra = " tag=" + s;
                    }
                }
            } catch (Throwable ignored) {
                // omit tag dump
            }

            if (dmgable) {
                return itemId + " x" + count + " dmg=" + dmg + extra;
            }
            return itemId + " x" + count + extra;

        } catch (Throwable t) {
            return "<stack_to_string_error>";
        }
    }
}
