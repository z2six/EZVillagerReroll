// MainFile: neoforge/src/main/java/org/z2six/ezvillagerreroll/client/SearchCatalogScreen.java
package org.z2six.ezvillagerreroll.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.z2six.ezvillagerreroll.EZVillagerReroll;
import org.z2six.ezvillagerreroll.network.PacketStartAutoSearch;

import java.util.ArrayList;
import java.util.List;

public final class SearchCatalogScreen extends Screen {

    private final MerchantScreen parent;
    private int villagerEntityId;

    private final List<ItemStack> catalog = new ArrayList<>();
    private final List<ItemStack> selected = new ArrayList<>();

    private EditBox searchBox;
    private Button btnCancel;
    private Button btnRequest;

    private boolean awaitingServerData = true;

    public SearchCatalogScreen(MerchantScreen parent) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = parent;
        this.villagerEntityId = -1;
    }

    public SearchCatalogScreen(int villagerEntityId, List<ItemStack> catalog) {
        super(Component.translatable("ezvr.catalog.title"));
        this.parent = null;
        this.villagerEntityId = villagerEntityId;
        if (catalog != null) this.catalog.addAll(catalog);
        this.awaitingServerData = false;
    }

    public void applyCatalogFromServer(int villagerEntityId, List<ItemStack> items) {
        try {
            this.villagerEntityId = villagerEntityId;
            this.catalog.clear();
            if (items != null) this.catalog.addAll(items);
            this.awaitingServerData = false;

            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen received catalog: villagerEntityId={} items={}",
                    villagerEntityId, this.catalog.size());
        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] applyCatalogFromServer failed", t);
        }
    }

    @Override
    protected void init() {
        try {
            super.init();

            int cx = this.width / 2;

            searchBox = new EditBox(this.font, cx - 110, 18, 220, 18, Component.translatable("ezvr.catalog.search"));
            searchBox.setMaxLength(64);
            addRenderableWidget(searchBox);

            btnCancel = Button.builder(Component.translatable("ezvr.catalog.cancel"), b -> {
                        tryClose();
                    })
                    .pos(cx - 110, this.height - 28)
                    .size(100, 20)
                    .build();

            btnRequest = Button.builder(Component.translatable("ezvr.catalog.request"), b -> {
                        try {
                            if (villagerEntityId < 0) {
                                EZVillagerReroll.LOG().warn("[EZVR] Request clicked but villagerEntityId not set yet; ignoring.");
                                return;
                            }

                            List<ItemStack> req = new ArrayList<>();
                            for (ItemStack s : selected) {
                                if (s == null || s.isEmpty()) continue;
                                req.add(s.copy());
                            }

                            ClientNetwork.sendToServer(new PacketStartAutoSearch(villagerEntityId, req));
                            EZVillagerReroll.LOG().info("[EZVR] SearchCatalogScreen: sent PacketStartAutoSearch villagerEntityId={} items={}",
                                    villagerEntityId, req.size());
                        } catch (Throwable t) {
                            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen: request send failed", t);
                        }

                        tryClose();
                        tryCloseMerchantIfOpen();
                    })
                    .pos(cx + 10, this.height - 28)
                    .size(100, 20)
                    .build();

            addRenderableWidget(btnCancel);
            addRenderableWidget(btnRequest);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.init failed", t);
        }
    }

    private void tryClose() {
        try {
            if (this.minecraft != null) this.minecraft.setScreen(null);
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.tryClose failed (soft): {}", t.toString());
        }
    }

    private void tryCloseMerchantIfOpen() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.player != null) mc.player.closeContainer();
        } catch (Throwable t) {
            EZVillagerReroll.LOG().debug("[EZVR] SearchCatalogScreen.tryCloseMerchantIfOpen failed (soft): {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        try {
            this.renderBackground(gg, mouseX, mouseY, partialTick);
            super.render(gg, mouseX, mouseY, partialTick);

            gg.drawCenteredString(this.font, Component.translatable("ezvr.catalog.header"), this.width / 2, 44, 0xFFFFFF);

            if (awaitingServerData) {
                gg.drawCenteredString(this.font, Component.literal("Loading…"), this.width / 2, 66, 0xB0B0B0);
                return;
            }

            gg.drawCenteredString(this.font,
                    Component.literal("Items available: " + catalog.size() + " (selection UI pending)"),
                    this.width / 2, 66, 0xB0B0B0);

        } catch (Throwable t) {
            EZVillagerReroll.LOG().error("[EZVR] SearchCatalogScreen.render failed", t);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
