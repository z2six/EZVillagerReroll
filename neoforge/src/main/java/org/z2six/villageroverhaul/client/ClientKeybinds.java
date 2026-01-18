// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientKeybinds.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class ClientKeybinds {

    private static KeyMapping OPEN_GLOBAL_COMBAT_SETTINGS;

    private ClientKeybinds() {}

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent e) {
        OPEN_GLOBAL_COMBAT_SETTINGS = new KeyMapping(
                "key.ezvr.combat_settings",
                GLFW.GLFW_KEY_K,
                "key.categories.ezvr"
        );
        e.register(OPEN_GLOBAL_COMBAT_SETTINGS);
    }

    public static boolean consumeOpenGlobalCombatSettings() {
        try {
            return OPEN_GLOBAL_COMBAT_SETTINGS != null && OPEN_GLOBAL_COMBAT_SETTINGS.consumeClick();
        } catch (Throwable t) {
            return false;
        }
    }
}
