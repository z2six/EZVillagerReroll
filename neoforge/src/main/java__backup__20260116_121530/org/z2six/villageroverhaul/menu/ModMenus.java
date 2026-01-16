// ModMenus.java
// MainFile: neoforge/src/main/java/org/z2six/villageroverhaul/menu/ModMenus.java
package org.z2six.villageroverhaul.menu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.z2six.villageroverhaul.Constants;

public final class ModMenus {

    private ModMenus() {}

    // NeoForge DeferredRegister for menu types
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Constants.MOD_ID);

    // Villager inventory menu type (buf-aware factory)
    public static final DeferredHolder<MenuType<?>, MenuType<VillagerInventoryMenu>> VILLAGER_INVENTORY =
            MENUS.register("villager_inventory",
                    () -> IMenuTypeExtension.create(VillagerInventoryMenu::new));
}
