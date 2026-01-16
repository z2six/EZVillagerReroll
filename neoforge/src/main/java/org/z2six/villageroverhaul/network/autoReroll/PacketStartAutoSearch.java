// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/network/PacketStartAutoSearch.java
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

public record PacketStartAutoSearch(int villagerEntityId, List<ItemStack> requested) implements CustomPacketPayload {

    public static final Type<PacketStartAutoSearch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "start_auto_search"));

    /**
     * Backwards-friendly alias for call-sites that used "targets()".
     */
    public List<ItemStack> targets() {
        return requested();
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketStartAutoSearch> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketStartAutoSearch decode(RegistryFriendlyByteBuf buf) {
            try {
                int id = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0) n = 0;
                if (n > 512) n = 512;

                List<ItemStack> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    // RegistryFriendlyByteBuf supports ItemStack codec.
                    ItemStack s = ItemStack.STREAM_CODEC.decode(buf);
                    list.add(s == null ? ItemStack.EMPTY : s);
                }
                return new PacketStartAutoSearch(id, list);
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketStartAutoSearch decode failed", t);
                return new PacketStartAutoSearch(-1, List.of());
            }
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, PacketStartAutoSearch msg) {
            try {
                buf.writeVarInt(msg.villagerEntityId());
                List<ItemStack> list = msg.requested() == null ? List.of() : msg.requested();

                int n = Math.min(512, list.size());
                buf.writeVarInt(n);

                for (int i = 0; i < n; i++) {
                    ItemStack s = list.get(i);
                    if (s == null) s = ItemStack.EMPTY;
                    ItemStack.STREAM_CODEC.encode(buf, s);
                }
            } catch (Throwable t) {
                VillagerOverhaul.LOG().error("[VillagerOverhaul] PacketStartAutoSearch encode failed", t);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
