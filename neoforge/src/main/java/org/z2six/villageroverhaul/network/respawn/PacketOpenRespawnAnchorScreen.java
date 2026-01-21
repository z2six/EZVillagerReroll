// neoforge/src/main/java/org/z2six/villageroverhaul/network/respawn/PacketOpenRespawnAnchorScreen.java
package org.z2six.villageroverhaul.network.respawn;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> Client: open respawn anchor UI with a list of available snapshots for this player.
 */
public record PacketOpenRespawnAnchorScreen(
        int anchorX, int anchorY, int anchorZ,
        List<Entry> entries
) implements CustomPacketPayload {

    public record Entry(
            UUID respawnId,
            String nameJson,
            String professionId,
            int respawnCost,
            int deaths
    ) {}

    public static final Type<PacketOpenRespawnAnchorScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "respawn_open_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenRespawnAnchorScreen> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketOpenRespawnAnchorScreen decode(RegistryFriendlyByteBuf buf) {
                    int x = 0, y = 0, z = 0;
                    List<Entry> list = List.of();
                    try {
                        x = buf.readInt();
                        y = buf.readInt();
                        z = buf.readInt();
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(256, n));
                        ArrayList<Entry> tmp = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            UUID rid = readUuid(buf);
                            String name = safeStr(buf.readUtf(2048));
                            String prof = safeStr(buf.readUtf(256));
                            int cost = Math.max(0, buf.readVarInt());
                            int deaths = Math.max(0, buf.readVarInt());
                            if (rid != null) tmp.add(new Entry(rid, name, prof, cost, deaths));
                        }
                        list = tmp;
                    } catch (Throwable ignored) {}
                    return new PacketOpenRespawnAnchorScreen(x, y, z, list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketOpenRespawnAnchorScreen msg) {
                    buf.writeInt(msg.anchorX());
                    buf.writeInt(msg.anchorY());
                    buf.writeInt(msg.anchorZ());
                    List<Entry> raw = msg.entries() == null ? List.of() : msg.entries();
                    ArrayList<Entry> list = new ArrayList<>();
                    for (Entry e : raw) {
                        if (e == null || e.respawnId() == null) continue;
                        list.add(e);
                        if (list.size() >= 256) break;
                    }

                    int n = Math.max(0, list.size());
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        Entry e = list.get(i);
                        writeUuid(buf, e.respawnId());
                        buf.writeUtf(safeStr(e.nameJson()), 2048);
                        buf.writeUtf(safeStr(e.professionId()), 256);
                        buf.writeVarInt(Math.max(0, e.respawnCost()));
                        buf.writeVarInt(Math.max(0, e.deaths()));
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static UUID readUuid(RegistryFriendlyByteBuf buf) {
        try {
            long msb = buf.readLong();
            long lsb = buf.readLong();
            return new UUID(msb, lsb);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeUuid(RegistryFriendlyByteBuf buf, UUID uuid) {
        try {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        } catch (Throwable ignored) {}
    }
}
