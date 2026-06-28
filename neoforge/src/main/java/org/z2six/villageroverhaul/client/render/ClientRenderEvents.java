// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/ClientRenderEvents.java
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
import org.z2six.villageroverhaul.content.ModBlockEntities;

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
            e.registerLayerDefinition(DwarfVillagerModel.LAYER_LOCATION, DwarfVillagerModel::createBodyLayer);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Registered villager custom layer definitions.");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onRegisterLayerDefinitions failed", t);
        }
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers e) {
        try {
            if (e == null) return;
            e.registerBlockEntityRenderer(ModBlockEntities.TRADING_HALL.get(), TradingHallBlockEntityRenderer::new);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onRegisterRenderers failed", t);
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

            // Wrap vanilla crossed-arms chest-item layer(s) with a per-villager flag gate.
            int wrapped = VillagerVanillaCrossedArmsItemLayerGate.install(villagerRenderer);
            if (wrapped > 0) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Wrapped {} vanilla crossed-arms item layer(s) with flag gate.", wrapped);
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] No vanilla crossed-arms item layer found to wrap (ok).");
            }

            // Driver humanoid model: shared by arms + armor so modded armor arm parts follow the same animation.
            HumanoidModel<LivingEntity> driverHumanoid = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.ZOMBIE));

            // --- Arms model (shared between arms + held-item layers) ---
            VillagerCombatArmsModel armsModel = new VillagerCombatArmsModel(e.getEntityModels().bakeLayer(VillagerCombatArmsModel.LAYER_LOCATION));
            VillagerHumanoidArmsLayer armsLayer = new VillagerHumanoidArmsLayer(villagerRenderer, armsModel, driverHumanoid);

            DwarfVillagerModel dwarfModel = new DwarfVillagerModel(e.getEntityModels().bakeLayer(DwarfVillagerModel.LAYER_LOCATION));
            villagerRenderer.addLayer(new DwarfVillagerLayer(villagerRenderer, dwarfModel, armsLayer));

            // --- Custom arms (renders the geometry) ---
            // Add BEFORE armor so armor renders on top of the arms skin.
            villagerRenderer.addLayer(armsLayer);

            // --- Armor layer ---
            HumanoidModel<LivingEntity> innerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            HumanoidModel<LivingEntity> outerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
            villagerRenderer.addLayer(new VillagerHumanoidArmorLayer(villagerRenderer, innerArmor, outerArmor, driverHumanoid, armsModel));

            // --- Held items (renders mainhand + offhand anchored to the custom arms pose) ---
            villagerRenderer.addLayer(new VillagerHumanoidHeldItemLayer(villagerRenderer, armsModel, dwarfModel));

            // --- Holstered loadout (renders while NOT holding items) ---
            villagerRenderer.addLayer(new VillagerHolsteredLoadoutLayer(villagerRenderer));

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] Added VillagerHumanoidArmorLayer + VillagerHumanoidArmsLayer + VillagerHumanoidHeldItemLayer + VillagerHolsteredLoadoutLayer to Villager renderer.");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onAddLayers failed", t);
        }
    }
}
