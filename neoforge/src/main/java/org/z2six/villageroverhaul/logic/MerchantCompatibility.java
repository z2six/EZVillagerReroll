package org.z2six.villageroverhaul.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.mixin.MerchantMenuAccessor;
import org.z2six.villageroverhaul.server.CatalogBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class MerchantCompatibility {

    private static final String GOBLIN_TRADER_ID = "goblintraders:goblin_trader";
    private static final String VEIN_GOBLIN_TRADER_ID = "goblintraders:vein_goblin_trader";

    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private MerchantCompatibility() {}

    public static boolean isGoblinTrader(Entity entity) {
        String id = entityTypeId(entity);
        return GOBLIN_TRADER_ID.equals(id) || VEIN_GOBLIN_TRADER_ID.equals(id);
    }

    public static boolean supportsMerchantModule(Entity entity) {
        if (!(entity instanceof Merchant)) return false;
        return entity instanceof Villager || isGoblinTrader(entity);
    }

    public static boolean supportsTradeLocks(Entity entity) {
        return supportsMerchantModule(entity);
    }

    public static boolean supportsReroll(Entity entity) {
        return supportsMerchantModule(entity);
    }

    public static boolean supportsAutoSearch(Entity entity) {
        return supportsMerchantModule(entity);
    }

    public static boolean supportsRecruit(Entity entity) {
        return supportsMerchantModule(entity);
    }

    public static boolean supportsInfoPanel(Entity entity) {
        return supportsMerchantModule(entity);
    }

    public static boolean supportsInventory(Entity entity) {
        return entity instanceof Villager;
    }

    public static boolean supportsCommands(Entity entity) {
        return entity instanceof Villager;
    }

    public static boolean usesMerchantOnlyInfo(Entity entity) {
        return isGoblinTrader(entity);
    }

    public static MerchantOffers getOffers(Entity entity) {
        try {
            return entity instanceof Merchant merchant ? merchant.getOffers() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int offerCount(Entity entity) {
        try {
            MerchantOffers offers = getOffers(entity);
            return offers == null ? 0 : Math.max(0, offers.size());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int getMerchantXp(Entity entity) {
        try {
            return entity instanceof Merchant merchant ? Math.max(0, merchant.getVillagerXp()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean showProgressBar(Entity entity) {
        try {
            return entity instanceof Merchant merchant && merchant.showProgressBar();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean canRestock(Entity entity) {
        try {
            return entity instanceof Merchant merchant && merchant.canRestock();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean syncOffersToPlayer(ServerPlayer player, Entity entity, MerchantMenu menu) {
        try {
            if (player == null || entity == null || menu == null) return false;
            if (!(entity instanceof Merchant)) return false;
            player.sendMerchantOffers(
                    menu.containerId,
                    getOffers(entity),
                    0,
                    getMerchantXp(entity),
                    showProgressBar(entity),
                    canRestock(entity)
            );
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] MerchantCompatibility.syncOffersToPlayer failed: {}", t.toString());
            return false;
        }
    }

    public static boolean syncOffersToActiveTrader(ServerPlayer player, Entity entity) {
        try {
            if (player == null || entity == null) return false;
            if (!(player.containerMenu instanceof MerchantMenu menu)) return false;
            Object trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            if (trader != entity) return false;
            return syncOffersToPlayer(player, entity, menu);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean rebuildOffers(Entity entity, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (entity instanceof Villager villager) {
                return syncToPlayer
                        ? TradeUtil.rebuildOffers(villager, player)
                        : TradeUtil.rebuildOffersInternal(villager, player, false);
            }
            if (!isGoblinTrader(entity)) return false;
            return rebuildGoblinOffers(entity, player, syncToPlayer);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] MerchantCompatibility.rebuildOffers failed", t);
            return false;
        }
    }

    public static List<ItemStack> buildCatalog(Entity entity) {
        try {
            if (entity instanceof Villager villager) {
                return CatalogBuilder.buildCatalog(villager);
            }
            if (!isGoblinTrader(entity)) return List.of();
            return buildGoblinCatalog(entity);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().error("[VillagerOverhaul] MerchantCompatibility.buildCatalog failed", t);
            return List.of();
        }
    }

    private static boolean rebuildGoblinOffers(Entity entity, ServerPlayer player, boolean syncToPlayer) {
        try {
            if (!(entity instanceof Merchant merchant)) return false;
            Field offersField = findField(entity.getClass(), "offers");
            if (offersField == null) return false;
            offersField.set(entity, null);
            MerchantOffers rebuilt = merchant.getOffers();
            if (rebuilt == null) return false;

            if (syncToPlayer && player != null) {
                syncOffersToActiveTrader(player, entity);
            }
            return true;
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] rebuildGoblinOffers failed: {}", t.toString());
            return false;
        }
    }

    private static List<ItemStack> buildGoblinCatalog(Entity entity) {
        try {
            Map<String, ItemStack> unique = new LinkedHashMap<>();

            MerchantOffers current = getOffers(entity);
            if (current != null) {
                for (MerchantOffer offer : current) {
                    if (offer == null) continue;
                    ItemStack result = offer.getResult();
                    if (result == null || result.isEmpty()) continue;
                    unique.putIfAbsent(CatalogBuilder.keyOf(result), result.copy());
                }
            }

            Object tradeManager = invokeStatic(findClass("com.mrcrayfish.goblintraders.trades.TradeManager"), "instance");
            if (tradeManager == null) return new ArrayList<>(unique.values());

            Method getTrades = findMethod(tradeManager.getClass(), "getTrades", entity.getType().getClass());
            Object entityTrades = getTrades == null ? null : getTrades.invoke(tradeManager, entity.getType());
            if (entityTrades == null) return new ArrayList<>(unique.values());

            Method mapMethod = findMethod(entityTrades.getClass(), "map");
            Object mapObj = mapMethod == null ? null : mapMethod.invoke(entityTrades);
            if (!(mapObj instanceof Map<?, ?> tradeMap)) return new ArrayList<>(unique.values());

            Method createOffer = null;
            Class<?> goblinBaseClass = findClass("com.mrcrayfish.goblintraders.entity.AbstractGoblinEntity");
            if (goblinBaseClass == null || !goblinBaseClass.isInstance(entity)) {
                return new ArrayList<>(unique.values());
            }

            int index = 0;
            for (Object listObj : tradeMap.values()) {
                if (!(listObj instanceof List<?> trades)) continue;
                for (Object trade : trades) {
                    if (trade == null) continue;
                    if (createOffer == null || createOffer.getDeclaringClass() != trade.getClass()) {
                        createOffer = findMethod(trade.getClass(), "createVanillaOffer", goblinBaseClass, RandomSource.class);
                    }
                    if (createOffer == null) continue;
                    MerchantOffer offer;
                    try {
                        RandomSource random = RandomSource.create(0x5EEDL + index++);
                        Object out = createOffer.invoke(trade, entity, random);
                        if (!(out instanceof MerchantOffer mo)) continue;
                        offer = mo;
                    } catch (Throwable ignored) {
                        continue;
                    }
                    ItemStack result = offer.getResult();
                    if (result == null || result.isEmpty()) continue;
                    unique.putIfAbsent(CatalogBuilder.keyOf(result), result.copy());
                }
            }

            return new ArrayList<>(unique.values());
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] buildGoblinCatalog failed: {}", t.toString());
            return List.of();
        }
    }

    public static String entityTypeId(Entity entity) {
        try {
            if (entity == null || entity.getType() == null) return "";
            ResourceLocation key = net.minecraft.world.entity.EntityType.getKey(entity.getType());
            return key == null ? "" : key.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static Entity resolveSupportedMerchantEntity(ServerPlayer player, int entityId) {
        try {
            if (player == null) return null;
            ServerLevel level = player.serverLevel();
            Entity entity = level == null ? null : level.getEntity(entityId);
            if (supportsMerchantModule(entity)) return entity;

            if (player.containerMenu instanceof MerchantMenu menu) {
                Object trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
                if (trader instanceof Entity entityTrader && supportsMerchantModule(entityTrader)) {
                    if (entityId <= 0 || entityTrader.getId() == entityId) return entityTrader;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Merchant resolveActiveMerchant(ServerPlayer player) {
        try {
            if (!(player.containerMenu instanceof MerchantMenu menu)) return null;
            Object trader = ((MerchantMenuAccessor) menu).ezvr$getTrader();
            return trader instanceof Merchant merchant ? merchant : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeStatic(Class<?> owner, String methodName) throws Exception {
        if (owner == null) return null;
        Method method = findMethod(owner, methodName);
        return method == null ? null : method.invoke(null);
    }

    private static Class<?> findClass(String name) {
        try {
            Class<?> cached = CLASS_CACHE.get(name);
            if (cached != null) return cached;
            Class<?> resolved = Class.forName(name);
            CLASS_CACHE.putIfAbsent(name, resolved);
            return resolved;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            String key = owner.getName() + "#" + name + "#" + paramKey(params);
            Method cached = METHOD_CACHE.get(key);
            if (cached != null) return cached;

            Class<?> cursor = owner;
            while (cursor != null && cursor != Object.class) {
                try {
                    Method method = cursor.getDeclaredMethod(name, params);
                    method.setAccessible(true);
                    METHOD_CACHE.putIfAbsent(key, method);
                    return method;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }

            try {
                Method method = owner.getMethod(name, params);
                METHOD_CACHE.putIfAbsent(key, method);
                return method;
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            String key = owner.getName() + "#" + name;
            Field cached = FIELD_CACHE.get(key);
            if (cached != null) return cached;

            Class<?> cursor = owner;
            while (cursor != null && cursor != Object.class) {
                try {
                    Field field = cursor.getDeclaredField(name);
                    field.setAccessible(true);
                    FIELD_CACHE.putIfAbsent(key, field);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String paramKey(Class<?>... params) {
        if (params == null || params.length == 0) return "none";
        StringBuilder out = new StringBuilder();
        for (Class<?> param : params) {
            if (out.length() > 0) out.append(',');
            out.append(param == null ? "null" : param.getName());
        }
        return out.toString();
    }
}
