// neoforge\src\main\java\org\z2six\villageroverhaul\network\PacketOpenVillagerInventory.java
package org.z2six.villageroverhaul.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketOpenVillagerInventory(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketOpenVillagerInventory> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_villager_inventory"));

    public static final StreamCodec<FriendlyByteBuf, PacketOpenVillagerInventory> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PacketOpenVillagerInventory decode(FriendlyByteBuf buf) {
            int id = 0;
            try { id = buf.readVarInt(); } catch (Throwable ignored) {}
            return new PacketOpenVillagerInventory(id);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PacketOpenVillagerInventory p) {
            buf.writeVarInt(p.villagerEntityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
