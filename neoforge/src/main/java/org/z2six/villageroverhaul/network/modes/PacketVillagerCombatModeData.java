// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerCombatModeData.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerCombatModeData(int villagerEntityId, String combatModeId) implements CustomPacketPayload {

    public static final Type<PacketVillagerCombatModeData> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_combat_mode_d"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerCombatModeData> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerCombatModeData::villagerEntityId,
                    ByteBufCodecs.STRING_UTF8, PacketVillagerCombatModeData::combatModeId,
                    PacketVillagerCombatModeData::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
