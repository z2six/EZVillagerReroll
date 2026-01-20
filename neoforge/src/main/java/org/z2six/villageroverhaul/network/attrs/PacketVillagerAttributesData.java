// neoforge\src\main\java\org\z2six\villageroverhaul\network\attrs\PacketVillagerAttributesData.java
package org.z2six.villageroverhaul.network.attrs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client snapshot of attribute values for a given entityId.
 *
 * ok=false means server couldn't resolve/validate the entity.
 */
public record PacketVillagerAttributesData(
        int villagerEntityId,
        boolean ok,
        List<Entry> entries
) implements CustomPacketPayload {

    public record Entry(ResourceLocation id, double base, double value) {}

    public static final Type<PacketVillagerAttributesData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_attributes_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerAttributesData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerAttributesData decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    boolean ok = false;
                    List<Entry> list = new ArrayList<>();

                    try { id = buf.readVarInt(); } catch (Throwable ignored) {}
                    try { ok = buf.readBoolean(); } catch (Throwable ignored) {}

                    int n = 0;
                    try { n = buf.readVarInt(); } catch (Throwable ignored) { n = 0; }
                    if (n < 0) n = 0;
                    if (n > 512) n = 512;

                    for (int i = 0; i < n; i++) {
                        try {
                            ResourceLocation rl = buf.readResourceLocation();
                            double base = buf.readDouble();
                            double val = buf.readDouble();
                            if (rl != null) list.add(new Entry(rl, base, val));
                        } catch (Throwable ignored) {
                            // stop early if payload is short/corrupt
                            break;
                        }
                    }

                    return new PacketVillagerAttributesData(id, ok, list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerAttributesData d) {
                    buf.writeVarInt(d.villagerEntityId());
                    buf.writeBoolean(d.ok());

                    List<Entry> e = d.entries() == null ? List.of() : d.entries();
                    int n = Math.min(512, Math.max(0, e.size()));
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        Entry it = e.get(i);
                        buf.writeResourceLocation(it.id());
                        buf.writeDouble(it.base());
                        buf.writeDouble(it.value());
                    }
                }
            };

    public static PacketVillagerAttributesData missing(int entityId) {
        return new PacketVillagerAttributesData(entityId, false, List.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

