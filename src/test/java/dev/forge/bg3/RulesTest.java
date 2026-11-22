package dev.forge.bg3;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    private static Item item(String id, String slot) {
        Item item = new Item();
        item.slot = slot;
        item.name = item.id = id;
        return item;
    }

    private static Map<String, Item> equip(Hero h, Item... items) {
        Map<String, Item> catalog = new HashMap<>();
        for (Item item : items) {
            catalog.put(item.id, item);
            h.gear.put(item.slot, item.id);
        }
        return catalog;
    }

    @Test
    void rangerBowUsesDexterityToHitAndStrengthElixirForTitanDamage() {
        Hero h = new Hero();
        h.level = 5;
        h.subclass = "Gloom Stalker";
        h.style = "Archery";
        Item bow = item("titanstring-bow", "RANGED");
        bow.weaponType = "Longbow";
        bow.diceCount = 1;
        bow.die = 8;
        bow.enchant = 1;
        bow.titan = true;
        Item elixir = item("elixir-of-hill-giant-strength", "ELIXIR");
        elixir.strFloor = 21;
        var catalog = equip(h, bow, elixir);
        Rules.Sheet sheet = Rules.stats(h, catalog);
        assertEquals(9, sheet.ranged().attack());
        assertEquals(10, sheet.ranged().min());
        assertEquals(17, sheet.ranged().max());
        assertEquals(13.5, sheet.ranged().average());
        assertEquals(6, sheet.initiative());
        assertEquals(10.5, sheet.movement());
        assertEquals(2, sheet.attacks());
        assertEquals(44, sheet.hp());
        assertArrayEquals(new int[]{10, 16, 14, 10, 15, 10}, h.abilities);
    }

    @Test
    void heavyArmourIgnoresEvenNegativeDexterityAndBlocksWizardCasting() {
        Hero h = new Hero();
        h.heroClass = "Wizard";
        h.abilities[1] = 8;
        Item armour = item("plate", "CHEST");
        armour.armorType = "Heavy";
        armour.baseAc = 18;
        var sheet = Rules.stats(h, equip(h, armour));
        assertEquals(18, sheet.ac());
        assertTrue(sheet.spells().isEmpty());
        assertTrue(sheet.notes().stream().anyMatch(n -> n.contains("Casting is blocked")));
    }

    @Test
    void featSlotsRetainEarlierChoicesAndApplyCappedIncreasesBeforeGearFloors() {
        Hero h = new Hero();
        h.heroClass = "Fighter";
        h.level = 8;
        h.abilities[1] = 17;
        h.feats = List.of("Ability Improvement · DEX +2", "Ability Improvement · DEX +2", "Alert", "Mobile");
        Item gloves = item("gloves", "HANDS");
        gloves.dexFloor = 18;
        var catalog = equip(h, gloves);
        Rules.normalizeChoices(h);
        assertEquals(3, h.feats.size());
        var sheet = Rules.stats(h, catalog);
        assertEquals(20, sheet.scores().get(1));
        assertEquals(10, sheet.initiative());
        h.level = 2;
        Rules.normalizeChoices(h);
        assertTrue(h.feats.isEmpty());
        assertEquals(18, Rules.stats(h, catalog).scores().get(1));
    }

    @Test
    void lightClericUsesGoldDwarfHpAndUnlocksPotentSpellcastingAtEight() {
        Hero h = new Hero();
        h.heroClass = "Cleric";
        h.race = "Gold Dwarf";
        h.level = 8;
        h.subclass = "Light Domain";
        h.abilities[4] = 18;
        var sheet = Rules.stats(h, Map.of());
        assertEquals(67, sheet.hp());
        assertEquals(15, sheet.spellDc());
        assertTrue(sheet.spells().stream().anyMatch(s -> s.formula().equals("2d8 + 4 radiant")));
        assertTrue(sheet.spells().stream().anyMatch(s -> s.name().equals("Spirit Guardians")));
        assertEquals(7.5, sheet.movement());
    }

    @Test
    void activeWeaponAndSharedGloveRidersCountOnce() {
        Hero h = new Hero();
        Item dagger = item("dagger", "MELEE");
        dagger.weaponType = "Dagger";
        dagger.diceCount = 1;
        dagger.die = 4;
        dagger.finesse = true;
        dagger.riders = List.of(new Item.Rider(1, 4, "Cold"));
        Item bow = item("bow", "RANGED");
        bow.weaponType = "Longbow";
        bow.diceCount = 1;
        bow.die = 8;
        bow.riders = List.of(new Item.Rider(1, 8, "Lightning"));
        Item gloves = item("burning-gloves", "HANDS");
        gloves.riders = List.of(new Item.Rider(1, 4, "Fire"));
        var sheet = Rules.stats(h, equip(h, dagger, bow, gloves));
        assertEquals(6, sheet.melee().min());
        assertEquals(15, sheet.melee().max());
        assertEquals(10.5, sheet.melee().average());
        assertEquals(23, sheet.ranged().max());
    }

    @Test
    void monkArmourAndShieldChangeWisdomDefenceAndMovement() {
        Hero h = new Hero();
        h.heroClass = "Monk";
        h.level = 3;
        h.race = "Human";
        var before = Rules.stats(h, Map.of());
        assertEquals(15, before.ac());
        assertEquals(12, before.movement());
        assertEquals(9, before.melee().max());
        Item shield = item("shield", "OFFHAND");
        shield.ac = 2;
        var after = Rules.stats(h, equip(h, shield));
        assertEquals(15, after.ac());
        assertEquals(9, after.movement());
    }

    @Test
    void eachSelectableSubclassHasAnOfflineMilestoneAndWikiSource() {
        Hero h = new Hero();
        h.level = 12;
        int count = 0;
        for (String career : Rules.classes()) {
            h.heroClass = career;
            for (String subclass : Rules.subclasses(career, h.level)) {
                if (subclass.equals("None")) continue;
                h.subclass = subclass;
                assertTrue(Rules.stats(h, Map.of()).traits().stream().anyMatch(s -> s.startsWith(subclass + " · L")), subclass);
                assertTrue(Rules.classSource(career, subclass).startsWith("https://bg3.wiki/wiki/"));
                count++;
            }
        }
        assertEquals(58, count);
    }

    @Test
    void racialSkillAndClassTrainingStackOnlyOnceAndLegacySavesKeepTheirSkill() {
        Hero h = new Hero();
        h.skill = "Survival";
        Rules.normalizeChoices(h);
        assertEquals(List.of("Survival", "None", "None"), h.skills);
        h.skills = List.of("Stealth", "Survival", "Survival", "Investigation");
        Rules.normalizeChoices(h);
        assertEquals(List.of("Stealth", "Survival", "None"), h.skills);
        var sheet = Rules.stats(h, Map.of());
        assertEquals(5, sheet.skills().get("Stealth"));
        assertEquals(4, sheet.skills().get("Survival"));
        assertEquals(4, sheet.skills().get("Perception"));
        assertEquals(0, sheet.skills().get("Investigation"));
    }

    @Test
    void barkskinSetsAMinimumAndBracersRequireUnarmouredWithoutShield() {
        Hero h = new Hero();
        Item barkskin = item("elixir-of-barkskin", "ELIXIR");
        Item bracers = item("bracers-of-defence", "HANDS");
        var catalog = equip(h, barkskin, bracers);
        assertEquals(16, Rules.stats(h, catalog).ac());
        h.gear.remove("ELIXIR");
        assertEquals(15, Rules.stats(h, catalog).ac());
        Item shield = item("shield", "OFFHAND");
        shield.ac = 2;
        catalog.putAll(equip(h, shield));
        assertEquals(15, Rules.stats(h, catalog).ac());
        h.gear.put("ELIXIR", barkskin.id);
        h.abilities[1] = 20;
        assertEquals(17, Rules.stats(h, catalog).ac());
    }

    @Test
    void equipmentTargetsIndividualChecksAndSavesInAdditionToGeneralSavingBonus() {
        Hero h = new Hero();
        Item boots = item("boots", "FEET");
        boots.skillBonuses = Map.of("Athletics", 1);
        Item helm = item("helm", "HEAD");
        helm.saveBonuses = Map.of("CON", 1);
        Item cloak = item("cloak", "CLOAK");
        cloak.save = 1;
        var sheet = Rules.stats(h, equip(h, boots, helm, cloak));
        assertEquals(1, sheet.skills().get("Athletics"));
        assertEquals(4, sheet.saves().get(2));
        assertEquals(3, sheet.saves().get(4));
    }

    @Test
    void offhandRequiresCompatiblePairAndUsesDamageAbilityOnlyWithFightingStyle() {
        Hero h = new Hero();
        h.level = 4;
        Item sword = item("sword", "MELEE");
        sword.weaponType = "Shortsword";
        sword.die = 6;
        sword.diceCount = 1;
        sword.finesse = true;
        Item dagger = item("dagger", "MELEE");
        dagger.weaponType = "Dagger";
        dagger.die = 4;
        dagger.diceCount = 1;
        dagger.finesse = true;
        Map<String, Item> catalog = Map.of("sword", sword, "dagger", dagger);
        h.gear.put("MELEE", "sword");
        h.gear.put("OFFHAND", "dagger");
        assertTrue(Rules.canDualWield(h, sword, dagger));
        assertFalse(Rules.canDualWield(h, null, dagger));
        assertEquals(4, Rules.stats(h, catalog).offhand().max());
        h.style = "Two-Weapon Fighting";
        assertEquals(7, Rules.stats(h, catalog).offhand().max());
        sword.weaponType = "Longsword";
        assertFalse(Rules.canDualWield(h, sword, dagger));
        h.feats = List.of("Dual Wielder");
        assertTrue(Rules.canDualWield(h, sword, dagger));
        assertEquals(14, Rules.stats(h, catalog).ac());
        sword.twoHanded = true;
        assertNull(Rules.stats(h, catalog).offhand());
    }
}
