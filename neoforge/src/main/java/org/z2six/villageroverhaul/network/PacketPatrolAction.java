// PacketPatrolAction.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketPatrolAction.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolAction(int villagerEntityId, Action action) implements CustomPacketPayload {

    public enum Action {
        ADD_WAYPOINT(1),
        CANCEL(2),
        FINALIZE(3);

        public final int id;
        Action(int id) { this.id = id; }

        public static Action fromId(int id) {
            for (Action a : values()) if (a.id == id) return a;
            return CANCEL;
        }
    }

    public static final Type<PacketPatrolAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolAction> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketPatrolAction::villagerEntityId,
                    ByteBufCodecs.VAR_INT, p -> p.action == null ? Action.CANCEL.id : p.action.id,
                    (id, actionId) -> new PacketPatrolAction(id, Action.fromId(actionId))
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
