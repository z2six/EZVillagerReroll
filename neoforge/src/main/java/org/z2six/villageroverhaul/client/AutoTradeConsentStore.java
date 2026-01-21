// neoforge\src\main\java\org\z2six\villageroverhaul\client\AutoTradeConsentStore.java
package org.z2six.villageroverhaul.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Client-side persistence for auto-trade confirmations.
 *
 * Stored in the client's config dir so users can delete/edit it easily.
 */
public final class AutoTradeConsentStore {

    private static final String FILE_NAME = "villageroverhaul-autotrade.properties";

    private static final Properties PROPS = new Properties();
    private static boolean LOADED = false;

    public enum Decision {
        ALLOW,
        DENY,
        ASK
    }

    private AutoTradeConsentStore() {}

    public static String keyFor(ItemStack buyA, ItemStack buyB, ItemStack sell) {
        return itemId(buyA) + "|" + itemId(buyB) + "|" + itemId(sell);
    }

    public static Decision getDecision(String key) {
        try {
            ensureLoaded();
            if (key == null || key.isBlank()) return Decision.ASK;
            String v = PROPS.getProperty(key);
            if (v == null) return Decision.ASK;
            v = v.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "allow", "true", "yes" -> Decision.ALLOW;
                case "deny", "false", "no" -> Decision.DENY;
                default -> Decision.ASK;
            };
        } catch (Throwable t) {
            return Decision.ASK;
        }
    }

    public static void rememberDecision(String key, boolean allow) {
        try {
            ensureLoaded();
            if (key == null || key.isBlank()) return;
            PROPS.setProperty(key, allow ? "allow" : "deny");
            save();
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoTradeConsentStore.rememberDecision failed (soft): {}", t.toString());
        }
    }

    private static void ensureLoaded() {
        try {
            if (LOADED) return;
            LOADED = true;

            Path p = path();
            if (p == null) return;
            if (!Files.exists(p)) return;

            try (InputStream in = Files.newInputStream(p)) {
                PROPS.load(in);
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoTradeConsentStore.ensureLoaded failed (soft): {}", t.toString());
        }
    }

    private static void save() {
        try {
            Path p = path();
            if (p == null) return;
            Files.createDirectories(p.getParent());

            try (OutputStream out = Files.newOutputStream(p)) {
                PROPS.store(out, "VillagerOverhaul auto-trade confirmations (delete to reset)");
            }
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] AutoTradeConsentStore.save failed (soft): {}", t.toString());
        }
    }

    private static Path path() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String itemId(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return "";
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id == null ? "" : id.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}

