// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketOpenBusyScreen.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.z2six.villageroverhaul.Constants;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.ArrayList;
import java.util.List;

public record PacketOpenBusyScreen(
        int villagerEntityId,
        List<ItemStack> requested,
        boolean canCancel,
        List<Long> requestedValueV
) implements CustomPacketPayload {

    public static final Type<PacketOpenBusyScreen> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_busy_screen"));

    /**
     * Backwards-friendly alias for call-sites that used "targets()".
     */
    public List<ItemStack> targets() {
        return requested();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketOpenBusyScreen> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenBusyScreen decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                boolean canCancel = false;
                try { canCancel = buf.readBoolean(); } catch (Throwable ignored) { canCancel = false; }
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > 512) n = 512;

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }

                List<Long> vv = List.of();
                try {
                    int vn = buf.readVarInt();
                    if (vn < 0) vn = 0;
                    if (vn > 512) vn = 512;
                    ArrayList<Long> tmp = new ArrayList<>(vn);
                    for (int i = 0; i < vn; i++) tmp.add(Math.max(0L, buf.readLong()));
                    vv = tmp;
                } catch (Throwable ignored) {}

                return new PacketOpenBusyScreen(id, list, canCancel, vv);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketOpenBusyScreen decode failed", t);
                return new PacketOpenBusyScreen(-1, List.of(), false, List.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketOpenBusyScreen msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                buf.writeBoolean(msg.canCancel());
                List<ItemStack> list = msg.requested() == null ? List.of() : msg.requested();
                int n = Math.min(512, list.size());
                buf.writeVarInt(n);

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }

                // Appended fields (backwards-friendly):
                List<Long> vv = msg.requestedValueV() == null ? List.of() : msg.requestedValueV();
                int vn = Math.min(512, vv.size());
                buf.writeVarInt(vn);
                for (int i = 0; i < vn; i++) {
                    Long v = vv.get(i);
                    buf.writeLong(v == null ? 0L : Math.max(0L, v));
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketOpenBusyScreen encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
