// neoforge\src\main\java\org\z2six\villageroverhaul\mixin\VillagerAccessor.java
package org.z2six.villageroverhaul.mixin;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Villager.class)
public interface VillagerAccessor {

    @Invoker("updateTrades")
    void ezvr$updateTrades();

    @Invoker("updateSpecialPrices")
    void ezvr$updateSpecialPrices(Player player);
}
