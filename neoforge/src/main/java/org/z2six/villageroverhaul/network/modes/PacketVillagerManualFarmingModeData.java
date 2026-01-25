// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerManualFarmingModeData.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketVillagerManualFarmingModeData(int villagerEntityId, boolean enabled) implements CustomPacketPayload {

    public static final Type<PacketVillagerManualFarmingModeData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "manual_farming_mode_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerManualFarmingModeData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerManualFarmingModeData::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketVillagerManualFarmingModeData::enabled,
                    PacketVillagerManualFarmingModeData::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

