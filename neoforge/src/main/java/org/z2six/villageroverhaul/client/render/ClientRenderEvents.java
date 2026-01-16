// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/render/ClientRenderEvents.java
package org.z2six.villageroverhaul.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

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

            // IMPORTANT:
            // Remove the vanilla "crossed arms item" layer (the one that renders the held item on the chest).
            // This is the thing causing the duplicate item render you still see.
            int removed = removeVanillaCrossedArmsItemLayer(villagerRenderer);
            if (removed > 0) {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Removed {} vanilla CrossedArmsItemLayer(s) from Villager renderer.", removed);
            } else {
                VillagerOverhaul.LOG().info("[VillagerOverhaul] No vanilla CrossedArmsItemLayer found to remove (ok).");
            }

            // --- Armor layer (your existing working one) ---
            HumanoidModel<LivingEntity> innerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            HumanoidModel<LivingEntity> outerArmor = new HumanoidModel<>(e.getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
            villagerRenderer.addLayer(new VillagerHumanoidArmorLayer(villagerRenderer, innerArmor, outerArmor));

            // --- Arms model (shared between arms + held-item layers) ---
            VillagerCombatArmsModel armsModel = new VillagerCombatArmsModel(
                    e.getEntityModels().bakeLayer(VillagerCombatArmsModel.LAYER_LOCATION)
            );

            // Driver humanoid model: use a vanilla humanoid biped layer
            HumanoidModel<LivingEntity> driverHumanoid = new HumanoidModel<>(
                    e.getEntityModels().bakeLayer(ModelLayers.ZOMBIE)
            );

            // --- Custom arms (renders the geometry) ---
            villagerRenderer.addLayer(new VillagerHumanoidArmsLayer(villagerRenderer, armsModel, driverHumanoid));

            // --- Held items (renders mainhand + offhand anchored to the custom arms pose) ---
            villagerRenderer.addLayer(new VillagerHumanoidHeldItemLayer(villagerRenderer, armsModel));

            VillagerOverhaul.LOG().info("[VillagerOverhaul] Added VillagerHumanoidArmorLayer + VillagerHumanoidArmsLayer + VillagerHumanoidHeldItemLayer to Villager renderer.");

        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientRenderEvents.onAddLayers failed", t);
        }
    }

    /**
     * Removes vanilla layer(s) responsible for rendering the held item in front of the villager chest
     * (CrossedArmsItemLayer or subclass).
     *
     * We do this via reflection because LivingEntityRenderer#layers isn't public and mappings drift.
     */
    private static int removeVanillaCrossedArmsItemLayer(LivingEntityRenderer<?, ?> renderer) {
        int removed = 0;

        try {
            if (renderer == null) return 0;

            // Best-effort: load the class if present (MojMap/NeoForge should have it)
            Class<?> crossedLayerClass = null;
            try {
                crossedLayerClass = Class.forName("net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer");
            } catch (Throwable ignored) {
                // If it doesn't exist under this name, we'll fall back to name-based checks below.
            }

            // Find the layers list field
            List<?> layersList = null;
            Field layersField = null;

            Class<?> c = renderer.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);

                    Object v = f.get(renderer);
                    if (!(v instanceof List<?> list)) continue;

                    // Heuristic: does it contain RenderLayer-like objects (or is it empty but plausible)?
                    if (list.isEmpty()) {
                        // Could still be the layers list; keep searching but remember candidate.
                        if (layersList == null) {
                            layersList = list;
                            layersField = f;
                        }
                        continue;
                    }

                    Object first = list.get(0);
                    if (first instanceof RenderLayer<?, ?>) {
                        layersList = list;
                        layersField = f;
                        break;
                    }
                }
                if (layersList != null) break;
                c = c.getSuperclass();
            }

            if (layersList == null) return 0;

            // Remove matching layers from the list in-place
            @SuppressWarnings("unchecked")
            List<Object> layers = (List<Object>) layersList;

            Iterator<Object> it = layers.iterator();
            while (it.hasNext()) {
                Object layer = it.next();
                if (layer == null) continue;

                if (isCrossedArmsItemLayer(layer, crossedLayerClass)) {
                    it.remove();
                    removed++;
                }
            }

            // Just in case some impls wrap list immutably (rare), attempt set back
            if (layersField != null) {
                try {
                    layersField.set(renderer, layers);
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] Failed removing vanilla CrossedArmsItemLayer (soft): {}", t.toString());
        }

        return removed;
    }

    private static boolean isCrossedArmsItemLayer(Object layer, Class<?> crossedLayerClassOrNull) {
        try {
            if (layer == null) return false;

            // 1) Strong check: class-based
            if (crossedLayerClassOrNull != null && crossedLayerClassOrNull.isInstance(layer)) {
                return true;
            }

            // 2) Name-based fallback (handles subclasses too)
            String name = layer.getClass().getName();
            if (name == null) return false;

            // Exact / common patterns:
            // - net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer
            // - net.minecraft.client.renderer.entity.layers.VillagerItemLayer (example subclass naming)
            // - anything containing "CrossedArmsItemLayer"
            return name.contains("CrossedArmsItemLayer") || name.contains("VillagerItem") || name.contains("VillagerHeldItem");

        } catch (Throwable ignored) {
            return false;
        }
    }
}
