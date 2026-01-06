// MainFile: src/main/java/org/z2six/ezvillagerreroll/mixin/AbstractVillagerAccessor.java
package org.z2six.ezvillagerreroll.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractVillager.class)
public interface AbstractVillagerAccessor {

    @Accessor("offers")
    void ezvr$setOffers(MerchantOffers offers);
}
