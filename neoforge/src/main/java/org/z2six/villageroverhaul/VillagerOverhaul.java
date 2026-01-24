// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/VillagerOverhaul.java
package org.z2six.villageroverhaul;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.villageroverhaul.client.ClientCommands;
import org.z2six.villageroverhaul.client.ClientUI;
import org.z2six.villageroverhaul.client.ClientKeybinds;
import org.z2six.villageroverhaul.client.VillagerInventoryScreen;
import org.z2six.villageroverhaul.client.render.ClientRenderEvents;
import org.z2six.villageroverhaul.config.ClientConfig;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.menu.ModMenus;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.server.BusyVillagerBlocker;
import org.z2six.villageroverhaul.server.CombatBlockDiagnostics;
import org.z2six.villageroverhaul.server.ServerCommands;
import org.z2six.villageroverhaul.server.ServerEvents;
import org.z2six.villageroverhaul.server.VillagerCombatAttributesBootstrap;
import org.z2six.villageroverhaul.server.ai.VillagerManualShieldBlocker;

@Mod(Constants.MOD_ID)
public final class VillagerOverhaul {

    private static final Logger LOG = LogUtils.getLogger();

    public VillagerOverhaul(final IEventBus modBus, final ModContainer modContainer) {
        LOG.info("[VillagerOverhaul] Initializing VillagerOverhaul (modid={})", Constants.MOD_ID);

        // --- Register configs (MOD lifecycle) ---
        try {
            modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
            LOG.info("[VillagerOverhaul] Registered SERVER config spec.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register SERVER config spec (continuing).", t);
        }

        try {
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            LOG.info("[VillagerOverhaul] Registered CLIENT config spec.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register CLIENT config spec (continuing).", t);
        }

        // --- Menu Types ---
        try {
            ModMenus.MENUS.register(modBus);
            LOG.info("[VillagerOverhaul] Registered menu types.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register menu types (continuing).", t);
        }

        // --- Network payload registration ---
        try {
            modBus.addListener(Network::onRegisterPayloadHandlers);
            LOG.info("[VillagerOverhaul] Registered Network payload handler registration on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register Network payload handlers listener.", t);
        }

        try {
            modBus.addListener(ServerConfig::onConfigLoading);
            modBus.addListener(ServerConfig::onConfigReloading);
            LOG.info("[VillagerOverhaul] Registered ServerConfig load/reload listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ServerConfig listeners.", t);
        }

        try {
            VillagerCombatAttributesBootstrap.register(modBus);
            LOG.info("[VillagerOverhaul] Registered VillagerCombatAttributesBootstrap on MOD BUS.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register VillagerCombatAttributesBootstrap listeners.", t);
        }

        try {
            modBus.addListener(this::commonSetup);
            modBus.addListener(this::clientSetup);
            LOG.info("[VillagerOverhaul] Registered common/client setup listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register setup listeners.", t);
        }

        try {
            modBus.addListener(ClientKeybinds::onRegisterKeyMappings);
            LOG.info("[VillagerOverhaul] Registered ClientKeybinds on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ClientKeybinds.", t);
        }

        // Menu->Screen mapping
        try {
            modBus.addListener(this::onRegisterMenuScreens);
            LOG.info("[VillagerOverhaul] Registered onRegisterMenuScreens listener on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register onRegisterMenuScreens listener (continuing).", t);
        }

        // --- RENDER LAYERS + LAYER DEFINITIONS (MOD bus, client-only event types) ---
        try {
            modBus.addListener(ClientRenderEvents::onRegisterLayerDefinitions);
            LOG.info("[VillagerOverhaul] Registered ClientRenderEvents::onRegisterLayerDefinitions on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ClientRenderEvents::onRegisterLayerDefinitions on MOD bus.", t);
        }

        try {
            modBus.addListener(ClientRenderEvents::onAddLayers);
            LOG.info("[VillagerOverhaul] Registered ClientRenderEvents::onAddLayers on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ClientRenderEvents::onAddLayers on MOD bus.", t);
        }

        // --- GAMEPLAY BUS listeners (NeoForge bus) ---
        try {
            ServerEvents.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] Registered ServerEvents on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ServerEvents (continuing).", t);
        }

        try {
            BusyVillagerBlocker.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] Registered BusyVillagerBlocker on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register BusyVillagerBlocker (continuing).", t);
        }

        try {
            ServerCommands.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] Registered ServerCommands on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ServerCommands (continuing).", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOG.info("[VillagerOverhaul] Common setup.");

        try {
            e.enqueueWork(() -> {
                try {
                    CombatBlockDiagnostics.register();
                    LOG.info("[VillagerOverhaul] CombatBlockDiagnostics registered (server-side block logging).");
                } catch (Throwable t) {
                    LOG.error("[VillagerOverhaul] CombatBlockDiagnostics registration failed (continuing).", t);
                }

                try {
                    VillagerManualShieldBlocker.register();
                    LOG.info("[VillagerOverhaul] VillagerManualShieldBlocker registered (manual shield blocking).");
                } catch (Throwable t) {
                    LOG.error("[VillagerOverhaul] VillagerManualShieldBlocker registration failed (continuing).", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to enqueue server-side registrations (continuing).", t);
        }
    }

    private void clientSetup(final FMLClientSetupEvent e) {
        LOG.info("[VillagerOverhaul] Client setup.");

        try {
            ClientUI.registerRuntimeClientEvents();
            LOG.info("[VillagerOverhaul] ClientUI runtime events registered.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] ClientUI registration failed (client features may be limited).", t);
        }

        // Client debug commands for dumping model parts and toggling visibility at runtime.
        try {
            ClientCommands.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] [client] ClientCommands registered (vo_modeldump, vo_partvis).");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] [client] ClientCommands registration failed (continuing).", t);
        }
    }

    private void onRegisterMenuScreens(final RegisterMenuScreensEvent e) {
        try {
            e.register(ModMenus.VILLAGER_INVENTORY.get(), VillagerInventoryScreen::new);
            LOG.info("[VillagerOverhaul] Registered VillagerInventoryScreen via RegisterMenuScreensEvent.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] RegisterMenuScreensEvent registration failed.", t);
        }
    }

    public static Logger LOG() {
        return LOG;
    }
}
