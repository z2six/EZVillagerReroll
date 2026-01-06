// MainFile: src/main/java/org/z2six/ezvillagerreroll/EZVillagerReroll.java
package org.z2six.ezvillagerreroll;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.z2six.ezvillagerreroll.client.ClientUI;
import org.z2six.ezvillagerreroll.config.ClientConfig;
import org.z2six.ezvillagerreroll.config.ServerConfig;
import org.z2six.ezvillagerreroll.network.Network;

@Mod(EZVillagerReroll.MODID)
public final class EZVillagerReroll {

    public static final String MODID = "ezvillagerreroll";
    private static final Logger LOG = LogUtils.getLogger();

    public EZVillagerReroll(final IEventBus modBus, final ModContainer modContainer) {
        LOG.info("[EZVR] Initializing EZVillagerReroll (modid={})", MODID);

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);

        try {
            modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
            LOG.info("[EZVR] Registered SERVER config");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register SERVER config; continuing with defaults where possible.", t);
        }

        try {
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            LOG.info("[EZVR] Registered CLIENT config");
        } catch (Throwable t) {
            LOG.error("[EZVR] Failed to register CLIENT config; continuing with defaults where possible.", t);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent e) {
        LOG.info("[EZVR] CommonSetup start");
        try {
            try {
                Network.init();
                LOG.info("[EZVR] Network.init() invoked");
            } catch (Throwable t) {
                LOG.warn("[EZVR] Network.init() soft-failed; payload registration is event-driven anyway. {}", t.toString());
            }
        } catch (Throwable t) {
            LOG.error("[EZVR] CommonSetup exception; continuing in safe mode.", t);
        } finally {
            LOG.info("[EZVR] CommonSetup end");
        }
    }

    private void clientSetup(final FMLClientSetupEvent e) {
        LOG.info("[EZVR] ClientSetup start");
        try {
            ClientUI.registerRuntimeClientEvents();
            LOG.info("[EZVR] ClientUI runtime events registered");
        } catch (Throwable t) {
            LOG.error("[EZVR] ClientSetup exception; client features may be limited.", t);
        } finally {
            LOG.info("[EZVR] ClientSetup end");
        }
    }

    public static Logger LOG() {
        return LOG;
    }
}
