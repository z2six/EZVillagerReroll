// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/VillagerOverhaul.java
package org.z2six.villageroverhaul;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.villageroverhaul.client.ClientUI;
import org.z2six.villageroverhaul.config.ClientConfig;
import org.z2six.villageroverhaul.config.ServerConfig;
import org.z2six.villageroverhaul.network.Network;
import org.z2six.villageroverhaul.server.BusyVillagerBlocker;
import org.z2six.villageroverhaul.server.ServerEvents;
import org.z2six.villageroverhaul.server.VillagerCombatAttributesBootstrap;

@Mod(Constants.MOD_ID)
public final class VillagerOverhaul {

    private static final Logger LOG = LogUtils.getLogger();

    public VillagerOverhaul(final IEventBus modBus, final ModContainer modContainer) {
        LOG.info("[VillagerOverhaul] Initializing VillagerOverhaul (modid={})", Constants.MOD_ID);

        // --- Register configs (these are MOD lifecycle, not gameplay) ---
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

        // --- GAMEPLAY BUS listeners (NeoForge bus) ---
        // IMPORTANT: gameplay/runtime events => NeoForge.EVENT_BUS
        try {
            // Tick + server start/stop (persistence)
            ServerEvents.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] Registered ServerEvents on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register ServerEvents (continuing).", t);
        }

        try {
            // RMB interception for busy villagers (this is the logic you said got "destroyed" because it wasn't wired)
            BusyVillagerBlocker.register(NeoForge.EVENT_BUS);
            LOG.info("[VillagerOverhaul] Registered BusyVillagerBlocker on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] Failed to register BusyVillagerBlocker (continuing).", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOG.info("[VillagerOverhaul] Common setup.");
        // Nothing required here for payload registration anymore (that happens in RegisterPayloadHandlersEvent).
        // Keep this method for future safe-init work.
    }

    private void clientSetup(final FMLClientSetupEvent e) {
        LOG.info("[VillagerOverhaul] Client setup.");
        try {
            ClientUI.registerRuntimeClientEvents();
            LOG.info("[VillagerOverhaul] ClientUI runtime events registered.");
        } catch (Throwable t) {
            LOG.error("[VillagerOverhaul] ClientUI registration failed (client features may be limited).", t);
        }
    }

    public static Logger LOG() {
        return LOG;
    }
}
