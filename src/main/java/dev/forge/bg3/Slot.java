package dev.forge.bg3;

public enum Slot {
    HEAD("Head", "helm"), CLOAK("Cloak", "cloak"), CHEST("Armour", "armour"),
    HANDS("Gloves", "glove"), FEET("Boots", "boot"), NECK("Amulet", "amulet"),
    RING1("Ring I", "ring"), RING2("Ring II", "ring"), MELEE("Main hand", "sword"),
    OFFHAND("Off hand", "shield"), RANGED("Ranged", "bow"), ELIXIR("Elixir", "elixir");

    public final String label, icon;

    Slot(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public boolean accepts(Item i) {
        return switch (this) {
            case RING1, RING2 -> i.slot.equals("RING");
            case OFFHAND -> i.slot.equals("OFFHAND") || i.slot.equals("MELEE") && !i.twoHanded;
            default -> i.slot.equals(name());
        };
    }

    public static Slot representative(String category) {
        return category.equals("RING") ? RING1 : valueOf(category);
    }
}
