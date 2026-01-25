// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerManualFarmingModeCommand.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.Constants;

public record PacketVillagerManualFarmingModeCommand(int villagerEntityId, boolean enabled) implements CustomPacketPayload {

    public static final Type<PacketVillagerManualFarmingModeCommand> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "manual_farming_mode_cmd"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerManualFarmingModeCommand> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerManualFarmingModeCommand::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketVillagerManualFarmingModeCommand::enabled,
                    PacketVillagerManualFarmingModeCommand::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

