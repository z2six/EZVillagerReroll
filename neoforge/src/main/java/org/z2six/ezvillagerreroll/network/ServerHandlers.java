// MainFile: src/main/java/org/z2six/ezvillagerreroll/network/ServerHandlers.java
package org.z2six.ezvillagerreroll.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.logic.RerollExecutor;

public final class ServerHandlers {

    private ServerHandlers() {}

    public static void handleReroll(PacketRequestReroll msg, IPayloadContext ctx) {
        try {
            var p = ctx.player();
            if (!(p instanceof ServerPlayer sp)) {
                EZVillagerReroll.LOG().warn("[EZVR] Reroll request without ServerPlayer context");
                return;
            }
            RerollExecutor.tryReroll(sp);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] handleReroll exception", t);
        }
    }
}
