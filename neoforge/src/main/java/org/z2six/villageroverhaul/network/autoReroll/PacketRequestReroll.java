// neoforge\src\main\java\org\z2six\villageroverhaul\network\autoReroll\PacketRequestReroll.java
package org.z2six.villageroverhaul.network.autoReroll;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketRequestReroll() implements CustomPacketPayload {

    public static final Type<PacketRequestReroll> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "req_reroll"));

    public static final StreamCodec<FriendlyByteBuf, PacketRequestReroll> STREAM_CODEC =
            StreamCodec.unit(new PacketRequestReroll());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
