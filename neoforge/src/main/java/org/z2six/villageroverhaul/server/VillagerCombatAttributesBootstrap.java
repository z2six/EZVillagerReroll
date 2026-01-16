// neoforge\src\main\java\org\z2six\villageroverhaul\server\VillagerCombatAttributesBootstrap.java
package org.z2six.villageroverhaul.server;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * Ensures villager-like entities actually HAVE the vanilla attributes we want to modify.
 *
 * Villagers do NOT include ATTACK_DAMAGE by default, so applying Strength would fail (AttributeInstance null).
 *
 * This runs on the MOD event bus (not the gameplay bus).
 */
public final class VillagerCombatAttributesBootstrap {

    private static volatile boolean registered = false;

    private VillagerCombatAttributesBootstrap() {}

    public static void register(IEventBus modBus) {
        if (modBus == null) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] VillagerCombatAttributesBootstrap.register called with null modBus");
            return;
        }
        if (registered) return;
        registered = true;

        modBus.addListener(VillagerCombatAttributesBootstrap::onEntityAttributeModification);
        VillagerOverhaul.LOG().info("[VillagerOverhaul] VillagerCombatAttributesBootstrap registered (mod bus).");
    }

    private static void onEntityAttributeModification(EntityAttributeModificationEvent e) {
        try {
            if (e == null) return;

            // Villager + Wandering Trader cover most “villager-like merchants”.
            ensureFor(e, EntityType.VILLAGER);
            ensureFor(e, EntityType.WANDERING_TRADER);

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] onEntityAttributeModification failed", t);
        }
    }

    // IMPORTANT: must be LivingEntity types, because the event only allows attributes for LivingEntity types.
    private static void ensureFor(EntityAttributeModificationEvent e, EntityType<? extends LivingEntity> type) {
        try {
            if (e == null || type == null) return;

            // Already exist on villagers, but adding is harmless if present.
            e.add(type, Attributes.MAX_HEALTH);
            e.add(type, Attributes.MOVEMENT_SPEED);

            // These are the key ones villagers normally DON'T have:
            e.add(type, Attributes.ATTACK_DAMAGE);
            e.add(type, Attributes.ARMOR);

            // Optional: only matters if you decide to use toughness later
            e.add(type, Attributes.ARMOR_TOUGHNESS);

        } catch (Throwable ignored) {
            // soft
        }
    }
}
