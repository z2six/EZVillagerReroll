// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/config/ClientConfig.java
package org.z2six.ezvillagerreroll.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.ezvillagerreroll.EZVillagerReroll;

public final class ClientConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_X =
            B.comment("""
                    Client-only UI: X offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by EZVR.
                    """).defineInRange("ui.buttonOffsetX", 0, -5000, 5000);

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_Y =
            B.comment("""
                    Client-only UI: Y offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by EZVR.
                    """).defineInRange("ui.buttonOffsetY", 0, -5000, 5000);

    public static final ModConfigSpec SPEC = B.build();

    public static int buttonOffsetX = 0;
    public static int buttonOffsetY = 0;

    public static void bake() {
        try {
            buttonOffsetX = BUTTON_OFFSET_X.get();
            buttonOffsetY = BUTTON_OFFSET_Y.get();
            EZVillagerReroll.LOG().info("[EZVR] ClientConfig baked: offsetX={}, offsetY={}", buttonOffsetX, buttonOffsetY);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] ClientConfig bake error", t);
        }
    }

    private ClientConfig() {}
}
