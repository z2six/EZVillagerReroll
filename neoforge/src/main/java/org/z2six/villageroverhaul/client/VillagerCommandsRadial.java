package org.z2six.villageroverhaul.client;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.network.ClientSyncedConfig;
import org.z2six.villageroverhaul.network.customcommands.PacketCcBeginTeaching;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCombatCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerManualFarmingModeCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerReleaseCommand;
import org.z2six.villageroverhaul.network.modes.PacketVillagerUiPause;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

final class VillagerCommandsRadial {

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final long RADIAL_PAUSE_KEEPALIVE_MS = 600L;

    private static int activePauseVillagerEntityId = -1;
    private static long activePauseSentAtMs = 0L;

    private VillagerCommandsRadial() {}

    static boolean open(Screen parent, int villagerEntityId) {
        try {
            if (villagerEntityId <= 0) return false;
            if (!ClientUI.canUseControlsForVillager(villagerEntityId)) return false;
            beginRadialPause(villagerEntityId);
            sendUiPause(villagerEntityId, true);
            boolean opened = openEzActionsRadial(parent, villagerEntityId);
            if (!opened) {
                endRadialPause(villagerEntityId, false);
            }
            return opened;
        } catch (Throwable t) {
            endRadialPause(villagerEntityId, false);
            VillagerOverhaul.LOG().error("[VillagerOverhaul] Failed to open villager commands radial", t);
            showFailureMessage();
            return false;
        }
    }

    static boolean isTakingOverUiPauseFor(int villagerEntityId) {
        return villagerEntityId > 0 && villagerEntityId == activePauseVillagerEntityId;
    }

    static void tickUiPauseKeepalive() {
        try {
            int villagerEntityId = activePauseVillagerEntityId;
            if (villagerEntityId <= 0) return;

            if (!isTemporaryRadialOpen()) {
                endRadialPause(villagerEntityId, false);
                return;
            }

            long now = System.currentTimeMillis();
            if (now - activePauseSentAtMs >= RADIAL_PAUSE_KEEPALIVE_MS) {
                sendUiPause(villagerEntityId, true);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean openEzActionsRadial(Screen parent, int villagerEntityId) throws Exception {
        Class<?> iconSpecClass = Class.forName("org.z2six.ezactions.data.icon.IconSpec");
        Class<?> actionInterface = Class.forName("org.z2six.ezactions.data.click.IClickAction");
        Class<?> actionTypeClass = Class.forName("org.z2six.ezactions.data.click.ClickActionType");
        Class<?> menuItemClass = Class.forName("org.z2six.ezactions.data.menu.MenuItem");
        Class<?> radialMenuClass = Class.forName("org.z2six.ezactions.data.menu.RadialMenu");
        Class<?> tempStyleClass = Class.forName("org.z2six.ezactions.data.menu.RadialMenu$TemporaryStyle");

        Object commandType = enumValue(actionTypeClass, "COMMAND");
        Method iconItem = iconSpecClass.getMethod("item", String.class);
        Constructor<?> menuItemCtor = menuItemClass.getConstructor(
                String.class,
                Component.class,
                Component.class,
                iconSpecClass,
                actionInterface,
                List.class,
                boolean.class,
                boolean.class,
                boolean.class
        );
        Method withActive = tryGetMethod(menuItemClass, "withActive", boolean.class);

        boolean manualFarming = ClientUI.isKnownManualFarmingEnabled(villagerEntityId);
        String movementMode = normalizeMovementMode(ClientUI.getKnownMovementModeId(villagerEntityId));
        String combatMode = normalizeCombatMode(ClientUI.getKnownCombatModeId(villagerEntityId));
        boolean tradingMode = "trading".equals(movementMode);
        ClientSyncedConfig.Snapshot cfg = ClientSyncedConfig.get();
        boolean showMerchant = cfg == null || cfg.enableMerchantModule;
        boolean showCombat = cfg == null || cfg.enableCombatModule;
        boolean showFarming = cfg == null || cfg.enableFarmingModule;

        List<Object> root = new ArrayList<>();
        root.add(action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Neutral", "Return to vanilla AI", "minecraft:villager_spawn_egg",
                !manualFarming && "neutral".equals(movementMode),
                () -> sendMovementCommand(villagerEntityId,
                        "neutral".equals(movementMode) ? PacketVillagerCommand.Command.IDLE : PacketVillagerCommand.Command.NEUTRAL,
                        "neutral".equals(movementMode) ? "Idle" : "Neutral")));
        root.add(action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Release", "Free from service or shoo away", "minecraft:ender_pearl",
                false,
                () -> openReleaseConfirm(parent, villagerEntityId)));
        root.add(category(menuItemCtor, iconItem, withActive,
                "Movement",
                manualFarming ? "Manual farming is active" : (showMerchant && tradingMode) ? "Trading mode is active" : "Current: " + prettyModeName(movementMode),
                "minecraft:leather_boots",
                !manualFarming && (!showMerchant || !tradingMode),
                List.of(
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Idle", "Stand in place", "minecraft:clock",
                                !manualFarming && "idle".equals(movementMode),
                                () -> sendMovementCommand(villagerEntityId, PacketVillagerCommand.Command.IDLE, "Idle")),
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Follow", "Follow the owner", "minecraft:lead",
                                !manualFarming && "follow".equals(movementMode),
                                () -> sendMovementCommand(villagerEntityId, PacketVillagerCommand.Command.FOLLOW, "Follow")),
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Patrol", "Open patrol route setup", "minecraft:filled_map",
                                !manualFarming && "patrol".equals(movementMode),
                                () -> openPatrolPrompt(parent, villagerEntityId))
                )));

        if (showFarming) {
            root.add(category(menuItemCtor, iconItem, withActive,
                    "Farming",
                    manualFarming ? "Manual farming active" : "Manual farming tools",
                    "minecraft:carrot",
                    manualFarming,
                    List.of(
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Manual", "Toggle manual farming", "minecraft:iron_hoe",
                                    manualFarming,
                                    () -> toggleManualFarming(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Workstation", "Register workstation", "minecraft:dirt",
                                    false,
                                    () -> ClientUI.beginWorkstationRegistration(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Deposit", "Register deposit chest", "minecraft:chest",
                                    false,
                                    () -> ClientUI.beginChestRegistration(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Withdraw", "Register withdraw chest", "minecraft:barrel",
                                    false,
                                    () -> ClientUI.beginWithdrawChestRegistration(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Settings", "Open farming settings", "minecraft:writable_book",
                                    false,
                                    () -> ClientUI.openFarmingSettings(parent, villagerEntityId))
                    )));
        }

        if (showMerchant) {
            root.add(category(menuItemCtor, iconItem, withActive,
                    "Trading",
                    tradingMode ? "Trading mode active" : "Trading mode and Trading Hall",
                    "minecraft:emerald",
                    tradingMode,
                    List.of(
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Trading", "Stay near workstation and process the hall", "minecraft:emerald",
                                    tradingMode,
                                    () -> sendMovementCommand(villagerEntityId, PacketVillagerCommand.Command.TRADING, "Trading")),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Storefront", "Record a storefront return position", "minecraft:oak_sign",
                                    false,
                                    () -> ClientUI.beginStorefrontRegistration(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Workstation", "Register workstation from vanilla job-site type", "minecraft:composter",
                                    false,
                                    () -> ClientUI.beginWorkstationRegistration(villagerEntityId)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Hall", "Register Trading Hall", "villageroverhaul:trading_hall",
                                    false,
                                    () -> ClientUI.beginTradingHallRegistration(villagerEntityId))
                    )));
        }

        if (showCombat) {
            root.add(category(menuItemCtor, iconItem, withActive,
                    "Combat",
                    "off".equals(combatMode) ? "Combat modes and settings" : "Current: " + prettyModeName(combatMode),
                    "minecraft:iron_sword",
                    !"off".equals(combatMode),
                    List.of(
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Flee", "Toggle flee mode", "minecraft:shield",
                                    "flee".equals(combatMode),
                                    () -> sendCombatCommand(villagerEntityId, "flee", PacketVillagerCombatCommand.Command.FLEE)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Defend", "Toggle defend mode", "minecraft:iron_chestplate",
                                    "defend".equals(combatMode),
                                    () -> sendCombatCommand(villagerEntityId, "defend", PacketVillagerCombatCommand.Command.DEFEND)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Aggressive", "Toggle aggressive mode", "minecraft:diamond_sword",
                                    "aggressive".equals(combatMode),
                                    () -> sendCombatCommand(villagerEntityId, "aggressive", PacketVillagerCombatCommand.Command.AGGRESSIVE)),
                            action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Settings", "Open combat settings", "minecraft:book",
                                    false,
                                    () -> ClientUI.openCombatSettings(parent, villagerEntityId))
                    )));
        }

        root.add(category(menuItemCtor, iconItem, withActive,
                "Custom Commands",
                "Teach and manage custom commands",
                "minecraft:redstone",
                false,
                List.of(
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Teach", "Teach a custom command", "minecraft:amethyst_shard",
                                false,
                                () -> beginTeachCustomCommand(villagerEntityId)),
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "List", "List custom commands", "minecraft:book",
                                false,
                                () -> openCustomCommandsList(parent, villagerEntityId)),
                        action(menuItemCtor, iconItem, withActive, actionInterface, commandType, "Settings", "Open custom command settings", "minecraft:comparator",
                                false,
                                () -> openCustomCommandsSettings(parent, villagerEntityId))
                )));

        Method openTemporary = radialMenuClass.getMethod("openTemporary", List.class, tempStyleClass, Screen.class);
        Object opened = openTemporary.invoke(null, root, null, parent);
        if (opened instanceof Boolean b) return b;
        return false;
    }

    private static Object category(Constructor<?> ctor, Method iconItem, Method withActive,
                                   String title, String note, String iconId, boolean active, List<Object> children) throws Exception {
        Object item = ctor.newInstance(
                nextId("cat"),
                Component.literal(title),
                Component.literal(note),
                iconItem.invoke(null, iconId),
                null,
                children,
                false,
                false,
                false
        );
        return applyActiveIfSupported(withActive, item, active);
    }

    private static Object action(Constructor<?> ctor, Method iconItem, Method withActive, Class<?> actionInterface, Object commandType,
                                 String title, String note, String iconId, boolean active, Runnable action) throws Exception {
        Object proxy = Proxy.newProxyInstance(
                actionInterface.getClassLoader(),
                new Class<?>[] { actionInterface },
                new RadialActionHandler(title, commandType, action)
        );
        Object item = ctor.newInstance(
                nextId("act"),
                Component.literal(title),
                Component.literal(note),
                iconItem.invoke(null, iconId),
                proxy,
                List.of(),
                false,
                false,
                false
        );
        return applyActiveIfSupported(withActive, item, active);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static String nextId(String prefix) {
        return "vo_" + prefix + "_" + Long.toUnsignedString(NEXT_ID.incrementAndGet(), 36);
    }

    private static Method tryGetMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object applyActiveIfSupported(Method withActive, Object item, boolean active) {
        try {
            if (withActive == null || item == null) return item;
            return withActive.invoke(item, active);
        } catch (Throwable t) {
            VillagerOverhaul.LOG().debug("[VillagerOverhaul] EZ Actions active marker unavailable: {}", t.toString());
            return item;
        }
    }

    private static String normalizeMovementMode(String raw) {
        if (raw == null) return "neutral";
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "idle" -> "idle";
            case "follow" -> "follow";
            case "patrol", "patrol_setup" -> "patrol";
            case "trading" -> "trading";
            default -> "neutral";
        };
    }

    private static String normalizeCombatMode(String raw) {
        if (raw == null) return "off";
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "flee" -> "flee";
            case "defend" -> "defend";
            case "aggressive" -> "aggressive";
            default -> "off";
        };
    }

    private static String prettyModeName(String raw) {
        if (raw == null || raw.isBlank()) return "Off";
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "neutral" -> "Neutral";
            case "idle" -> "Idle";
            case "follow" -> "Follow";
            case "patrol" -> "Patrol";
            case "trading" -> "Trading";
            case "flee" -> "Flee";
            case "defend" -> "Defend";
            case "aggressive" -> "Aggressive";
            default -> raw;
        };
    }

    private static void sendMovementCommand(int villagerEntityId, PacketVillagerCommand.Command cmd, String label) {
        endRadialPause(villagerEntityId, false);
        ClientNetwork.sendToServer(new PacketVillagerCommand(villagerEntityId, cmd));
        showClientToast("Command sent: " + label);
    }

    private static void sendCombatCommand(int villagerEntityId, String key, PacketVillagerCombatCommand.Command cmd) {
        endRadialPause(villagerEntityId, false);
        String current = ClientUI.getKnownCombatModeId(villagerEntityId);
        String normKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        PacketVillagerCombatCommand.Command actual = normKey.equals(current)
                ? PacketVillagerCombatCommand.Command.OFF
                : cmd;
        ClientNetwork.sendToServer(new PacketVillagerCombatCommand(villagerEntityId, actual));
    }

    private static void toggleManualFarming(int villagerEntityId) {
        endRadialPause(villagerEntityId, false);
        boolean next = !ClientUI.isKnownManualFarmingEnabled(villagerEntityId);
        ClientNetwork.sendToServer(new PacketVillagerManualFarmingModeCommand(villagerEntityId, next));
    }

    private static void openPatrolPrompt(Screen parent, int villagerEntityId) {
        clearRadialPause(villagerEntityId);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new PatrolBeginPromptScreen(parent, villagerEntityId));
    }

    private static void beginTeachCustomCommand(int villagerEntityId) {
        endRadialPause(villagerEntityId, false);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        ClientNetwork.sendToServer(new PacketCcBeginTeaching(villagerEntityId, -1));
        try {
            if (mc.player != null) mc.player.closeContainer();
        } catch (Throwable ignored) {}
        mc.setScreen(null);
    }

    private static void openCustomCommandsList(Screen parent, int villagerEntityId) {
        clearRadialPause(villagerEntityId);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new CustomCommandsListScreen(parent, villagerEntityId));
    }

    private static void openCustomCommandsSettings(Screen parent, int villagerEntityId) {
        clearRadialPause(villagerEntityId);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.setScreen(new CustomCommandsSettingsScreen(parent, villagerEntityId));
    }

    private static void openReleaseConfirm(Screen parent, int villagerEntityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        clearRadialPause(villagerEntityId);
        Screen returnParent = parent;
        if (parent instanceof MerchantScreen) {
            try {
                if (mc.player != null) mc.player.closeContainer();
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Release confirm opened from MerchantScreen; closed merchant container first (villagerEntityId={})", villagerEntityId);
            } catch (Throwable ignored) {}
            returnParent = null;
        }
        mc.setScreen(new ReleaseConfirmScreen(returnParent, villagerEntityId));
    }

    private static final class ReleaseConfirmScreen extends Screen {
        private static final int PANEL_W = 286;

        private final Screen parent;
        private final int villagerEntityId;
        private boolean shooAway;
        private boolean confirmed;
        private int pauseTick;
        private Button shooAwayButton;
        private Button confirmButton;

        private ReleaseConfirmScreen(Screen parent, int villagerEntityId) {
            super(Component.literal("Release from oath?"));
            this.parent = parent;
            this.villagerEntityId = villagerEntityId;
        }

        @Override
        protected void init() {
            int cx = this.width / 2;
            int y = Math.max(28, this.height / 2 - 70);

            this.shooAwayButton = this.addRenderableWidget(Button.builder(shooAwayLabel(), b -> {
                        this.shooAway = !this.shooAway;
                        if (this.shooAwayButton != null) this.shooAwayButton.setMessage(shooAwayLabel());
                        if (this.confirmButton != null) this.confirmButton.setMessage(confirmLabel());
                    })
                    .bounds(cx - PANEL_W / 2, y + 72, PANEL_W, 20)
                    .build());

            this.confirmButton = this.addRenderableWidget(Button.builder(confirmLabel(), b -> confirmRelease())
                    .bounds(cx - PANEL_W / 2, y + 100, 134, 20)
                    .build());

            this.addRenderableWidget(Button.builder(Component.literal("Keep oath"), b -> cancelRelease())
                    .bounds(cx + PANEL_W / 2 - 134, y + 100, 134, 20)
                    .build());
        }

        @Override
        public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            gg.fill(0, 0, this.width, this.height, 0xC0101010);
        }

        @Override
        public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
            this.renderBackground(gg, mouseX, mouseY, partialTick);

            Font font = Minecraft.getInstance().font;
            int cx = this.width / 2;
            int y = Math.max(28, this.height / 2 - 70);

            gg.drawCenteredString(font, this.title, cx, y, 0xFFFFFFFF);

            List<FormattedCharSequence> lines = font.split(bodyText(), PANEL_W);
            int lineY = y + 24;
            for (FormattedCharSequence line : lines) {
                gg.drawString(font, line, cx - PANEL_W / 2, lineY, 0xFFBDBDBD, false);
                lineY += 11;
            }

            super.render(gg, mouseX, mouseY, partialTick);
        }

        @Override
        public void tick() {
            super.tick();
            if ((this.pauseTick++ % 20) == 0) sendUiPause(this.villagerEntityId, true);
        }

        @Override
        public void removed() {
            super.removed();
            if (!this.confirmed) sendUiPause(this.villagerEntityId, false);
        }

        private Component bodyText() {
            if (this.shooAway) {
                return Component.literal("Shoo away sends the villager beyond your hearth. Their spirit will not answer a Spawn Anchor.");
            }
            return Component.literal("This frees the villager from your service. They return to Neutral and may swear service again later.");
        }

        private Component shooAwayLabel() {
            return Component.literal((this.shooAway ? "[x] " : "[ ] ") + "Shoo away");
        }

        private Component confirmLabel() {
            return Component.literal(this.shooAway ? "Shoo away" : "Release");
        }

        private void confirmRelease() {
            try {
                this.confirmed = true;
                VillagerOverhaul.LOG().info("[VillagerOverhaul] Client sending release command (villagerEntityId={} shooAway={})", this.villagerEntityId, this.shooAway);
                ClientNetwork.sendToServer(new PacketVillagerReleaseCommand(this.villagerEntityId, this.shooAway));
                ClientUI.markVillagerReleasedLocally(this.villagerEntityId);
                showClientToast(this.shooAway ? "Shooing villager away..." : "Villager released from service.");
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.setScreen(null);
            } catch (Throwable t) {
                sendUiPause(this.villagerEntityId, false);
                VillagerOverhaul.LOG().error("[VillagerOverhaul] Release confirmation failed", t);
            }
        }

        private void cancelRelease() {
            try {
                sendUiPause(this.villagerEntityId, false);
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) mc.setScreen(this.parent);
            } catch (Throwable ignored) {}
        }
    }

    private static void sendUiPause(int villagerEntityId, boolean paused) {
        try {
            if (villagerEntityId <= 0) return;
            ClientNetwork.sendToServer(new PacketVillagerUiPause(villagerEntityId, paused));
            if (paused && villagerEntityId == activePauseVillagerEntityId) {
                activePauseSentAtMs = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {}
    }

    private static void beginRadialPause(int villagerEntityId) {
        activePauseVillagerEntityId = villagerEntityId;
        activePauseSentAtMs = 0L;
    }

    private static void endRadialPause(int villagerEntityId, boolean keepPaused) {
        clearRadialPause(villagerEntityId);
        sendUiPause(villagerEntityId, keepPaused);
    }

    private static void clearRadialPause(int villagerEntityId) {
        if (villagerEntityId == activePauseVillagerEntityId) {
            activePauseVillagerEntityId = -1;
            activePauseSentAtMs = 0L;
        }
    }

    private static boolean isTemporaryRadialOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.screen == null) return false;
            if (!"org.z2six.ezactions.gui.RadialMenuScreen".equals(mc.screen.getClass().getName())) {
                return false;
            }

            Class<?> radialMenuClass = Class.forName("org.z2six.ezactions.data.menu.RadialMenu");
            Method isTemporarySession = radialMenuClass.getMethod("isTemporarySession");
            Object out = isTemporarySession.invoke(null);
            return !(out instanceof Boolean b) || b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void showClientToast(String msg) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.YELLOW), true);
            }
        } catch (Throwable ignored) {}
    }

    private static void showFailureMessage() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("Could not open EZ Actions radial. Check that EZ Actions is installed.")
                                .withStyle(ChatFormatting.RED),
                        true
                );
            }
        } catch (Throwable ignored) {}
    }

    private record RadialActionHandler(String title, Object commandType, Runnable action) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method == null ? "" : method.getName();
            if ("getId".equals(name)) return "vo_radial:" + title;
            if ("getType".equals(name)) return commandType;
            if ("getDisplayName".equals(name)) return Component.literal(title);
            if ("serialize".equals(name)) {
                JsonObject out = new JsonObject();
                try {
                    out.addProperty("type", String.valueOf(commandType));
                    out.addProperty("runtimeOnly", true);
                } catch (Throwable ignored) {}
                return out;
            }
            if ("execute".equals(name)) {
                try {
                    if (action != null) action.run();
                    return true;
                } catch (Throwable t) {
                    VillagerOverhaul.LOG().error("[VillagerOverhaul] Villager commands radial action failed: {}", title, t);
                    return false;
                }
            }
            if ("toString".equals(name)) return "VillagerCommandsRadialAction[" + title + "]";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null || args.length == 0 ? null : args[0]);
            return null;
        }
    }
}
