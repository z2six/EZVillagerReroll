// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerManualFarmingModeQuery.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketVillagerManualFarmingModeQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerManualFarmingModeQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "manual_farming_mode_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerManualFarmingModeQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerManualFarmingModeQuery::villagerEntityId,
                    PacketVillagerManualFarmingModeQuery::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

