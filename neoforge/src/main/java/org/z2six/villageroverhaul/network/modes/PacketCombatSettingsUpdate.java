// neoforge\src\main\java\org\z2six\villageroverhaul\network\modes\PacketCombatSettingsUpdate.java
package org.z2six.villageroverhaul.network.modes;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PacketCombatSettingsUpdate(int villagerEntityId, boolean global, CompoundTag settings) implements CustomPacketPayload {

    public static final Type<PacketCombatSettingsUpdate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("villageroverhaul", "combat_settings_u"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketCombatSettingsUpdate> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PacketCombatSettingsUpdate::villagerEntityId,
                    ByteBufCodecs.BOOL, PacketCombatSettingsUpdate::global,
                    ByteBufCodecs.COMPOUND_TAG, PacketCombatSettingsUpdate::settings,
                    PacketCombatSettingsUpdate::new
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
