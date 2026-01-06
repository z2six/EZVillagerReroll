// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/EZVillagerReroll.java
package org.z2six.ezvillagerreroll;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.z2six.ezvillagerreroll.client.ClientUI;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.network.Network;
import org.z2six.ezvillagerreroll.server.ServerEvents;

@Mod(Constants.MOD_ID)
public final class EZVillagerReroll {

    private static final Logger LOG = LogUtils.getLogger();

    public EZVillagerReroll(final IEventBus modBus, final ModContainer modContainer) {
        LOG.info("[EZVR] Initializing EZVillagerReroll (modid={})", Constants.MOD_ID);

        // --- Register configs (these are MOD lifecycle, not gameplay) ---
        try {
            modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
            LOG.info("[EZVR] Registered SERVER config spec.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register SERVER config spec (continuing).", t);
        }

        try {
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            LOG.info("[EZVR] Registered CLIENT config spec.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register CLIENT config spec (continuing).", t);
        }

        // --- MOD BUS listeners (IModBusEvent + lifecycle) ---
        // IMPORTANT: do NOT register these on NeoForge.EVENT_BUS
        try {
            modBus.addListener(Network::onRegisterPayloadHandlers);
            LOG.info("[EZVR] Registered Network payload handler registration on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register Network payload handlers listener.", t);
        }

        try {
            modBus.addListener(ServerConfig::onConfigLoading);
            modBus.addListener(ServerConfig::onConfigReloading);
            LOG.info("[EZVR] Registered ServerConfig load/reload listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register ServerConfig listeners.", t);
        }

        try {
            modBus.addListener(this::commonSetup);
            modBus.addListener(this::clientSetup);
            LOG.info("[EZVR] Registered common/client setup listeners on MOD bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register setup listeners.", t);
        }

        // --- GAMEPLAY BUS listeners (NeoForge bus) ---
        // ServerEvents are gameplay/runtime events => NeoForge.EVENT_BUS is correct.
        try {
            ServerEvents.register(NeoForge.EVENT_BUS);
            LOG.info("[EZVR] Registered ServerEvents on NeoForge EVENT bus.");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register ServerEvents (continuing).", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOG.info("[EZVR] Common setup.");
        // Nothing required here for payload registration anymore (that happens in RegisterPayloadHandlersEvent).
        // Keep this method for future safe-init work.
    }

    private void clientSetup(final FMLClientSetupEvent e) {
        LOG.info("[EZVR] Client setup.");
        try {
            ClientUI.registerRuntimeClientEvents();
            LOG.info("[EZVR] ClientUI runtime events registered.");
        } catch (Throwable t) {
            LOG.error("[EZVR] ClientUI registration failed (client features may be limited).", t);
        }
    }

    public static Logger LOG() {
        return LOG;
    }
}
