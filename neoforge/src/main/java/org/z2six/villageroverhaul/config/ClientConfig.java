// neoforge\src\main\java\org\z2six\villageroverhaul\config\ClientConfig.java
package org.z2six.villageroverhaul.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.z2six.villageroverhaul.VillagerOverhaul;

public final class ClientConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // ----------------------------
    // Reroll button offsets
    // ----------------------------

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_X =
            B.comment("""
                    Client-only UI: X offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by VillagerOverhaul.
                    """).defineInRange("ui.buttonOffsetX", 20, -5000, 5000);

    public static final ModConfigSpec.IntValue BUTTON_OFFSET_Y =
            B.comment("""
                    Client-only UI: Y offset applied to the reroll button relative to the base position.
                    Base position is the vanilla MerchantScreen anchor used by VillagerOverhaul.
                    """).defineInRange("ui.buttonOffsetY", -5, -5000, 5000);

    // ----------------------------
    // Stats/info button offsets
    // ----------------------------

    public static final ModConfigSpec.IntValue STATS_BUTTON_OFFSET_X =
            B.comment("""
                    Client-only UI: X offset applied to the stats/info button relative to its base position.
                    Base position is directly BELOW the reroll button.
                    """).defineInRange("ui.statsButtonOffsetX", 0, -5000, 5000);

    public static final ModConfigSpec.IntValue STATS_BUTTON_OFFSET_Y =
            B.comment("""
                    Client-only UI: Y offset applied to the stats/info button relative to its base position.
                    Base position is directly BELOW the reroll button.
                    """).defineInRange("ui.statsButtonOffsetY", 0, -5000, 5000);

    public static final ModConfigSpec SPEC = B.build();

    public static int buttonOffsetX = 20;
    public static int buttonOffsetY = -5;

    public static int statsButtonOffsetX = 0;
    public static int statsButtonOffsetY = 0;

    public static void bake() {
        try {
            buttonOffsetX = BUTTON_OFFSET_X.get();
            buttonOffsetY = BUTTON_OFFSET_Y.get();

            statsButtonOffsetX = STATS_BUTTON_OFFSET_X.get();
            statsButtonOffsetY = STATS_BUTTON_OFFSET_Y.get();

            VillagerOverhaul.LOG().info(
                    "[VillagerOverhaul] ClientConfig baked: rerollOffset=({},{}), statsOffset=({}, {})",
                    buttonOffsetX, buttonOffsetY,
                    statsButtonOffsetX, statsButtonOffsetY
            );
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] ClientConfig bake error", t);
        }
    }

    private ClientConfig() {}
}
