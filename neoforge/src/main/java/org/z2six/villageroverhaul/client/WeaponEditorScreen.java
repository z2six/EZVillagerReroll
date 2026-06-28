package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.z2six.villageroverhaul.VillagerOverhaul;
import org.z2six.villageroverhaul.api.VillagerOverhaulRenderAccess;
import org.z2six.villageroverhaul.client.render.ArmorEditorProfile;
import org.z2six.villageroverhaul.client.render.HolsterWaistDefaults;
import org.z2six.villageroverhaul.client.render.VillagerHolsteredLoadoutLayer;
import org.z2six.villageroverhaul.client.render.WeaponEditorState;
import org.z2six.villageroverhaul.client.render.WeaponEditorTransform;
import org.z2six.villageroverhaul.network.modes.PacketVillagerForceBlock;
import org.z2six.villageroverhaul.server.VillagerFactionService;

public final class WeaponEditorScreen extends Screen {
    private static final int BOX_W = 270;
    private static final int BOX_H = 310;

    private enum Mode { HELD, HOLSTER }
    private enum Param { X, Y, Z, RX, RY, RZ, SX, SY, SZ, SPIN, ROLL }
    private enum HolsterProfileMode { AUTO, GENERIC, BOW, CROSSBOW }

    private final Screen parent;
    private int villagerId = -1;
    private Mode mode = Mode.HELD;
    private ArmorEditorProfile modelProfile = ArmorEditorProfile.VILLAGER;
    private boolean autoModelProfile = true;
    private WeaponEditorState.HeldProfile heldProfile = WeaponEditorState.HeldProfile.GENERIC;
    private boolean autoHeldProfile = true;
    private HolsterProfileMode holsterProfileMode = HolsterProfileMode.AUTO;
    private Param selected = Param.X;
    private String status = "";

    private Button modeButton;
    private Button modelButton;
    private Button profileButton;
    private Button paramButton;

    private boolean rightDragging = false;
    private double lastDragX;
    private double lastDragY;
    private float previewYawDeg = 0.0f;
    private float previewPitchDeg = 0.0f;

    public WeaponEditorScreen(Screen parent) {
        super(Component.literal("Weapon Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int x = 10;
        int y = 10;

        addRenderableWidget(Button.builder(Component.literal("Retarget"), b -> retarget())
                .bounds(x, y, 76, 20).build());
        x += 82;
        modeButton = addRenderableWidget(Button.builder(Component.literal(""), b -> cycleMode())
                .bounds(x, y, 86, 20).build());
        x += 92;
        modelButton = addRenderableWidget(Button.builder(Component.literal(""), b -> cycleModel())
                .bounds(x, y, 112, 20).build());
        x += 118;
        profileButton = addRenderableWidget(Button.builder(Component.literal(""), b -> cycleProfile())
                .bounds(x, y, 128, 20).build());
        x += 134;
        paramButton = addRenderableWidget(Button.builder(Component.literal(""), b -> cycleParam())
                .bounds(x, y, 82, 20).build());

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustSelected(-1.0f))
                .bounds(10, 38, 34, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustSelected(1.0f))
                .bounds(50, 38, 34, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> resetSelectedProfile())
                .bounds(92, 38, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Block"), b -> requestBlockPreview())
                .bounds(162, 38, 62, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Log"), b -> logExport())
                .bounds(232, 38, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Copy"), b -> copyExport())
                .bounds(290, 38, 58, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(this.width - 72, 10, 62, 20).build());

        retarget();
        refreshButtons();
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        gg.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg, mouseX, mouseY, partialTick);

        int boxLeft = (this.width - BOX_W) / 2;
        int boxTop = 68;
        int boxRight = boxLeft + BOX_W;
        int boxBottom = boxTop + BOX_H;

        gg.fill(boxLeft, boxTop, boxRight, boxBottom, 0xAA101010);
        gg.fill(boxLeft, boxTop, boxRight, boxTop + 1, 0xFF606060);
        gg.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, 0xFF606060);
        gg.fill(boxLeft, boxTop, boxLeft + 1, boxBottom, 0xFF606060);
        gg.fill(boxRight - 1, boxTop, boxRight, boxBottom, 0xFF606060);

        LivingEntity le = resolveEntity();
        if (le != null) {
            InventoryScreen.renderEntityInInventoryFollowsAngle(
                    gg,
                    boxLeft + 6, boxTop + 6,
                    boxRight - 6, boxBottom - 6,
                    54,
                    0.0f,
                    previewYawDeg / 20.0f,
                    previewPitchDeg / 20.0f,
                    le
            );
        }

        int infoY = boxBottom + 10;
        gg.drawCenteredString(this.font, selectedLine(), this.width / 2, infoY, 0xFFFFFF);
        gg.drawCenteredString(this.font, valueLine(), this.width / 2, infoY + 14, 0xC0C0C0);
        gg.drawCenteredString(this.font, "Wheel or +/- adjusts selected. Shift = fine. RMB drag rotates preview.", this.width / 2, infoY + 32, 0xA0A0A0);
        if (!status.isBlank()) {
            gg.drawCenteredString(this.font, status, this.width / 2, infoY + 48, 0x80D0FF);
        }
        if (le == null) {
            gg.drawCenteredString(this.font, "No villager found. Look at one or stand closer, then Retarget.", this.width / 2, infoY + 64, 0xFF6060);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        float dir = deltaY > 0.0 ? 1.0f : deltaY < 0.0 ? -1.0f : 0.0f;
        if (dir != 0.0f) {
            adjustSelected(dir);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && isInsideEntityBox(mouseX, mouseY)) {
            rightDragging = true;
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 1) {
            rightDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 1 && rightDragging) {
            double dx = mouseX - lastDragX;
            double dy = mouseY - lastDragY;
            lastDragX = mouseX;
            lastDragY = mouseY;
            float yawPerPixel = hasShiftDown() ? 0.45f : 1.35f;
            float pitchPerPixel = hasShiftDown() ? 0.3f : 0.9f;
            previewYawDeg = Mth.wrapDegrees(previewYawDeg + (float) dx * yawPerPixel);
            previewPitchDeg = Mth.clamp(previewPitchDeg + (float) dy * pitchPerPixel, -85.0f, 85.0f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void retarget() {
        Villager v = findNearbyVillager();
        villagerId = v == null ? -1 : v.getId();
        if (v != null && autoModelProfile) {
            modelProfile = VillagerFactionService.isDwarf(v) ? ArmorEditorProfile.DWARF : ArmorEditorProfile.VILLAGER;
        }
        if (v != null && autoHeldProfile) {
            ItemStack stack = selectedHeldStack(v);
            heldProfile = WeaponEditorState.heldProfileFor(stack);
        }
        status = v == null ? "No villager targeted" : "Target villager id=" + v.getId();
        refreshButtons();
    }

    private static Villager findNearbyVillager() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null) return null;
            HitResult hr = mc.hitResult;
            if (hr instanceof EntityHitResult ehr && ehr.getEntity() instanceof Villager v) return v;
            AABB box = mc.player.getBoundingBox().inflate(8.0);
            Villager nearest = null;
            double best = Double.MAX_VALUE;
            for (Villager v : mc.player.level().getEntitiesOfClass(Villager.class, box)) {
                double d2 = v.distanceToSqr(mc.player);
                if (d2 < best) {
                    best = d2;
                    nearest = v;
                }
            }
            return nearest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private LivingEntity resolveEntity() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || villagerId < 0) return null;
            return mc.level.getEntity(villagerId) instanceof LivingEntity le ? le : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void cycleMode() {
        mode = mode == Mode.HELD ? Mode.HOLSTER : Mode.HELD;
        selected = mode == Mode.HELD ? Param.X : Param.X;
        refreshButtons();
    }

    private void cycleModel() {
        if (autoModelProfile) {
            autoModelProfile = false;
            modelProfile = ArmorEditorProfile.VILLAGER;
        } else if (modelProfile == ArmorEditorProfile.VILLAGER) {
            modelProfile = ArmorEditorProfile.DWARF;
        } else {
            autoModelProfile = true;
            if (resolveEntity() instanceof Villager v) {
                modelProfile = VillagerFactionService.isDwarf(v) ? ArmorEditorProfile.DWARF : ArmorEditorProfile.VILLAGER;
            }
        }
        refreshButtons();
    }

    private void cycleProfile() {
        if (mode == Mode.HELD) {
            if (autoHeldProfile) {
                autoHeldProfile = false;
                heldProfile = WeaponEditorState.HeldProfile.GENERIC;
            } else {
                WeaponEditorState.HeldProfile[] vals = WeaponEditorState.HeldProfile.values();
                int next = (heldProfile.ordinal() + 1) % (vals.length + 1);
                if (next >= vals.length) {
                    autoHeldProfile = true;
                } else {
                    heldProfile = vals[next];
                }
            }
        } else {
            HolsterProfileMode[] vals = HolsterProfileMode.values();
            holsterProfileMode = vals[(holsterProfileMode.ordinal() + 1) % vals.length];
        }
        refreshButtons();
    }

    private void cycleParam() {
        Param[] vals = mode == Mode.HELD
                ? new Param[]{Param.X, Param.Y, Param.Z, Param.RX, Param.RY, Param.RZ, Param.SX, Param.SY, Param.SZ}
                : new Param[]{Param.X, Param.Y, Param.Z, Param.RX, Param.RY, Param.RZ, Param.SPIN, Param.ROLL};
        int idx = 0;
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] == selected) {
                idx = i;
                break;
            }
        }
        selected = vals[(idx + 1) % vals.length];
        refreshButtons();
    }

    private void adjustSelected(float dir) {
        float step = switch (selected) {
            case X, Y, Z -> hasShiftDown() ? 0.0025f : 0.01f;
            case SX, SY, SZ -> hasShiftDown() ? 0.01f : 0.05f;
            case RX, RY, RZ, SPIN, ROLL -> hasShiftDown() ? 0.5f : 2.0f;
        };
        String key = keyFor(selected);
        if (mode == Mode.HELD) {
            WeaponEditorState.setHeldParam(resolvedModelProfile(), resolvedHeldProfile(), key, dir * step, true);
        } else {
            setHolsterParam(key, dir * step, true);
        }
        status = "Adjusted " + selected.name();
    }

    private void resetSelectedProfile() {
        if (mode == Mode.HELD) {
            WeaponEditorState.resetHeld(resolvedModelProfile(), resolvedHeldProfile());
        } else if (resolvedModelProfile() == ArmorEditorProfile.DWARF) {
            HolsterWaistDefaults.resetDwarfTransform(toDwarfHolsterProfile(resolvedHolsterProfile()));
        } else {
            VillagerHolsteredLoadoutLayer.ezvr$resetWaistTweak(resolvedHolsterProfile());
        }
        status = "Reset current profile";
    }

    private void requestBlockPreview() {
        try {
            LivingEntity le = resolveEntity();
            if (!(le instanceof Villager v)) {
                status = "No villager targeted";
                return;
            }
            ClientNetwork.sendToServer(new PacketVillagerForceBlock(v.getId(), 200));
            status = "Requested blocking preview";
        } catch (Throwable t) {
            status = "Blocking preview failed";
        }
    }

    private void logExport() {
        String export = exportText();
        VillagerOverhaul.LOG().info("{}", export);
        status = "Export written to latest.log";
    }

    private void copyExport() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.keyboardHandler == null) {
                status = "Clipboard unavailable";
                return;
            }
            mc.keyboardHandler.setClipboard(exportText());
            status = "Export copied";
        } catch (Throwable t) {
            status = "Clipboard failed";
        }
    }

    private String exportText() {
        return WeaponEditorState.heldHardcodeLines()
                + "\n[VillagerOverhaul] Weapon editor holster export\n"
                + "holster generic: " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT) + "\n"
                + "holster bow: " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.BOW) + "\n"
                + "holster crossbow: " + VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(VillagerHolsteredLoadoutLayer.WaistProfile.CROSSBOW) + "\n"
                + "dwarf holster: " + HolsterWaistDefaults.dwarfTweakString(HolsterWaistDefaults.Profile.DEFAULT) + "\n";
    }

    private ArmorEditorProfile resolvedModelProfile() {
        if (autoModelProfile && resolveEntity() instanceof Villager v) {
            return VillagerFactionService.isDwarf(v) ? ArmorEditorProfile.DWARF : ArmorEditorProfile.VILLAGER;
        }
        return modelProfile;
    }

    private WeaponEditorState.HeldProfile resolvedHeldProfile() {
        if (autoHeldProfile && resolveEntity() instanceof Villager v) {
            return WeaponEditorState.heldProfileFor(selectedHeldStack(v));
        }
        return heldProfile;
    }

    private VillagerHolsteredLoadoutLayer.WaistProfile resolvedHolsterProfile() {
        return switch (holsterProfileMode) {
            case GENERIC -> VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT;
            case BOW -> VillagerHolsteredLoadoutLayer.WaistProfile.BOW;
            case CROSSBOW -> VillagerHolsteredLoadoutLayer.WaistProfile.CROSSBOW;
            case AUTO -> detectHolsterProfile();
        };
    }

    private void setHolsterParam(String key, float value, boolean additive) {
        if (resolvedModelProfile() == ArmorEditorProfile.DWARF) {
            HolsterWaistDefaults.setDwarfTransform(toDwarfHolsterProfile(resolvedHolsterProfile()), key, value, additive);
        } else {
            VillagerHolsteredLoadoutLayer.ezvr$setWaistTweak(resolvedHolsterProfile(), key, value, additive);
        }
    }

    private static HolsterWaistDefaults.Profile toDwarfHolsterProfile(VillagerHolsteredLoadoutLayer.WaistProfile profile) {
        return switch (profile == null ? VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT : profile) {
            case DEFAULT -> HolsterWaistDefaults.Profile.DEFAULT;
            case BOW -> HolsterWaistDefaults.Profile.BOW;
            case CROSSBOW -> HolsterWaistDefaults.Profile.CROSSBOW;
        };
    }

    private VillagerHolsteredLoadoutLayer.WaistProfile detectHolsterProfile() {
        try {
            if (resolveEntity() instanceof VillagerOverhaulRenderAccess acc) {
                return VillagerHolsteredLoadoutLayer.ezvr$getWaistProfileForStack(acc.ezvr$getCombatLoadoutMain());
            }
        } catch (Throwable ignored) {}
        return VillagerHolsteredLoadoutLayer.WaistProfile.DEFAULT;
    }

    private ItemStack selectedHeldStack(Villager v) {
        if (v == null) return ItemStack.EMPTY;
        ItemStack off = v.getOffhandItem();
        if (off != null && !off.isEmpty() && WeaponEditorState.heldProfileFor(off) == WeaponEditorState.HeldProfile.SHIELD) {
            return off;
        }
        ItemStack main = v.getMainHandItem();
        return main == null ? ItemStack.EMPTY : main;
    }

    private void refreshButtons() {
        if (modeButton != null) modeButton.setMessage(Component.literal("Mode: " + mode.name()));
        if (modelButton != null) modelButton.setMessage(Component.literal("Model: " + (autoModelProfile ? "Auto" : modelProfile.name())));
        if (profileButton != null) {
            String label = mode == Mode.HELD
                    ? "Held: " + (autoHeldProfile ? "Auto" : heldProfile.name())
                    : "Holster: " + holsterProfileMode.name();
            profileButton.setMessage(Component.literal(label));
        }
        if (paramButton != null) paramButton.setMessage(Component.literal("Param: " + selected.name()));
    }

    private String selectedLine() {
        if (mode == Mode.HELD) {
            return "Held " + resolvedModelProfile().name() + " / " + resolvedHeldProfile().name() + " / " + selected.name();
        }
        return "Holster " + resolvedHolsterProfile().name() + " / " + selected.name();
    }

    private String valueLine() {
        if (mode == Mode.HELD) {
            return WeaponEditorState.heldLine(resolvedModelProfile(), resolvedHeldProfile());
        }
        if (resolvedModelProfile() == ArmorEditorProfile.DWARF) {
            return HolsterWaistDefaults.dwarfTweakString(toDwarfHolsterProfile(resolvedHolsterProfile()));
        }
        return VillagerHolsteredLoadoutLayer.ezvr$waistTweakString(resolvedHolsterProfile());
    }

    private static String keyFor(Param param) {
        return switch (param) {
            case X -> "tx";
            case Y -> "ty";
            case Z -> "tz";
            case RX -> "rx";
            case RY -> "ry";
            case RZ -> "rz";
            case SX -> "sx";
            case SY -> "sy";
            case SZ -> "sz";
            case SPIN -> "spin";
            case ROLL -> "roll";
        };
    }

    private boolean isInsideEntityBox(double mouseX, double mouseY) {
        int boxLeft = (this.width - BOX_W) / 2;
        int boxTop = 68;
        return mouseX >= boxLeft && mouseX <= boxLeft + BOX_W && mouseY >= boxTop && mouseY <= boxTop + BOX_H;
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }
}
