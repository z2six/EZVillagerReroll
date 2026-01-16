// neoforge\src\main\java\org\z2six\villageroverhaul\network\patrol\PacketPatrolAction.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketPatrolAction(
        int villagerEntityId,
        Action action,
        boolean hasPos,
        double x,
        double y,
        double z
) implements CustomPacketPayload {

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

    public PacketPatrolAction(int villagerEntityId, Action action) {
        this(villagerEntityId, action, false, 0.0, 0.0, 0.0);
    }

    public static final Type<PacketPatrolAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_action"));

    /**
     * Encoding:
     * - villagerEntityId (varint)
     * - actionId (varint)
     * - hasPos (bool)
     * - if hasPos: x,y,z (double,double,double)
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolAction> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());

                        int actionId = (msg.action() == null) ? Action.CANCEL.id : msg.action().id;
                        buf.writeVarInt(actionId);

                        buf.writeBoolean(msg.hasPos());

                        if (msg.hasPos()) {
                            buf.writeDouble(msg.x());
                            buf.writeDouble(msg.y());
                            buf.writeDouble(msg.z());
                        }
                    },
                    (buf) -> {
                        int id = buf.readVarInt();
                        int actionId = buf.readVarInt();
                        Action act = Action.fromId(actionId);

                        boolean hasPos = buf.readBoolean();
                        double x = 0.0, y = 0.0, z = 0.0;

                        if (hasPos) {
                            x = buf.readDouble();
                            y = buf.readDouble();
                            z = buf.readDouble();
                        }

                        return new PacketPatrolAction(id, act, hasPos, x, y, z);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
