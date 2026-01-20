// neoforge/src/main/java/org/z2six/villageroverhaul/network/trades/PacketVillagerTradesData.java
package org.z2six.villageroverhaul.network.trades;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> Client snapshot for a villager's current trade offers.
 * We only include the result stacks (icons + tooltips) and the lock mask.
 */
public record PacketVillagerTradesData(
        int villagerEntityId,
        boolean ok,
        long lockMask,
        List<ItemStack> results
) implements CustomPacketPayload {

    public static final Type<PacketVillagerTradesData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_trades_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerTradesData> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PacketVillagerTradesData decode(RegistryFriendlyByteBuf buf) {
                    int id = 0;
                    boolean ok = false;
                    long mask = 0L;
                    List<ItemStack> results = List.of();
                    try {
                        id = buf.readVarInt();
                        ok = buf.readBoolean();
                        mask = buf.readLong();
                        int n = buf.readVarInt();
                        n = Math.max(0, Math.min(64, n));
                        ArrayList<ItemStack> tmp = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            ItemStack s = ItemStack.EMPTY;
                            try { s = ItemStack.STREAM_CODEC.decode(buf); } catch (Throwable ignored) { s = ItemStack.EMPTY; }
                            tmp.add(s == null ? ItemStack.EMPTY : s);
                        }
                        results = tmp;
                    } catch (Throwable ignored) {}
                    return new PacketVillagerTradesData(id, ok, mask, results);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PacketVillagerTradesData msg) {
                    buf.writeVarInt(msg.villagerEntityId());
                    buf.writeBoolean(msg.ok());
                    buf.writeLong(msg.lockMask());

                    List<ItemStack> results = msg.results() == null ? List.of() : msg.results();
                    int n = Math.max(0, Math.min(64, results.size()));
                    buf.writeVarInt(n);
                    for (int i = 0; i < n; i++) {
                        ItemStack s = results.get(i);
                        try { ItemStack.STREAM_CODEC.encode(buf, s == null ? ItemStack.EMPTY : s); } catch (Throwable ignored) {}
                    }
                }
            };

    public static PacketVillagerTradesData missing(int entityId) {
        return new PacketVillagerTradesData(entityId, false, 0L, List.of());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
