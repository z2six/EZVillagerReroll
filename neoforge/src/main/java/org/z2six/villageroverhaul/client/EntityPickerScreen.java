// neoforge\src\main\java\org\z2six\villageroverhaul\client\EntityPickerScreen.java
package org.z2six.villageroverhaul.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.z2six.villageroverhaul.VillagerOverhaul;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public final class EntityPickerScreen extends Screen {

    private final Screen parent;
    private final Consumer<String> onSelect;
    private final Set<String> existingIdsLower;

    private EditBox searchBox;
    private final List<Button> resultButtons = new ArrayList<>();

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 200;
    private static final int PAD = 10;

    private static final int PANEL_BG = 0xCC0B0B0B;
    private static final int PANEL_BORDER = 0xFF3A3A3A;

    public EntityPickerScreen(Screen parent, Consumer<String> onSelect, Set<String> existingIds) {
        super(Component.literal("Pick Entity"));
        this.parent = parent;
        this.onSelect = onSelect;
        this.existingIdsLower = toLowerSet(existingIds);
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        searchBox = new EditBox(this.font, left + PAD, top + PAD + 18, PANEL_W - (PAD * 2), 16, Component.literal("Search"));
        this.addRenderableWidget(searchBox);

        int btnY = top + PAD + 44;
        int btnW = PANEL_W - (PAD * 2);
        int btnH = 16;

        for (int i = 0; i < 8; i++) {
            int y = btnY + i * (btnH + 4);
            Button b = Button.builder(Component.literal(""), bb -> {
                        String id = bb.getMessage() == null ? "" : bb.getMessage().getString();
                        if (id == null || id.isBlank()) return;
                        String idLower = id.toLowerCase(Locale.ROOT);
                        if (existingIdsLower.contains(idLower)) {
                            VillagerOverhaul.LOG().info("[VillagerOverhaul] EntityPicker: '{}' already added; ignoring.", id);
                            return;
                        }
                        if (onSelect != null) {
                            onSelect.accept(id);
                            VillagerOverhaul.LOG().info("[VillagerOverhaul] EntityPicker: added '{}'.", id);
                        }
                        onClose();
                    })
                    .pos(left + PAD, y)
                    .size(btnW, btnH)
                    .build();
            b.visible = false;
            b.active = false;
            this.addRenderableWidget(b);
            resultButtons.add(b);
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> onClose())
                        .pos(left + PANEL_W - 58 - PAD, top + PAD)
                        .size(58, 18)
                        .build()
        );
    }

    @Override
    public void tick() {
        super.tick();
        updateResults();
    }

    private void updateResults() {
        String q = searchBox == null ? "" : searchBox.getValue();
        q = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        List<String> matches = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id == null) continue;
            String s = id.toString();
            if (q.isEmpty() || s.contains(q)) {
                matches.add(s);
            }
        }
        matches.sort(Comparator.naturalOrder());

        for (int i = 0; i < resultButtons.size(); i++) {
            Button b = resultButtons.get(i);
            if (b == null) continue;
            if (i >= matches.size()) {
                b.visible = false;
                b.active = false;
                b.setMessage(Component.literal(""));
                continue;
            }
            String id = matches.get(i);
            b.visible = true;
            boolean already = existingIdsLower.contains(id.toLowerCase(Locale.ROOT));
            b.active = !already;
            b.setMessage(already
                    ? Component.literal(id).withStyle(ChatFormatting.DARK_GRAY)
                    : Component.literal(id));
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            super.renderBackground(gg, mouseX, mouseY, partialTick);
        } catch (Throwable ignored) {}

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        drawPanel(gg, left, top, PANEL_W, PANEL_H);

        Font font = Minecraft.getInstance().font;
        gg.drawString(font, "Entity Picker", left + PAD, top + PAD + 5, 0xFFFFFFFF, true);

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private static void drawPanel(GuiGraphics gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, PANEL_BG);
        gg.fill(x, y, x + w, y + 1, PANEL_BORDER);
        gg.fill(x, y + h - 1, x + w, y + h, PANEL_BORDER);
        gg.fill(x, y, x + 1, y + h, PANEL_BORDER);
        gg.fill(x + w - 1, y, x + w, y + h, PANEL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Set<String> toLowerSet(Set<String> in) {
        if (in == null || in.isEmpty()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : in) {
            if (s == null) continue;
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

}
