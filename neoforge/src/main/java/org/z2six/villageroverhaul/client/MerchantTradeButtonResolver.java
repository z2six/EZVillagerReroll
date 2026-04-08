package org.z2six.villageroverhaul.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.z2six.villageroverhaul.mixin.MerchantScreenAccessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MerchantTradeButtonResolver {

    private static final String TRADE_BUTTON_CLASS =
            "net.minecraft.client.gui.screens.inventory.MerchantScreen$TradeOfferButton";

    private MerchantTradeButtonResolver() {}

    public static List<TradeButtonRef> getTradeButtons(MerchantScreen screen) {
        ArrayList<TradeButtonRef> out = new ArrayList<>(7);
        try {
            if (screen == null) return out;

            ArrayList<AbstractWidget> fallback = new ArrayList<>();
            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof AbstractWidget widget)) continue;
                if (!TRADE_BUTTON_CLASS.equals(widget.getClass().getName())) continue;
                fallback.add(widget);
            }

            fallback.sort(Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
            for (int i = 0; i < fallback.size(); i++) {
                out.add(new TradeButtonRef(fallback.get(i), i));
            }

            return out;
        } catch (Throwable ignored) {
            return out;
        }
    }

    public static TradeButtonRef findHoveredTradeButton(MerchantScreen screen, double mouseX, double mouseY) {
        try {
            for (TradeButtonRef ref : getTradeButtons(screen)) {
                if (ref == null || ref.widget() == null) continue;
                if (!ref.widget().visible) continue;
                if (!ref.widget().isMouseOver(mouseX, mouseY)) continue;
                return ref;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static int getScrollOffset(MerchantScreen screen, int offerCount) {
        try {
            int maxScroll = Math.max(0, offerCount - 7);

            try {
                int raw = ((MerchantScreenAccessor) screen).ezvr$getScrollOff();
                return clamp(raw, 0, maxScroll);
            } catch (Throwable ignored) {}

            Class<?> c = screen.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n == null || !n.toLowerCase().contains("scroll")) continue;

                        f.setAccessible(true);
                        int v = f.getInt(screen);
                        if (v >= 0 && v <= maxScroll) return v;
                    } catch (Throwable ignoredField) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    public record TradeButtonRef(AbstractWidget widget, int rowIndex) {}
}
