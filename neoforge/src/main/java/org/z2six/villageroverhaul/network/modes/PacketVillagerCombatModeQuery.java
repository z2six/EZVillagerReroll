// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketVillagerCombatModeQuery.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketVillagerCombatModeQuery(int villagerEntityId) implements CustomPacketPayload {

    public static final Type<PacketVillagerCombatModeQuery> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "villager_combat_mode_q"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketVillagerCombatModeQuery> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketVillagerCombatModeQuery::villagerEntityId,
                    PacketVillagerCombatModeQuery::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
