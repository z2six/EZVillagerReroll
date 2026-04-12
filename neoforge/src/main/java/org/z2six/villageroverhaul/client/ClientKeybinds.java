// neoforge\src\main\java\org\z2six\villageroverhaul\client\ClientKeybinds.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class ClientKeybinds {

    private static KeyMapping OPEN_GLOBAL_COMBAT_SETTINGS;
    private static KeyMapping OPEN_PLAYER_CHAT_COMMANDS;
    private static KeyMapping OPEN_FARMING_PROFILES;

    private ClientKeybinds() {}

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent e) {
        OPEN_GLOBAL_COMBAT_SETTINGS = new KeyMapping(
                "key.vo.combat_settings",
                GLFW.GLFW_KEY_K,
                "key.categories.vo"
        );
        e.register(OPEN_GLOBAL_COMBAT_SETTINGS);

        // Unbound by default (players can bind it in Controls).
        OPEN_PLAYER_CHAT_COMMANDS = new KeyMapping(
                "key.vo.chat_commands",
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.vo"
        );
        e.register(OPEN_PLAYER_CHAT_COMMANDS);

        OPEN_FARMING_PROFILES = new KeyMapping(
                "key.vo.farming_profiles",
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.vo"
        );
        e.register(OPEN_FARMING_PROFILES);
    }

    public static boolean consumeOpenGlobalCombatSettings() {
        try {
            return OPEN_GLOBAL_COMBAT_SETTINGS != null && OPEN_GLOBAL_COMBAT_SETTINGS.consumeClick();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean consumeOpenPlayerChatCommands() {
        try {
            return OPEN_PLAYER_CHAT_COMMANDS != null && OPEN_PLAYER_CHAT_COMMANDS.consumeClick();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean consumeOpenFarmingProfiles() {
        try {
            return OPEN_FARMING_PROFILES != null && OPEN_FARMING_PROFILES.consumeClick();
        } catch (Throwable t) {
            return false;
        }
    }
}
