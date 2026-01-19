// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerEatTest.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerEatTest(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerEatTest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_eat_test"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerEatTest> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerEatTest::villagerEntityId,
                    PacketVillagerEatTest::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

