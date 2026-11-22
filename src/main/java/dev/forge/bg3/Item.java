package dev.forge.bg3;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Item {
    public record Rider(int count, int die, String type) { }
    public record Acquisition(String act, String details, String source) { }

    public List<Acquisition> acquisition = List.of();
    public List<Rider> riders = List.of();
    public Map<String, Integer> saveBonuses = Map.of(), skillBonuses = Map.of();
    public int strBonus, dexBonus, conBonus, intBonus, wisBonus, chaBonus,
            strBonusCap, dexBonusCap, conBonusCap, intBonusCap, wisBonusCap, chaBonusCap,
            intFloor, wisFloor, chaFloor;
    public String id, name, polishName, slot, rarity, summary, source, armorType, weaponType, damageType, image;
    public int baseAc, dexCap, ac, attack, weaponDamage, rangedDamage, initiative, strFloor,
            dexFloor, conFloor, wisSave, save, spellDc, spellAttack, hpPerLevel,
            diceCount, die, enchant, versatileDie;
    public double movement;
    public boolean finesse, twoHanded, titan;

    boolean matchesCategory(String category) {
        return switch (category) {
            case "" -> true;
            case "CLOTHING" -> "CHEST".equals(slot) && "Clothing".equals(armorType);
            default -> category.equals(slot);
        };
    }

    boolean matchesName(String query) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(term)
                || polishName != null && polishName.toLowerCase(Locale.ROOT).contains(term)
                || GameText.polish(name).toLowerCase(Locale.ROOT).contains(term);
    }
}
