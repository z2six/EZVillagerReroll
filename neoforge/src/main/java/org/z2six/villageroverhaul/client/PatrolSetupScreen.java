// PatrolSetupScreen.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/client/PatrolSetupScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.PacketPatrolAction;

public final class PatrolSetupScreen extends Screen {

    private final int villagerEntityId;
    private final int waypointCount;

    public PatrolSetupScreen(int villagerEntityId, int waypointCount) {
        super(Component.literal("Patrol Setup"));
        this.villagerEntityId = villagerEntityId;
        this.waypointCount = waypointCount;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        int w = 220;
        int h = 20;

        this.addRenderableWidget(Button.builder(Component.literal("Add waypoint (" + waypointCount + ")"), b -> {
                    try {
                        // Capture the client-observed villager position at click time.
                        Minecraft mc = Minecraft.getInstance();
                        Vec3 pos = null;

                        if (mc != null && mc.level != null) {
                            Entity ent = mc.level.getEntity(villagerEntityId);
                            if (ent != null) pos = ent.position();
                        }

                        if (pos != null) {
                            ClientNetwork.sendToServer(new PacketPatrolAction(
                                    villagerEntityId,
                                    PacketPatrolAction.Action.ADD_WAYPOINT,
                                    true,
                                    pos.x, pos.y, pos.z
                            ));
                        } else {
                            // Fallback: no client entity found, send without pos (server will use server position).
                            ClientNetwork.sendToServer(new PacketPatrolAction(
                                    villagerEntityId,
                                    PacketPatrolAction.Action.ADD_WAYPOINT
                            ));
                        }

                        toast("Waypoint added.", ChatFormatting.YELLOW);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().error("[VillagerOverhaul] Add waypoint failed", t);
                    }
                    close();
                })
                .bounds(cx - w / 2, cy - 30, w, h)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Start patrol"), b -> {
                    try {
                        ClientNetwork.sendToServer(new PacketPatrolAction(villagerEntityId, PacketPatrolAction.Action.FINALIZE));
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().error("[VillagerOverhaul] Finalize patrol failed", t);
                    }
                    Minecraft.getInstance().setScreen(new PatrolRouteTypeScreen(this, villagerEntityId));
                })
                .bounds(cx - w / 2, cy - 5, w, h)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                    try {
                        ClientNetwork.sendToServer(new PacketPatrolAction(villagerEntityId, PacketPatrolAction.Action.CANCEL));
                        toast("Patrol canceled.", ChatFormatting.RED);
                    } catch (Throwable t) {
                        VillagerOverhaul.LOG().error("[VillagerOverhaul] Cancel patrol failed", t);
                    }
                    close();
                })
                .bounds(cx - w / 2, cy + 20, w, h)
                .build());
    }

    private void toast(String msg, ChatFormatting fmt) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg).withStyle(fmt), true);
            }
        } catch (Throwable ignored) {}
    }

    private void close() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.setScreen(null);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
