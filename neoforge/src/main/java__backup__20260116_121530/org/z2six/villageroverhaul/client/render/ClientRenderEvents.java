// ClientRenderEvents.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/ClientRenderEvents.java
package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

/**
 * Client-only renderer wiring (registered from VillagerOverhaul main class on MOD bus).
 */
public final class ClientRenderEvents {

    private ClientRenderEvents() {}

    /**
     * MUST be registered on the MOD event bus:
     * modEventBus.addListener(ClientRenderEvents::onRegisterLayerDefinitions);
     */
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions e) {
        try {
            if (e == null) return;

            e.registerLayerDefinition(VillagerCombatArmsModel.LAYER_LOCATION, VillagerCombatArmsModel::createBodyLayer);

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Registered VillagerCombatArmsModel layer definition.");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onRegisterLayerDefinitions failed", t);
        }
    }

    /**
     * MUST be registered on the MOD event bus:
     * modEventBus.addListener(ClientRenderEvents::onAddLayers);
     */
    public static void onAddLayers(EntityRenderersEvent.AddLayers e) {
        try {
            if (e == null) return;

            // Get the villager renderer
            EntityRenderer<? super Villager> raw = e.getRenderer(EntityType.VILLAGER);
            if (!(raw instanceof LivingEntityRenderer<?, ?> livingRaw)) return;

            @SuppressWarnings("unchecked")
            LivingEntityRenderer<Villager, VillagerModel<Villager>> villagerRenderer =
                    (LivingEntityRenderer<Villager, VillagerModel<Villager>>) livingRaw;

            // --- Armor layer (your existing working one) ---
            HumanoidModel<LivingEntity> innerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            HumanoidModel<LivingEntity> outerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
            villagerRenderer.addLayer(new VillagerHumanoidArmorLayer(villagerRenderer, innerArmor, outerArmor));

            // --- Arms layer (NEW) ---
            VillagerCombatArmsModel armsModel = new VillagerCombatArmsModel(
                    e.getEntityModels().bakeLayer(VillagerCombatArmsModel.LAYER_LOCATION)
            );

            // Driver humanoid model: use a vanilla humanoid biped layer
            HumanoidModel<LivingEntity> driverHumanoid = new HumanoidModel<>(
                    e.getEntityModels().bakeLayer(ModelLayers.ZOMBIE)
            );

            villagerRenderer.addLayer(new VillagerHumanoidArmsLayer(villagerRenderer, armsModel, driverHumanoid));

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Added VillagerHumanoidArmorLayer + VillagerHumanoidArmsLayer to Villager renderer.");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onAddLayers failed", t);
        }
    }
}
