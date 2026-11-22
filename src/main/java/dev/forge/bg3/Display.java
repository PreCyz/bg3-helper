package dev.forge.bg3;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component("fmt")
public class Display {
    public String sign(int i) {
        return i > 0 ? "+" + i : Integer.toString(i);
    }

    public String number(double n) {
        return n == (long) n ? Long.toString((long) n)
                : String.format(org.springframework.context.i18n.LocaleContextHolder.getLocale(), "%.1f", n);
    }

    public String delta(double n) {
        return (n > 0 ? "+" : "") + number(n);
    }

    public String icon(String category) {
        return Slot.representative(category).icon;
    }

    public String equipSlot(String category) {
        return Slot.representative(category).name();
    }

    public String slot(String category) {
        return category.equals("RING") ? "Ring" : Slot.representative(category).label;
    }

    public String rarity(String rarity) {
        return rarity == null ? "common" : rarity.toLowerCase(Locale.ROOT).replace(" ", "-");
    }
}
