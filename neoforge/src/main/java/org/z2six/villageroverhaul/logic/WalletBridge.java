// neoforge\src\main\java\org\z2six\villageroverhaul\logic\WalletBridge.java
package org.z2six.villageroverhaul.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class WalletBridge {

    private static volatile boolean lookedUp = false;
    private static volatile boolean lcPresent = false;

    private static final List<String> WALLET_HANDLER_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.wallet.WalletHandler",
            "lightmanscurrency.common.wallet.WalletHandler"
    );
    private static final List<String> WALLET_ITEM_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.items.WalletItem",
            "lightmanscurrency.common.items.WalletItem"
    );
    private static final List<String> WALLET_WRAPPER_CANDIDATES = Arrays.asList(
            "io.github.lightman314.lightmanscurrency.common.wallet.WalletDataWrapper",
            "lightmanscurrency.common.wallet.WalletDataWrapper"
    );

    private static Class<?> clsWalletHandler;
    private static Class<?> clsWalletItem;
    private static Class<?> clsWalletDataWrapper;

    private static Method mWalletHandler_get;        // static WalletHandler WalletHandler.get(Entity)
    private static Method mWalletHandler_getWallet;  // ItemStack WalletHandler.getWallet()
    private static Method mWalletHandler_setChanged; // void WalletHandler.setChanged()

    private static Method mWalletItem_isWallet;       // boolean WalletItem.isWallet(ItemStack)
    private static Method mWalletItem_getDataWrapper; // WalletDataWrapper WalletItem.getDataWrapper(ItemStack)

    private static Method mWrapper_getContents; // Container WalletDataWrapper.getContents()
    private static Method mWrapper_setContents; // void WalletDataWrapper.setContents(Container, LivingEntity)

    public static boolean isLCPresent() {
        if (!lookedUp) {
            lcPresent = ModList.get().isLoaded("lightmanscurrency");
            lookedUp = true;
            if (lcPresent) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] Lightman's Currency detected (soft-integration active)");
                bindReflectionHandles();
            }
        }
        return lcPresent;
    }

    public static boolean tryWithdrawFromWallet(ServerPlayer player, ResourceLocation coinId, int count) {
        try {
            if (!isLCPresent()) return false;
            if (coinId == null || count <= 0) return false;

            if (!areBound()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC wallet API not bound; falling back to inventory.");
                return false;
            }

            Item targetItem = BuiltInRegistries.ITEM.get(coinId);
            if (targetItem == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: item '{}' not found in registry.", coinId);
                return false;
            }

            Object handler = mWalletHandler_get.invoke(null, player);
            if (handler == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: WalletHandler.get(player) returned null.");
                return false;
            }

            Object walletStackObj = mWalletHandler_getWallet.invoke(handler);
            if (!(walletStackObj instanceof ItemStack walletStack)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: getWallet() did not return an ItemStack.");
                return false;
            }

            if (walletStack.isEmpty() || !(Boolean) mWalletItem_isWallet.invoke(null, walletStack)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: no valid wallet equipped.");
                return false;
            }

            Object wrapper = mWalletItem_getDataWrapper.invoke(null, walletStack);
            if (wrapper == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: getDataWrapper() returned null.");
                return false;
            }

            Object containerObj = mWrapper_getContents.invoke(wrapper);
            if (!(containerObj instanceof Container container)) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: getContents() did not return a Container.");
                return false;
            }

            int available = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                if (s.getItem() == targetItem) available += s.getCount();
                if (available >= count) break;
            }
            if (available < count) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC withdraw: insufficient wallet coins. Need {}, have {}.", count, available);
                return false;
            }

            int remaining = count;
            for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                if (s.getItem() != targetItem) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
            }

            mWrapper_setContents.invoke(wrapper, container, (LivingEntity) player);
            mWalletHandler_setChanged.invoke(handler);

            VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC wallet: withdrew {} x {}", count, coinId);
            return true;

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] LC wallet withdraw failed (soft): {}", t.toString());
            return false;
        }
    }

    private static boolean areBound() {
        return mWalletHandler_get != null
                && mWalletHandler_getWallet != null
                && mWalletHandler_setChanged != null
                && mWalletItem_isWallet != null
                && mWalletItem_getDataWrapper != null
                && mWrapper_getContents != null
                && mWrapper_setContents != null;
    }

    private static void bindReflectionHandles() {
        try {
            clsWalletHandler = tryLoadAny(WALLET_HANDLER_CANDIDATES);
            clsWalletItem = tryLoadAny(WALLET_ITEM_CANDIDATES);
            clsWalletDataWrapper = tryLoadAny(WALLET_WRAPPER_CANDIDATES);

            if (clsWalletHandler == null || clsWalletItem == null || clsWalletDataWrapper == null) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC wallet classes not found. Candidates tried: {}, {}, {}",
                        WALLET_HANDLER_CANDIDATES, WALLET_ITEM_CANDIDATES, WALLET_WRAPPER_CANDIDATES);
                return;
            }

            mWalletHandler_get = safeGetMethod(clsWalletHandler, "get", net.minecraft.world.entity.Entity.class);
            mWalletHandler_getWallet = safeGetMethod(clsWalletHandler, "getWallet");
            mWalletHandler_setChanged = safeGetMethod(clsWalletHandler, "setChanged");

            mWalletItem_isWallet = safeGetMethod(clsWalletItem, "isWallet", ItemStack.class);
            mWalletItem_getDataWrapper = safeGetMethod(clsWalletItem, "getDataWrapper", ItemStack.class);

            mWrapper_getContents = safeGetMethod(clsWalletDataWrapper, "getContents");
            mWrapper_setContents = safeGetMethod(clsWalletDataWrapper, "setContents",
                    net.minecraft.world.Container.class, net.minecraft.world.entity.LivingEntity.class);

            if (areBound()) {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC wallet API bound successfully via reflection.");
            } else {
                VillagerOverhaul.LOG().debug("[VillagerOverhaul] LC wallet API binding incomplete (methods missing). Will fall back if used.");
            }

        } catch (Throwable t) {
            VillagerOverhaul.LOG().warn("[VillagerOverhaul] bindReflectionHandles failed: {}", t.toString());
        }
    }

    private static Class<?> tryLoadAny(List<String> candidates) {
        for (String name : candidates) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Method safeGetMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private WalletBridge() {}
}
