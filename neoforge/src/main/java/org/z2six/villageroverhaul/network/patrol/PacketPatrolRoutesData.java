// neoforge/src/main/java/org/z2six/villageroverhaul/network/patrol/PacketPatrolRoutesData.java
package org.z2six.villageroverhaul.network.patrol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> Client: list of saved patrol routes for a villager.
 */
public record PacketPatrolRoutesData(
        int villagerEntityId,
        List<RouteEntry> routes
) implements CustomPacketPayload {

    public record RouteEntry(
            UUID routeId,
            String name,
            String typeId,
            int waypointCount
    ) {}

    public static final Type<PacketPatrolRoutesData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "patrol_routes_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketPatrolRoutesData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, msg) -> {
                        buf.writeVarInt(msg.villagerEntityId());
                        List<RouteEntry> list = msg.routes() == null ? List.of() : msg.routes();
                        int n = Math.max(0, Math.min(64, list.size()));
                        buf.writeVarInt(n);
                        for (int i = 0; i < n; i++) {
                            RouteEntry e = list.get(i);
                            UUID rid = (e == null || e.routeId == null) ? new UUID(0L, 0L) : e.routeId;
                            buf.writeLong(rid.getMostSignificantBits());
                            buf.writeLong(rid.getLeastSignificantBits());
                            buf.writeUtf(e == null || e.name == null ? "" : e.name, 64);
                            buf.writeUtf(e == null || e.typeId == null ? "" : e.typeId, 32);
                            buf.writeVarInt(Math.max(0, e == null ? 0 : e.waypointCount));
                        }
                    },
                    (buf) -> {
                        int id = buf.readVarInt();
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(64, n));
                        ArrayList<RouteEntry> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            UUID rid = new UUID(buf.readLong(), buf.readLong());
                            String name = buf.readUtf(64);
                            String type = buf.readUtf(32);
                            int wc = Math.max(0, buf.readVarInt());
                            list.add(new RouteEntry(rid, name, type, wc));
                        }
                        return new PacketPatrolRoutesData(id, list);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
