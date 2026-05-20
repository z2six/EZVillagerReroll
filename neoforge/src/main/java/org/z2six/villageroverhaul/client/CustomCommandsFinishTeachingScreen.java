package org.z2six.villageroverhaul.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import org.z2six.villageroverhaul.network.customcommands.PacketCcSaveTaughtAction;
import org.z2six.villageroverhaul.network.customcommands.PacketCcTeachSessionQuery;

import java.util.ArrayList;
import java.util.List;

public final class CustomCommandsFinishTeachingScreen extends Screen {

    private final Screen parent;
    private final int villagerEntityId;

    private static final int PANEL_W = 336;
    private static final int PANEL_H = 238;
    private static final int STEPS_ROWS_VISIBLE = 3;
    private static final int ROW_H = 18;

    private EditBox titleBox;
    private EditBox commandBox;
    private EditBox descBox;
    private EditBox timeoutBox;
    private EditBox retryBox;
    private EditBox stopBox;
    private Button caseBtn;
    private Button chainBtn;
    private boolean caseSensitive = true;
    private boolean chain = false;
    private Button anyoneBtn;
    private boolean anyone = false;
    private Button combatOverrideBtn;
    private boolean combatOverride = true;

    private int editIndex = -1;
    private final List<CompoundTag> steps = new ArrayList<>();
    private int scroll = 0;
    private boolean scrollDragging = false;
    private int scrollDragOffsetY = 0;

    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_PAD = 3;

    public CustomCommandsFinishTeachingScreen(Screen parent, int villagerEntityId) {
        super(Component.literal("Finish teaching"));
        this.parent = parent;
        this.villagerEntityId = villagerEntityId;
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // no-op (prevents NeoForge background blur overlay)
    }

    @Override
    protected void init() {
        super.init();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        titleBox = new EditBox(this.font, left + 10, top + 28, PANEL_W - 20, 18, Component.literal("Title"));
        titleBox.setMaxLength(64);
        titleBox.setTooltip(Tooltip.create(Component.literal("A name for this teaching (shown in the list).")));
        addRenderableWidget(titleBox);

        commandBox = new EditBox(this.font, left + 10, top + 50, PANEL_W - 20, 18, Component.literal("Chat Command"));
        commandBox.setMaxLength(64);
        commandBox.setTooltip(Tooltip.create(Component.literal("What you type in chat to trigger this teaching. Use \"##\" for multiple triggers. Use \"$$text$$\" to match if the message contains text (\"$$a&&b$$\" requires both).")));
        addRenderableWidget(commandBox);

        descBox = new EditBox(this.font, left + 10, top + 72, PANEL_W - 20, 18, Component.literal("Description"));
        descBox.setMaxLength(256);
        descBox.setTooltip(Tooltip.create(Component.literal("Optional notes for you (shown in the list).")));
        addRenderableWidget(descBox);

        caseBtn = Button.builder(Component.literal("Case [x]"), b -> {
                    caseSensitive = !caseSensitive;
                    updateCaseButton();
                })
                .pos(left + 10, top + 94).size(72, 18).build();
        caseBtn.setTooltip(Tooltip.create(Component.literal("If enabled, uppercase/lowercase must match exactly.")));
        addRenderableWidget(caseBtn);

        chainBtn = Button.builder(Component.literal("Chain []"), b -> {
                    chain = !chain;
                    updateChainButton();
                })
                .pos(left + 86, top + 94).size(70, 18).build();
        chainBtn.setTooltip(Tooltip.create(Component.literal("If enabled, villagers can pass this command through a chain to reach far away villagers.")));
        addRenderableWidget(chainBtn);

        anyoneBtn = Button.builder(Component.literal("Anyone []"), b -> {
                    anyone = !anyone;
                    updateAnyoneButton();
                })
                .pos(left + 160, top + 94).size(78, 18).build();
        anyoneBtn.setTooltip(Tooltip.create(Component.literal("If enabled, ANY player can trigger this teaching (not just the owner).")));
        addRenderableWidget(anyoneBtn);

        combatOverrideBtn = Button.builder(Component.literal("Combat [x]"), b -> {
                    combatOverride = !combatOverride;
                    updateCombatOverrideButton();
                })
                .pos(left + 242, top + 94).size(84, 18).build();
        combatOverrideBtn.setTooltip(Tooltip.create(Component.literal("If enabled, combat can cancel this teaching and the villager returns to its previous task after combat.")));
        addRenderableWidget(combatOverrideBtn);

        int smallW = 46;
        int rowY = top + 116;
        timeoutBox = new EditBox(this.font, left + 62, rowY, smallW, 18, Component.literal("Timeout"));
        timeoutBox.setMaxLength(4);
        timeoutBox.setTooltip(Tooltip.create(Component.literal("If the villager can't complete a step in time, it fails and retries later.")));
        addRenderableWidget(timeoutBox);

        retryBox = new EditBox(this.font, left + 166, rowY, smallW, 18, Component.literal("Retry"));
        retryBox.setMaxLength(4);
        retryBox.setTooltip(Tooltip.create(Component.literal("How long to wait before automatically retrying after a failure.")));
        addRenderableWidget(retryBox);

        stopBox = new EditBox(this.font, left + 280, rowY, 40, 18, Component.literal("Stop after"));
        stopBox.setMaxLength(4);
        stopBox.setTooltip(Tooltip.create(Component.literal("Stop trying after this many failures (0 = never stop).")));
        addRenderableWidget(stopBox);

        int bottomY = top + PANEL_H - 10 - 18;
        Button btnSave = Button.builder(Component.literal("Save"), b -> onSave())
                .pos(left + PANEL_W - 10 - 60, bottomY).size(60, 18).build();
        addRenderableWidget(btnSave);

        Button btnCancel = Button.builder(Component.literal("Cancel"), b -> {
                    if (this.minecraft != null) this.minecraft.setScreen(parent);
                })
                .pos(left + PANEL_W - 10 - 60 - 64, bottomY).size(60, 18).build();
        addRenderableWidget(btnCancel);

        // Request latest session data from server.
        ClientNetwork.sendToServer(new PacketCcTeachSessionQuery(villagerEntityId));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();

        try {
            CompoundTag tag = CustomCommandsClientCache.consumeTeachSession(villagerEntityId);
            if (tag == null) return;

            editIndex = tag.getInt("editIndex");

            try { titleBox.setValue(tag.getString("title")); } catch (Throwable ignored) {}
            try { descBox.setValue(tag.getString("desc")); } catch (Throwable ignored) {}
            try { commandBox.setValue(tag.getString("cmd")); } catch (Throwable ignored) {}
            try { caseSensitive = tag.getBoolean("case"); } catch (Throwable ignored) {}
            try { chain = tag.getBoolean("chain"); } catch (Throwable ignored) {}
            try { anyone = tag.getBoolean("anyone"); } catch (Throwable ignored) {}
            try { combatOverride = !tag.contains("co") || tag.getBoolean("co"); } catch (Throwable ignored) {}
            try { timeoutBox.setValue(String.valueOf(tag.getInt("timeout"))); } catch (Throwable ignored) {}
            try { retryBox.setValue(String.valueOf(tag.getInt("retry"))); } catch (Throwable ignored) {}
            try { stopBox.setValue(String.valueOf(tag.getInt("stop"))); } catch (Throwable ignored) {}
            updateCaseButton();
            updateChainButton();
            updateAnyoneButton();
            updateCombatOverrideButton();

            steps.clear();
            if (tag.contains("steps", Tag.TAG_LIST)) {
                ListTag list = tag.getList("steps", Tag.TAG_COMPOUND);
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag st = list.getCompound(i);
                    steps.add(st);
                    try {
                        if (st.contains("rules", Tag.TAG_LIST)) {
                            int type = st.getInt("t");
                            CompoundTag rt = new CompoundTag();
                            rt.putInt("t", type + 100);
                            rt.put("rules", st.getList("rules", Tag.TAG_COMPOUND));
                            steps.add(rt);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            int maxScroll = Math.max(0, steps.size() - STEPS_ROWS_VISIBLE);
            if (scroll > maxScroll) scroll = maxScroll;
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int listX = left + 10;
        int listY = top + 152;
        int listW = PANEL_W - 20;
        int listH = STEPS_ROWS_VISIBLE * ROW_H;
        if (mouseX < listX || mouseX > listX + listW || mouseY < listY || mouseY > listY + listH) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        int maxScroll = Math.max(0, steps.size() - STEPS_ROWS_VISIBLE);
        scroll -= (int) Math.signum(scrollY);
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int listX = left + 10;
        int listY = top + 152;
        int listW = PANEL_W - 20;
        int trackX = listX + listW - SCROLLBAR_W;
        int trackY = listY;
        int trackH = STEPS_ROWS_VISIBLE * ROW_H;

        int maxScroll = Math.max(0, steps.size() - STEPS_ROWS_VISIBLE);
        if (maxScroll > 0 && mouseX >= trackX && mouseX <= trackX + SCROLLBAR_W && mouseY >= trackY && mouseY <= trackY + trackH) {
            int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) STEPS_ROWS_VISIBLE / (double) Math.max(STEPS_ROWS_VISIBLE, steps.size())));
            int thumbY = trackY + (int) Math.round((double) (trackH - thumbH) * ((double) scroll / (double) maxScroll));
            if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                scrollDragging = true;
                scrollDragOffsetY = (int) mouseY - thumbY;
                return true;
            }
            int y = (int) mouseY - trackY - (thumbH / 2);
            double frac = (trackH - thumbH) <= 0 ? 0.0 : (double) y / (double) (trackH - thumbH);
            frac = Math.max(0.0, Math.min(1.0, frac));
            scroll = (int) Math.round(frac * (double) maxScroll);
            if (scroll < 0) scroll = 0;
            if (scroll > maxScroll) scroll = maxScroll;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!scrollDragging) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int listY = top + 152;
        int trackY = listY;
        int trackH = STEPS_ROWS_VISIBLE * ROW_H;

        int maxScroll = Math.max(0, steps.size() - STEPS_ROWS_VISIBLE);
        if (maxScroll <= 0) return true;

        int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) STEPS_ROWS_VISIBLE / (double) Math.max(STEPS_ROWS_VISIBLE, steps.size())));
        int newThumbY = (int) mouseY - scrollDragOffsetY;
        int minY = trackY;
        int maxY = trackY + trackH - thumbH;
        if (newThumbY < minY) newThumbY = minY;
        if (newThumbY > maxY) newThumbY = maxY;
        double frac = (maxY - minY) <= 0 ? 0.0 : (double) (newThumbY - minY) / (double) (maxY - minY);
        scroll = (int) Math.round(frac * (double) maxScroll);
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) scrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private Component formatStep(CompoundTag t) {
        try {
            int type = t.getInt("t");
            if (type == 0) {
                return Component.literal("Waypoint: " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z"));
            } else if (type == 5) {
                int wt = t.getInt("wt");
                return Component.literal("Wait: " + (wt / 20.0f) + "s");
            } else if (type == 6) {
                int lt = Math.max(0, t.getInt("lt"));
                return Component.literal("Look: " + (lt / 20.0f) + "s");
            } else if (type == 1) {
                return Component.literal("Interact block: " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z"));
            } else if (type == 2) {
                return Component.literal("Interact entity: " + (t.contains("e") ? t.getUUID("e").toString() : "?"));
            } else if (type == 3) {
                return Component.literal("Withdraw chest: " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z"));
            } else if (type == 4) {
                return Component.literal("Deposit chest: " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z"));
            } else if (type == 103 || type == 104) {
                String p = type == 103 ? "Withdraw items: " : "Deposit items: ";
                String s = rulesSummary(t);
                return Component.literal(p + (s.isBlank() ? "<none>" : s));
            }
        } catch (Throwable ignored) {}
        return Component.literal("Step");
    }

    private String rulesSummary(CompoundTag t) {
        try {
            if (t == null || !t.contains("rules", Tag.TAG_LIST)) return "";
            ListTag rl = t.getList("rules", Tag.TAG_COMPOUND);
            StringBuilder sb = new StringBuilder();
            int n = Math.min(4, rl.size());
            for (int i = 0; i < n; i++) {
                CompoundTag r = rl.getCompound(i);
                String id = r.getString("id");
                int c = r.getInt("n");
                if (id == null || id.isBlank()) continue;
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(id).append(" x").append(Math.max(0, c));
            }
            if (rl.size() > 4) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append("...");
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        gg.fill(left, top, left + PANEL_W, top + PANEL_H, 0xCC0B0B0B);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, 0xFF3A3A3A);

        gg.drawString(this.font, Component.literal("Finish teaching"), left + 10, top + 10, 0xFFFFFFFF);
        gg.drawString(this.font, Component.literal("Timeout:"), left + 10, top + 121, 0xFFB0B0B0);
        gg.drawString(this.font, Component.literal("Retry:"), left + 122, top + 121, 0xFFB0B0B0);
        gg.drawString(this.font, Component.literal("Stop:"), left + 234, top + 121, 0xFFB0B0B0);
        gg.drawString(this.font, Component.literal("Recorded actions:"), left + 10, top + 140, 0xFFB0B0B0);

        int listX = left + 10;
        int listY = top + 152;
        int rowH = ROW_H;

        gg.fill(listX, listY - 2, listX + (PANEL_W - 20), listY + (STEPS_ROWS_VISIBLE * rowH) + 2, 0x22000000);
        gg.renderOutline(listX, listY - 2, PANEL_W - 20, (STEPS_ROWS_VISIBLE * rowH) + 4, 0xFF2E2E2E);

        for (int i = 0; i < STEPS_ROWS_VISIBLE; i++) {
            int idx = scroll + i;
            if (idx >= steps.size()) break;
            gg.drawString(this.font, formatStep(steps.get(idx)), listX, listY + (i * rowH), 0xFFFFFFFF);
        }

        int maxScroll = Math.max(0, steps.size() - STEPS_ROWS_VISIBLE);
        if (maxScroll > 0) {
            int trackX = listX + (PANEL_W - 20) - SCROLLBAR_W;
            int trackY = listY;
            int trackH = STEPS_ROWS_VISIBLE * ROW_H;
            gg.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, 0x33000000);

            int thumbH = Math.max(10, (int) Math.round((double) trackH * (double) STEPS_ROWS_VISIBLE / (double) Math.max(STEPS_ROWS_VISIBLE, steps.size())));
            int thumbY = trackY + (int) Math.round((double) (trackH - thumbH) * ((double) scroll / (double) maxScroll));
            gg.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xAA888888);
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private void updateCaseButton() {
        try {
            if (caseBtn == null) return;
            caseBtn.setMessage(Component.literal("Case " + (caseSensitive ? "[x]" : "[]")));
        } catch (Throwable ignored) {}
    }

    private void updateChainButton() {
        try {
            if (chainBtn == null) return;
            chainBtn.setMessage(Component.literal("Chain " + (chain ? "[x]" : "[]")));
        } catch (Throwable ignored) {}
    }

    private void updateAnyoneButton() {
        try {
            if (anyoneBtn == null) return;
            anyoneBtn.setMessage(Component.literal("Anyone " + (anyone ? "[x]" : "[]")));
        } catch (Throwable ignored) {}
    }

    private void updateCombatOverrideButton() {
        try {
            if (combatOverrideBtn == null) return;
            combatOverrideBtn.setMessage(Component.literal("Combat " + (combatOverride ? "[x]" : "[]")));
        } catch (Throwable ignored) {}
    }

    private static int parseInt(String s, int fallback) {
        try {
            if (s == null) return fallback;
            String t = s.trim();
            if (t.isEmpty()) return fallback;
            return Integer.parseInt(t);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void onSave() {
        try {
            int timeout = parseInt(timeoutBox == null ? "" : timeoutBox.getValue(), 10);
            int retry = parseInt(retryBox == null ? "" : retryBox.getValue(), 10);
            int stop = parseInt(stopBox == null ? "" : stopBox.getValue(), 0);
            ClientNetwork.sendToServer(new PacketCcSaveTaughtAction(
                    villagerEntityId,
                    editIndex,
                    titleBox == null ? "" : titleBox.getValue(),
                    commandBox == null ? "" : commandBox.getValue(),
                    caseSensitive,
                    chain,
                    anyone,
                    combatOverride,
                    descBox == null ? "" : descBox.getValue(),
                    timeout,
                    retry,
                    stop
            ));
            if (this.minecraft != null) this.minecraft.setScreen(null);
        } catch (Throwable ignored) {}
    }
}
