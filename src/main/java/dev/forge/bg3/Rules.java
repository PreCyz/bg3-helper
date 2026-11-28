package dev.forge.bg3;


import java.util.*;
import java.util.function.ToIntFunction;

/** Unbuffed character sheet using the BG3 Wiki class tables and item properties. */
public final class Rules {
    private Rules() {
    }

    private static final String NONE = "None";
    private static final List<String> ABILITIES = List.of("STR", "DEX", "CON", "INT", "WIS", "CHA");
    private static final int STR = 0, DEX = 1, CON = 2, INT = 3, WIS = 4, CHA = 5;
    public record Race(String name, List<String> subraces) {
        private Race(String name, String... subraces) {
            this(name, List.of(subraces));
        }

        String ancestry(String subrace) {
            if (subraces.isEmpty() && (subrace == null || subrace.isEmpty())) return name;
            if (subrace != null && subraces.contains(subrace)) return subrace;
            throw new IllegalArgumentException(GameText.format("Choose a valid subrace for {0}.", GameText.text(name)));
        }
    }

    // Playable character creation options from https://bg3.wiki/wiki/Races.
    public static final List<Race> PLAYABLE_RACES = List.of(
            new Race("Human"), new Race("Elf", "High Elf", "Wood Elf"),
            new Race("Drow", "Lolth-Sworn Drow", "Seldarine Drow"),
            new Race("Half-Elf", "High Half-Elf", "Wood Half-Elf", "Drow Half-Elf"),
            new Race("Half-Orc"), new Race("Halfling", "Lightfoot Halfling", "Strongheart Halfling"),
            new Race("Dwarf", "Gold Dwarf", "Shield Dwarf", "Duergar"),
            new Race("Gnome", "Forest Gnome", "Deep Gnome", "Rock Gnome"),
            new Race("Tiefling", "Asmodeus Tiefling", "Mephistopheles Tiefling", "Zariel Tiefling"),
            new Race("Githyanki"),
            new Race("Dragonborn", "Black Dragonborn", "Blue Dragonborn", "Brass Dragonborn", "Bronze Dragonborn",
                    "Copper Dragonborn", "Gold Dragonborn", "Green Dragonborn", "Red Dragonborn", "Silver Dragonborn", "White Dragonborn"));

    static final List<String> ANCESTRIES = PLAYABLE_RACES.stream()
            .flatMap(r -> (r.subraces.isEmpty() ? List.of(r.name) : r.subraces).stream()).toList();

    private static final Map<String, Integer> SKILL_ABILITIES = new TreeMap<>();

    static {
        skillGroup(STR, "Athletics");
        skillGroup(DEX, "Acrobatics", "Sleight of Hand", "Stealth");
        skillGroup(INT, "Arcana", "History", "Investigation", "Nature", "Religion");
        skillGroup(WIS, "Animal Handling", "Insight", "Medicine", "Perception", "Survival");
        skillGroup(CHA, "Deception", "Intimidation", "Performance", "Persuasion");
    }

    private static void skillGroup(int ability, String... names) {
        for (String name : names) SKILL_ABILITIES.put(name, ability);
    }

    private record Career(int hitDie, int casting, int firstSave, int secondSave, int subclassLevel,
                          String paths, String classSkills) {
    }

    private static final Map<String, Career> CAREERS = new TreeMap<>(Map.ofEntries(
            Map.entry("Barbarian", new Career(12, CHA, STR, CON, 3, "Berserker|Giant|Wildheart|Wild Magic",
                    "Animal Handling|Athletics|Intimidation|Nature|Perception|Survival")),
            Map.entry("Bard", new Career(8, CHA, DEX, CHA, 3, "College of Glamour|College of Lore|College of Swords|College of Valour",
                    String.join("|", SKILL_ABILITIES.keySet()))),
            Map.entry("Cleric", new Career(8, WIS, WIS, CHA, 1, "Death Domain|Knowledge Domain|Life Domain|Light Domain|Nature Domain|Tempest Domain|Trickery Domain|War Domain",
                    "History|Insight|Medicine|Persuasion|Religion")),
            Map.entry("Druid", new Career(8, WIS, INT, WIS, 2, "Circle of the Land|Circle of the Moon|Circle of the Spores|Circle of the Stars",
                    "Animal Handling|Arcana|Insight|Medicine|Nature|Perception|Religion|Survival")),
            Map.entry("Fighter", new Career(10, INT, STR, CON, 3, "Arcane Archer|Battle Master|Champion|Eldritch Knight",
                    "Acrobatics|Animal Handling|Athletics|History|Insight|Intimidation|Perception|Survival")),
            Map.entry("Monk", new Career(8, WIS, STR, DEX, 3, "Way of the Drunken Master|Way of the Four Elements|Way of the Open Hand|Way of Shadow",
                    "Acrobatics|Athletics|History|Insight|Religion|Stealth")),
            Map.entry("Paladin", new Career(10, CHA, WIS, CHA, 1, "Oath of Devotion|Oath of the Ancients|Oath of the Crown|Oath of Vengeance|Oathbreaker",
                    "Athletics|Insight|Intimidation|Medicine|Persuasion|Religion")),
            Map.entry("Ranger", new Career(10, WIS, STR, DEX, 3, "Beast Master|Gloom Stalker|Hunter|Swarmkeeper",
                    "Animal Handling|Athletics|Insight|Investigation|Nature|Perception|Stealth|Survival")),
            Map.entry("Rogue", new Career(8, INT, DEX, INT, 3, "Arcane Trickster|Assassin|Swashbuckler|Thief",
                    "Acrobatics|Athletics|Deception|Insight|Intimidation|Investigation|Perception|Performance|Persuasion|Sleight of Hand|Stealth")),
            Map.entry("Sorcerer", new Career(6, CHA, CON, CHA, 1, "Draconic Bloodline|Shadow Magic|Storm Sorcery|Wild Magic",
                    "Arcana|Deception|Insight|Intimidation|Persuasion|Religion")),
            Map.entry("Warlock", new Career(8, CHA, WIS, CHA, 1, "The Archfey|The Fiend|The Great Old One|The Hexblade",
                    "Arcana|Deception|History|Intimidation|Investigation|Nature|Religion")),
            Map.entry("Wizard", new Career(6, INT, INT, WIS, 2, "Abjuration|Bladesinging|Conjuration|Divination|Enchantment|Evocation|Illusion|Necromancy|Transmutation",
                    "Arcana|History|Insight|Investigation|Medicine|Religion"))
    ));

    private static final Set<String> SIMPLE = Set.of("Club", "Dagger", "Greatclub", "Handaxe", "Javelin",
            "Light Hammer", "Mace", "Quarterstaff", "Sickle", "Spear", "Light Crossbow", "Shortbow", "Dart");
    private static final Set<String> MARTIAL_CLASSES = Set.of("Barbarian", "Fighter", "Paladin", "Ranger");
    private static final Set<String> MARTIAL_PATHS = Set.of("College of Valour", "War Domain", "Tempest Domain", "Death Domain", "The Hexblade");

    record Progression(String source, SortedMap<Integer, String> features) {
    }

    private static volatile Map<String, Progression> progressionData = Map.of();

    static void useProgression(Map<String, Progression> data) {
        progressionData = Map.copyOf(data);
    }

    private static String progressionKey(String heroClass, String path) {
        return "Wild Magic".equals(path) ? heroClass + "/" + path : path;
    }

    public static String classSource(String heroClass, String path) {
        Progression p = progressionData.get(progressionKey(heroClass, path));
        return p == null ? "https://bg3.wiki/wiki/" + heroClass : p.source;
    }

    public record Strike(String name, String damageType, String formula, int attack, int min, int max, double average) {
    }

    public record Spell(String name, String formula, String detail) {
    }

    public record Sheet(int ac, int hp, int initiative, int proficiency, int spellDc, int spellAttack,
                        int attacks, double movement, List<Integer> scores, List<Integer> modifiers,
                        List<Integer> saves, Map<String, Integer> skills, Strike melee, Strike ranged, Strike offhand,
                        List<String> traits, List<String> notes, List<Spell> spells) {
    }

    public static List<String> classes() {
        return List.copyOf(CAREERS.keySet());
    }

    static Race raceFor(String nameOrAncestry) {
        return PLAYABLE_RACES.stream().filter(r -> r.name.equals(nameOrAncestry) || r.subraces.contains(nameOrAncestry))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose a playable race."));
    }

    private static List<String> options(String pipeSeparated) {
        return List.of((NONE + "|" + pipeSeparated).split("\\|"));
    }

    public static List<String> subclasses(String heroClass, int level) {
        Career c = CAREERS.get(heroClass);
        return c != null && level >= c.subclassLevel ? options(c.paths) : List.of(NONE);
    }

    public static List<String> skills(String heroClass) {
        Career c = CAREERS.get(heroClass);
        return c == null ? List.of(NONE) : options(c.classSkills);
    }

    public static int skillSlots(String heroClass) {
        return switch (heroClass) {
            case "Rogue" -> 4;
            case "Ranger", "Bard" -> 3;
            default -> 2;
        };
    }

    // Background proficiencies from https://bg3.wiki/wiki/Backgrounds
    static final Map<String,List<String>> BACKGROUNDS = Collections.unmodifiableMap(new TreeMap<>(Map.ofEntries(
            Map.entry("Acolyte", List.of("Insight", "Religion")), Map.entry("Charlatan", List.of("Deception", "Sleight of Hand")),
            Map.entry("Criminal", List.of("Deception", "Stealth")), Map.entry("Entertainer", List.of("Acrobatics", "Performance")),
            Map.entry("Folk Hero", List.of("Animal Handling", "Survival")), Map.entry("Guild Artisan", List.of("Insight", "Persuasion")),
            Map.entry("Haunted One", List.of("Medicine", "Intimidation")), Map.entry("Noble", List.of("History", "Persuasion")),
            Map.entry("Outlander", List.of("Athletics", "Survival")), Map.entry("Sage", List.of("Arcana", "History")),
            Map.entry("Soldier", List.of("Athletics", "Intimidation")), Map.entry("Urchin", List.of("Sleight of Hand", "Stealth")))));

    static Set<String> proficientSkills(Hero h) {
        Set<String> result = new LinkedHashSet<>(chosenSkills(h));
        for (var c : h.otherClasses) result.addAll(c.skills());
        result.remove(NONE);
        result.addAll(BACKGROUNDS.getOrDefault(h.background, List.of()));
        if (elf(h.race) || drow(h.race)) result.add("Perception");
        if (h.race.startsWith("Wood ")) result.add("Stealth");
        if ("Half-Orc".equals(h.race)) result.add("Intimidation");
        return result;
    }

    private static List<String> chosenSkills(Hero h) {
        List<String> input = h.skills;
        if (input == null || input.isEmpty()) input = Collections.singletonList(h.skill);
        return chosenSkills(input, h.heroClass, skillSlots(h.heroClass), new HashSet<>());
    }

    public static int additionalSkillSlots(String career) {
        return switch (career) {
            case "Bard", "Ranger", "Rogue" -> 1;
            case "Cleric" -> 2;
            default -> 0;
        };
    }

    private static List<String> chosenSkills(List<String> input, String career, int slots, Set<String> used) {
        List<String> available = skills(career);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            String selected = i < input.size() ? choice(available, input.get(i)) : NONE;
            result.add(used.add(selected) ? selected : NONE);
        }
        return result;
    }

    public static List<String> styles(String heroClass, String subclass, int level) {
        if ("Fighter".equals(heroClass))
            return options("Archery|Defence|Duelling|Great Weapon Fighting|Protection|Two-Weapon Fighting");
        if ("Ranger".equals(heroClass) && level >= 2) return options("Archery|Defence|Duelling|Two-Weapon Fighting");
        if ("Paladin".equals(heroClass) && level >= 2)
            return options("Defence|Duelling|Great Weapon Fighting|Protection");
        if ("Bard".equals(heroClass) && level >= 3 && "College of Swords".equals(subclass))
            return options("Duelling|Two-Weapon Fighting");
        return List.of(NONE);
    }

    private static final String ASI = "Ability Improvement · ";
    private static final Map<String, int[]> IMPROVEMENTS = new LinkedHashMap<>();

    static {
        for (int i = 0; i < ABILITIES.size(); i++) {
            int[] both = new int[6];
            both[i] = 2;
            IMPROVEMENTS.put(ASI + ABILITIES.get(i) + " +2", both);
            for (int j = i + 1; j < ABILITIES.size(); j++) {
                int[] split = new int[6];
                split[i] = split[j] = 1;
                IMPROVEMENTS.put(ASI + ABILITIES.get(i) + " / " + ABILITIES.get(j) + " +1", split);
            }
        }
    }

    public static int featSlots(String heroClass, int level) {
        int standard = Math.min(level, 12) / 4;
        return standard + (("Fighter".equals(heroClass) && level >= 6) || ("Rogue".equals(heroClass) && level >= 10) ? 1 : 0);
    }

    public static List<String> feats(String heroClass, int level) {
        if (featSlots(heroClass, level) == 0) return List.of(NONE);
        return featChoices();
    }

    public static int featSlots(Hero h) {
        int slots = featSlots(h.heroClass, h.startingLevel());
        for (var entry : h.otherClasses) slots += featSlots(entry.heroClass(), entry.level());
        return slots;
    }

    public static List<String> featChoices() {
        var choices = new ArrayList<>(options("Actor|Alert|Dual Wielder|Great Weapon Master|Mobile|Sharpshooter|Tavern Brawler · STR|Tavern Brawler · CON|Tough"));
        choices.addAll(IMPROVEMENTS.keySet());
        return List.copyOf(choices);
    }

    private static String choice(List<String> choices, String chosen) {
        return chosen != null && choices.contains(chosen) ? chosen : NONE;
    }

    public static void normalizeChoices(Hero hero) {
        hero.subclass = choice(subclasses(hero.heroClass, hero.startingLevel()), hero.subclass);
        hero.style = choice(styles(hero.heroClass, hero.subclass, hero.startingLevel()), hero.style);
        hero.skills = chosenSkills(hero);
        Set<String> used = new HashSet<>(hero.skills);
        hero.otherClasses.replaceAll(c -> {
            var path = choice(subclasses(c.heroClass(), c.level()), c.subclass());
            var style = choice(styles(c.heroClass(), path, c.level()), c.style());
            var skills = chosenSkills(c.skills(), c.heroClass(), additionalSkillSlots(c.heroClass()), used);
            return new Hero.ClassLevel(c.heroClass(), c.level(), path, style, skills);
        });
        hero.skill = hero.skills.getFirst();
        hero.feats = chosenFeats(hero);
        hero.feat = hero.feats.isEmpty() ? NONE : hero.feats.getFirst();
    }

    private static List<String> chosenFeats(Hero hero) {
        int slots = featSlots(hero);
        if (slots == 0) return List.of();
        List<String> input = hero.feats;
        if (input == null || input.isEmpty()) input = Collections.singletonList(hero.feat);
        List<String> available = featChoices();
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < slots; i++) {
            String selected = i < input.size() ? choice(available, input.get(i)) : NONE;
            String key = selected.startsWith("Tavern Brawler") ? "Tavern Brawler" : selected;
            result.add(selected.startsWith(ASI) || used.add(key) ? selected : NONE);
        }
        return result;
    }

    private static int bonus(List<Item> items, ToIntFunction<Item> property) {
        return items.stream().mapToInt(property).sum();
    }

    private static boolean wears(List<Item> items, String id) {
        return items.stream().anyMatch(item -> id.equals(item.id));
    }

    private static final Set<String> LIGHT = Set.of("Club", "Dagger", "Handaxe", "Hand Crossbow",
            "Light Hammer", "Scimitar", "Shortsword", "Sickle");

    public static boolean lightWeapon(Item item) {
        return item != null && item.weaponType != null && LIGHT.contains(item.weaponType) && !item.twoHanded;
    }

    /**
     * Checks the two-weapon melee loadout. Shields have a separate slot compatibility rule.
     */
    public static boolean canDualWield(Hero hero, Item main, Item off) {
        if (main == null || off == null || !"MELEE".equals(main.slot) || !"MELEE".equals(off.slot)
                || main.twoHanded || off.twoHanded) return false;
        return (lightWeapon(main) && lightWeapon(off)) || chosenFeats(hero).contains("Dual Wielder");
    }

    static int[] unbuffedScores(Hero hero) {
        return scores(hero, chosenFeats(hero), List.of());
    }

    private static int[] scores(Hero h, List<String> feats, List<Item> items) {
        int[] scores = h.abilities.clone();
        for (String feat : feats) {
            int[] increase = IMPROVEMENTS.get(feat);
            if (increase != null) {
                for (int i = 0; i < scores.length; i++) scores[i] += Math.clamp(20 - scores[i], 0, increase[i]);
            } else {
                int ability = switch (feat) {
                    case "Actor" -> CHA;
                    case "Tavern Brawler · STR" -> STR;
                    case "Tavern Brawler · CON" -> CON;
                    default -> -1;
                };
                if (ability >= 0 && scores[ability] < 20) scores[ability]++;
            }
        }
        // Lower capped increases apply before higher capped increases, independent of slot order.
        for (int i = 0; i < scores.length; i++) {
            TreeMap<Integer, Integer> capped = new TreeMap<>();
            int floor = 0;
            for (Item item : items) {
                int amount = switch (i) {
                    case STR -> item.strBonus;
                    case DEX -> item.dexBonus;
                    case CON -> item.conBonus;
                    case INT -> item.intBonus;
                    case WIS -> item.wisBonus;
                    default -> item.chaBonus;
                };
                int cap = switch (i) {
                    case STR -> item.strBonusCap;
                    case DEX -> item.dexBonusCap;
                    case CON -> item.conBonusCap;
                    case INT -> item.intBonusCap;
                    case WIS -> item.wisBonusCap;
                    default -> item.chaBonusCap;
                };
                int fixed = switch (i) {
                    case STR -> item.strFloor;
                    case DEX -> item.dexFloor;
                    case CON -> item.conFloor;
                    case INT -> item.intFloor;
                    case WIS -> item.wisFloor;
                    default -> item.chaFloor;
                };
                if (amount != 0) capped.merge(cap == 0 ? 20 : cap, amount, Integer::sum);
                floor = Math.max(floor, fixed);
            }
            for (var entry : capped.entrySet())
                scores[i] += Math.min(entry.getValue(), Math.max(0, entry.getKey() - scores[i]));
            scores[i] = Math.max(floor, scores[i]);
        }
        return scores;
    }

    private static boolean elf(String race) {
        return race.equals("High Elf") || race.equals("Wood Elf");
    }

    private static boolean drow(String race) {
        return race.endsWith(" Drow");
    }

    private static boolean dwarf(String race) {
        return race.endsWith("Dwarf") || race.equals("Duergar");
    }

    private static boolean proficient(Hero h, String path, String weapon) {
        if (weapon == null || weapon.isBlank()) return false;
        if (MARTIAL_CLASSES.contains(h.heroClass) || MARTIAL_PATHS.contains(path)) return true;
        for (var c : h.otherClasses) {
            if (MARTIAL_CLASSES.contains(c.heroClass()) || MARTIAL_PATHS.contains(c.subclass())) return true;
            if (switch (c.heroClass()) {
                case "Cleric" -> Set.of("Flail", "Morningstar").contains(weapon);
                case "Monk" -> SIMPLE.contains(weapon) || weapon.equals("Shortsword");
                case "Warlock" -> SIMPLE.contains(weapon);
                default -> false;
            }) return true;
            if (c.subclass().equals("College of Swords") && weapon.equals("Scimitar")
                    || c.subclass().equals("Bladesinging") && Set.of("Longsword", "Rapier", "Scimitar", "Shortsword", "Sickle").contains(weapon)) return true;
        }
        if (elf(h.race) && Set.of("Longbow", "Shortbow", "Longsword", "Shortsword").contains(weapon)) return true;
        if (drow(h.race) && Set.of("Rapier", "Shortsword", "Hand Crossbow").contains(weapon)) return true;
        if (dwarf(h.race) && Set.of("Battleaxe", "Handaxe", "Light Hammer", "Warhammer").contains(weapon)) return true;
        if (h.race.equals("Githyanki") && Set.of("Greatsword", "Longsword", "Shortsword").contains(weapon)) return true;
        if ((h.race.equals("Human") || h.race.endsWith("Half-Elf"))
                && Set.of("Spear", "Pike", "Glaive", "Halberd").contains(weapon)) return true;
        return switch (h.heroClass) {
            case "Bard", "Rogue" ->
                    SIMPLE.contains(weapon) || Set.of("Hand Crossbow", "Rapier", "Shortsword", "Longsword").contains(weapon)
                            || path.equals("College of Swords") && weapon.equals("Scimitar");
            case "Monk" -> SIMPLE.contains(weapon) || weapon.equals("Shortsword");
            case "Cleric" -> SIMPLE.contains(weapon) || weapon.equals("Flail") || weapon.equals("Morningstar");
            case "Warlock" -> SIMPLE.contains(weapon);
            case "Druid" ->
                    Set.of("Club", "Dagger", "Javelin", "Mace", "Quarterstaff", "Scimitar", "Sickle", "Spear").contains(weapon);
            case "Wizard", "Sorcerer" -> Set.of("Dagger", "Light Crossbow", "Quarterstaff").contains(weapon)
                    || path.equals("Bladesinging") && Set.of("Longsword", "Rapier", "Scimitar", "Shortsword", "Sickle").contains(weapon);
            default -> false;
        };
    }

    private static boolean armourProficient(Hero h, String path, String type) {
        if ("Clothing".equals(type) || type == null) return true;
        for (var c : h.otherClasses) {
            boolean medium = Set.of("Cleric", "Druid", "Fighter", "Paladin", "Ranger").contains(c.heroClass());
            boolean extraMedium = Set.of("College of Swords", "College of Valour", "The Hexblade").contains(c.subclass());
            if (switch (type) {
                case "Heavy" -> Set.of("Life Domain", "Nature Domain", "Tempest Domain", "War Domain").contains(c.subclass());
                case "Medium" -> medium || extraMedium;
                case "Light" -> medium || extraMedium || Set.of("Bard", "Rogue", "Warlock").contains(c.heroClass()) || c.subclass().equals("Bladesinging");
                case "Shield" -> medium || c.heroClass().equals("Barbarian") || Set.of("College of Valour", "The Hexblade").contains(c.subclass());
                default -> false;
            }) return true;
        }
        boolean civil = h.race.equals("Human") || h.race.endsWith("Half-Elf");
        boolean military = h.heroClass.equals("Fighter") || h.heroClass.equals("Paladin");
        boolean medium = military || Set.of("Barbarian", "Cleric", "Druid", "Ranger").contains(h.heroClass);
        boolean extraMedium = h.race.equals("Shield Dwarf") || h.race.equals("Githyanki")
                || Set.of("The Hexblade", "College of Swords", "College of Valour").contains(path);
        return switch (type) {
            case "Heavy" ->
                    military || Set.of("Life Domain", "Nature Domain", "Tempest Domain", "War Domain").contains(path);
            case "Medium" -> medium || extraMedium;
            case "Light" ->
                    medium || extraMedium || civil || Set.of("Bard", "Rogue", "Warlock").contains(h.heroClass) || path.equals("Bladesinging");
            case "Shield" -> medium || civil || path.equals("College of Valour") || path.equals("The Hexblade");
            default -> true;
        };
    }

    static boolean trainedForArmour(Hero h, String path, Item item) {
        return "helldusk-armour".equals(item.id) || "elven-chain".equals(item.id)
                || armourProficient(h, path, "OFFHAND".equals(item.slot) ? "Shield" : item.armorType);
    }

    static boolean trainedForItem(Hero h, Item item) {
        return trainedForArmour(h, h.subclass, item) && (item.weaponType == null || proficient(h, h.subclass, item.weaponType)
                || "gloves-of-archery".equals(h.gear.get("HANDS")) && Set.of("Longbow", "Shortbow").contains(item.weaponType));
    }

    private static int armourClass(Hero h, Item chest, boolean armored, boolean shield, String path, String style,
                                   int[] mod, List<Item> items) {
        int base = chest == null || chest.baseAc == 0 ? 10 : chest.baseAc;
        int dex = chest == null ? mod[DEX] : switch (Objects.toString(chest.armorType, "")) {
            case "Heavy" -> 0;
            case "Medium" -> Math.min(chest.dexCap == 0 ? 2 : chest.dexCap, mod[DEX]);
            default -> mod[DEX];
        };
        if (!armored) {
            if (hasPath(h, path, "Draconic Bloodline")) base = Math.max(base, 13);
            if (h.levelsIn("Barbarian") > 0) base = Math.max(base, 10 + mod[CON]);
            if (h.levelsIn("Monk") > 0 && !shield) base = Math.max(base, 10 + mod[WIS]);
        }
        int ac = base + dex + bonus(items, i -> i.ac) + (armored && hasStyle(h, style, "Defence") ? 1 : 0);
        if (!armored && !shield && wears(items, "bracers-of-defence")) ac += 2;
        return ac;
    }

    private static double movement(Hero h, boolean armored, boolean shield, List<String> feats, List<Item> items) {
        double speed = 9;
        if (dwarf(h.race) || h.race.endsWith("Gnome") || h.race.endsWith("Halfling")) speed = 7.5;
        if (h.race.startsWith("Wood ")) speed = 10.5;
        if (feats.contains("Mobile")) speed += 3;
        if (h.levelsIn("Barbarian") >= 5 && items.stream().noneMatch(i -> "Heavy".equals(i.armorType)))
            speed += 3;
        if (h.levelsIn("Monk") >= 2 && !armored && !shield)
            speed += h.levelsIn("Monk") >= 10 ? 6 : h.levelsIn("Monk") >= 6 ? 4.5 : 3;
        return speed + items.stream().mapToDouble(i -> i.movement).sum();
    }

    private static int attackCount(List<Hero.ClassLevel> classes) {
        int attacks = 1;
        for (var c : classes) {
            if (c.heroClass().equals("Fighter") && c.level() >= 11) return 3;
            if (c.level() >= 5 && (MARTIAL_CLASSES.contains(c.heroClass()) || c.heroClass().equals("Monk"))) attacks = 2;
            if (c.level() >= 6 && Set.of("College of Swords", "College of Valour", "Bladesinging").contains(c.subclass())) attacks = 2;
        }
        return attacks;
    }

    private static boolean hasPath(Hero h, String primary, String wanted) {
        if (wanted.equals(primary)) return true;
        for (var c : h.otherClasses) if (wanted.equals(c.subclass())) return true;
        return false;
    }

    private static boolean hasStyle(Hero h, String primary, String wanted) {
        if (wanted.equals(primary)) return true;
        for (var c : h.otherClasses) if (wanted.equals(c.style())) return true;
        return false;
    }

    private static Map<String, Integer> skillBonuses(Hero h, String path, List<String> feats, int[] mod,
                                                     int prof, List<Item> items) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> selected = proficientSkills(h);
        for (var entry : SKILL_ABILITIES.entrySet()) {
            String skill = entry.getKey();
            int ability = entry.getValue();
            boolean trained = selected.contains(skill) || skill.equals("Performance") && hasPath(h, path, "Bladesinging");
            boolean expertise = feats.contains("Actor") && (skill.equals("Deception") || skill.equals("Performance"))
                    || h.race.equals("Rock Gnome") && skill.equals("History");
            int extra = trained ? prof : 0;
            if (expertise) extra = 2 * prof;
            if (extra == 0 && h.levelsIn("Bard") >= 2) extra = prof / 2;
            if (extra == 0 && hasPath(h, path, "Champion") && h.levelsIn("Fighter") >= 7 && ability <= CON) extra = prof / 2;
            int gear = bonus(items, i -> i.skillBonuses.getOrDefault(skill, 0));
            result.put(skill, mod[ability] + extra + gear);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Item equipped(Hero h, Map<String, Item> catalog, String slot) {
        String id = h.gear.get(slot);
        return id == null ? null : catalog.get(id);
    }

    private static String add(int amount) {
        return amount > 0 ? " + " + amount : amount < 0 ? " − " + -amount : "";
    }

    private static double meanDie(int die, boolean reroll) {
        double mean = (die + 1) / 2.0;
        return reroll && die > 2 ? mean + (2 * mean - 3) / die : mean;
    }

    private enum AttackHand {MELEE, RANGED, OFFHAND}

    private static Strike strike(Hero h, Item weapon, AttackHand hand, Item offhand, String path, String style,
                                 List<String> feats, List<Item> items, int[] mod, int prof, List<String> notes) {
        boolean ranged = hand == AttackHand.RANGED;
        if (ranged && weapon == null) return new Strike("No ranged weapon", "", "—", 0, 0, 0, 0);
        boolean unarmed = weapon == null;
        boolean trained = unarmed || proficient(h, path, weapon.weaponType);
        if (!trained && ranged && ("Longbow".equals(weapon.weaponType) || "Shortbow".equals(weapon.weaponType)))
            trained = items.stream().anyMatch(i -> "gloves-of-archery".equals(i.id));
        boolean monkWeapon = h.levelsIn("Monk") > 0 && !ranged && (unarmed || trained && !weapon.twoHanded);
        boolean bothHands = !unarmed && !ranged && (weapon.twoHanded || offhand == null && weapon.versatileDie > 0);
        int ability = ranged ? mod[DEX] : mod[STR];
        if (monkWeapon || !unarmed && weapon.finesse) ability = Math.max(ability, mod[DEX]);
        int attack = ability + (trained ? prof : 0) + bonus(items, i -> i.attack);
        boolean offhandUsesAbility = hasStyle(h, style, "Two-Weapon Fighting") || wears(items, "gloves-of-the-balanced-hands");
        int flat = hand == AttackHand.OFFHAND && !offhandUsesAbility ? 0 : ability;
        int count = unarmed ? 1 : Math.max(1, weapon.diceCount);
        int die = unarmed ? 1 : Math.max(1, weapon.die);
        if (bothHands) die = Math.max(die, weapon.versatileDie);
        if (monkWeapon && count == 1) die = Math.max(die, h.levelsIn("Monk") >= 9 ? 8 : h.levelsIn("Monk") >= 3 ? 6 : 4);
        if (!unarmed) {
            attack += weapon.enchant;
            flat += weapon.enchant + bonus(items, i -> "caustic-band".equals(i.id) ? 0 : i.weaponDamage);
            if (weapon.titan) flat += Math.max(1, mod[STR]);
            if ("baldurans-giantslayer".equals(weapon.id)) flat += mod[STR];
            if ("the-dead-shot".equals(weapon.id)) attack += prof;
            if (wears(items, "legacy-of-the-masters")) attack += 2;
        } else if (feats.stream().anyMatch(f -> f.startsWith("Tavern Brawler"))) {
            attack += mod[STR];
            flat += mod[STR];
        }
        if (ranged) {
            if (hasStyle(h, style, "Archery")) attack += 2;
            flat += bonus(items, i -> i.rangedDamage);
        } else if (!unarmed && !bothHands && (offhand == null || "OFFHAND".equals(offhand.slot)) && hasStyle(h, style, "Duelling")) {
            flat += 2;
        }
        if (trained && (ranged && feats.contains("Sharpshooter") || bothHands && feats.contains("Great Weapon Master"))) {
            attack -= 5;
            flat += 10;
            notes.add(GameText.format("{0} assumes All In is enabled (−5 to hit, +10 damage).",
                    GameText.text(ranged ? "Sharpshooter" : "Great Weapon Master")));
        }
        if (!trained) notes.add(GameText.format("{0} has no weapon proficiency bonus for this hero.", GameText.text(weapon.name)));

        boolean reroll = bothHands && hasStyle(h, style, "Great Weapon Fighting");
        int min = Math.max(0, count + flat), max = Math.max(0, count * die + flat);
        double mean = meanDamage(count, die, flat, reroll);
        StringBuilder formula = new StringBuilder(die == 1 ? String.valueOf(count) : count + "d" + die).append(add(flat));
        List<Item.Rider> riders = new ArrayList<>();
        if (!unarmed) for (Item item : items) {
            if ("caustic-band".equals(item.id) && item.weaponDamage > 0)
                riders.add(new Item.Rider(item.weaponDamage, 1, "Acid"));
        }
        if (unarmed && wears(items, "helldusk-gloves")) riders.add(new Item.Rider(1, 6, "Necrotic"));
        if (unarmed && wears(items, "flawed-helldusk-gloves")) riders.add(new Item.Rider(1, 4, "Necrotic"));
        if (!unarmed && weapon.riders != null) riders.addAll(weapon.riders);
        if (!unarmed) for (Item item : items) {
            if (!"MELEE".equals(item.slot) && !"RANGED".equals(item.slot) && !"OFFHAND".equals(item.slot)
                    && item.riders != null) riders.addAll(item.riders);
        }
        if (!unarmed && !ranged && h.levelsIn("Paladin") >= 11)
            riders.add(new Item.Rider(1, 8, "Radiant"));
        Set<String> types = new LinkedHashSet<>();
        types.add(unarmed ? "Bludgeoning" : Objects.toString(weapon.damageType, "Weapon"));
        for (Item.Rider rider : riders) {
            min += rider.count();
            max += rider.count() * rider.die();
            mean += rider.count() * meanDie(rider.die(), reroll);
            formula.append(" + ").append(rider.count());
            if (rider.die() > 1) formula.append('d').append(rider.die());
            formula.append(' ').append(GameText.text(rider.type()));
            if (!"weapon damage".equalsIgnoreCase(rider.type())) types.add(rider.type());
        }
        return new Strike(unarmed ? "Unarmed strike" : weapon.name, String.join(" + ", types.stream().map(GameText::text).toList()),
                formula.toString(), attack, min, max, Math.round(mean * 100) / 100.0);
    }

    // A negative ability modifier can bring a low roll below zero. Each such outcome deals zero damage.
    private static double meanDamage(int count, int die, int flat, boolean reroll) {
        if (count + flat >= 0) return count * meanDie(die, reroll) + flat;
        double[] probabilities = {1};
        for (int n = 0; n < count; n++) {
            double[] next = new double[probabilities.length + die];
            for (int total = 0; total < probabilities.length; total++) {
                for (int face = 1; face <= die; face++) {
                    double chance = 1.0 / die;
                    if (reroll && die > 2) chance = (face <= 2 ? 0 : chance) + 2.0 / (die * die);
                    next[total + face] += probabilities[total] * chance;
                }
            }
            probabilities = next;
        }
        double mean = 0;
        for (int i = 0; i < probabilities.length; i++) mean += probabilities[i] * Math.max(0, flat + i);
        return mean;
    }

    public static Sheet stats(Hero h, Map<String, Item> catalog) {
        Career career = Objects.requireNonNull(CAREERS.get(h.heroClass), "Choose a class.");
        String path = choice(subclasses(h.heroClass, h.startingLevel()), h.subclass);
        String style = choice(styles(h.heroClass, path, h.startingLevel()), h.style);
        var classes = new ArrayList<Hero.ClassLevel>();
        classes.add(new Hero.ClassLevel(h.heroClass, h.startingLevel(), path, style, h.skills));
        classes.addAll(h.otherClasses);
        List<String> feats = chosenFeats(h);
        List<Item> equipped = h.gear.values().stream().filter(Objects::nonNull)
                .map(catalog::get).filter(Objects::nonNull).toList();
        int[] scores = scores(h, feats, equipped);
        int[] mods = Arrays.stream(scores).map(score -> Math.floorDiv(score - 10, 2)).toArray();
        int prof = 2 + (h.level - 1) / 4;
        Item chest = equipped(h, catalog, "CHEST");
        Item main = equipped(h, catalog, "MELEE");
        Item offhand = equipped(h, catalog, "OFFHAND");
        boolean shield = offhand != null && "OFFHAND".equals(offhand.slot);
        boolean dual = canDualWield(h, main, offhand);
        boolean armored = equipped.stream().anyMatch(i -> i.armorType != null && !i.armorType.equals("Clothing"));
        int ac = armourClass(h, chest, armored, shield, path, style, mods, equipped);
        if (dual && feats.contains("Dual Wielder")) ac++;
        if (wears(equipped, "elixir-of-barkskin")) ac = Math.max(ac, 16);
        int hpBonus = mods[CON] + bonus(equipped, i -> i.hpPerLevel);
        if (h.race.equals("Gold Dwarf")) hpBonus++;
        if (feats.contains("Tough")) hpBonus += 2;
        int hp = 0;
        for (var c : classes) {
            int die = CAREERS.get(c.heroClass()).hitDie;
            int perLevel = hpBonus + ("Draconic Bloodline".equals(c.subclass()) ? 1 : 0);
            boolean starting = c.heroClass().equals(h.heroClass);
            if (starting) hp += Math.max(1, die + perLevel);
            hp += (c.level() - (starting ? 1 : 0)) * Math.max(1, die / 2 + 1 + perLevel);
        }
        int initiative = mods[DEX] + bonus(equipped, i -> i.initiative);
        if (feats.contains("Alert")) initiative += 5;
        if (hasPath(h, path, "Gloom Stalker")) initiative += 3;
        if (h.levelsIn("Barbarian") >= 7) initiative += 3;
        if (hasPath(h, path, "Swashbuckler")) initiative += h.levelsIn("Rogue") >= 10 ? 4 : h.levelsIn("Rogue") >= 5 ? 3 : 2;
        String itemClass = h.otherClasses.isEmpty() ? h.heroClass : h.otherClasses.getLast().heroClass();
        int casting = mods[CAREERS.get(itemClass).casting] + prof;
        int spellDc = 8 + casting + bonus(equipped, i -> i.spellDc);
        int spellAttack = casting + bonus(equipped, i -> i.spellAttack + i.attack);
        List<String> notes = new ArrayList<>();
        notes.add("Damage ranges and averages are per successful ordinary hit, before resistance, critical hits and encounter-specific effects.");
        notes.add("Class progression uses Balanced difficulty. Spell examples use their listed slot level and assume the spell is available.");
        notes.add("The sheet applies modeled equipment bonuses. The equipped effects list includes conditional and special abilities for reference.");
        List<String> traits = new ArrayList<>(racialTraits(h.race));
        Item elixir = equipped(h, catalog, "ELIXIR");
        if (elixir != null && elixir.summary != null) traits.add(GameText.text(elixir.name) + " · " + GameText.text(elixir.summary));
        for (var c : classes) addFeatures(c, traits);
        if (!h.otherClasses.isEmpty()) notes.add(GameText.format("Item spell DC and spell attack use {0}. Class spell examples use their own casting ability.", GameText.text(itemClass)));
        int saveBonus = bonus(equipped, i -> i.save);
        if (h.levelsIn("Paladin") >= 6) {
            saveBonus += Math.max(0, mods[CHA]);
            traits.add("Aura of Protection is active for these saving throws.");
        }
        List<Integer> saves = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean trained = i == career.firstSave || i == career.secondSave
                    || hasPath(h, path, "Gloom Stalker") && h.levelsIn("Ranger") >= 7 && (i == INT || i == WIS);
            String ability = ABILITIES.get(i);
            int equipmentSave = bonus(equipped, it -> it.saveBonuses.getOrDefault(ability, 0));
            if (i == WIS) equipmentSave += bonus(equipped, it -> it.wisSave);
            saves.add(mods[i] + saveBonus + (trained ? prof : 0) + equipmentSave);
        }
        Strike melee = strike(h, main, AttackHand.MELEE, offhand, path, style, feats, equipped, mods, prof, notes);
        Strike ranged = strike(h, equipped(h, catalog, "RANGED"), AttackHand.RANGED, offhand, path, style, feats, equipped, mods, prof, notes);
        Strike off = dual ? strike(h, offhand, AttackHand.OFFHAND, main, path, style, feats, equipped, mods, prof, notes) : null;
        List<String> untrained = equipped.stream().filter(i -> !trainedForArmour(h, path, i))
                .map(i -> i.name).toList();
        if (!untrained.isEmpty())
            notes.add(GameText.format("Missing armour proficiency for {0}. Casting is blocked and attacks, saves and ability checks have disadvantage.",
                    String.join(", ", untrained.stream().map(GameText::text).toList())));
        List<Spell> spells = new ArrayList<>();
        if (untrained.isEmpty()) {
            for (var c : classes) {
                int adjustment = mods[CAREERS.get(c.heroClass()).casting] + prof - casting;
                spells.addAll(examples(c, h.level, mods, spellDc + adjustment, spellAttack + adjustment, equipped));
            }
        }
        return new Sheet(ac, hp, initiative, prof, spellDc, spellAttack, attackCount(classes), movement(h, armored, shield, feats, equipped),
                Arrays.stream(scores).boxed().toList(), Arrays.stream(mods).boxed().toList(), List.copyOf(saves),
                skillBonuses(h, path, feats, mods, prof, equipped), melee, ranged, off, List.copyOf(traits), List.copyOf(notes), spells);
    }

    private static List<String> racialTraits(String race) {
        if (elf(race)) return List.of("Fey Ancestry · advantage against charm and immunity to magical sleep.",
                race.equals("Wood Elf") ? "Wood Elf · 10.5 m movement and Stealth proficiency." : "High Elf · one Wizard cantrip using Intelligence.");
        String drowMagic = "Drow Magic · Dancing Lights at level 1, Faerie Fire at level 3, and Darkness at level 5.";
        if (race.endsWith("Half-Elf")) return List.of("Civil Militia · light armour, shields and polearm training.",
                "Fey Ancestry · advantage against charm and immunity to magical sleep.", "Darkvision · 12 m.",
                switch (race) {
                    case "Drow Half-Elf" -> drowMagic;
                    case "Wood Half-Elf" -> "Fleet of Foot · 10.5 m movement. Proficient in Stealth.";
                    default -> "High Half-Elf · one Wizard cantrip using Intelligence.";
                });
        if (drow(race))
            return List.of("Superior Darkvision · 24 m.", "Drow Weapon Training · rapiers, shortswords and hand crossbows.",
                    "Fey Ancestry · advantage against charm and immunity to magical sleep.", drowMagic);
        if (dwarf(race)) return List.of("Dwarven Resilience · poison resistance and advantage on saves against poison.",
                switch (race) {
                    case "Gold Dwarf" -> "Dwarven Toughness · +1 maximum HP per level.";
                    case "Shield Dwarf" -> "Dwarven Armour Training · light and medium armour.";
                    default -> "Duergar Resilience · advantage against paralysis, illusions and charm.";
                });
        if (race.endsWith("Gnome"))
            return List.of("Gnome Cunning · advantage on Intelligence, Wisdom and Charisma saves.");
        if (race.endsWith("Halfling"))
            return List.of("Halfling Luck · reroll natural ones on attacks, checks and saves once.", "Brave · advantage against fear.");
        if (race.endsWith("Tiefling"))
            return List.of("Hellish Resistance · fire damage resistance.", "Darkvision · 12 m.");
        if (race.endsWith("Dragonborn")) {
            String element = switch (race.split(" ")[0]) {
                case "Black", "Copper" -> "acid";
                case "Blue", "Bronze" -> "lightning";
                case "Green" -> "poison";
                case "White", "Silver" -> "cold";
                default -> "fire";
            };
            return List.of("Draconic Ancestry · " + element + " resistance and a matching breath weapon.");
        }
        return switch (race) {
            case "Half-Orc" -> List.of("Relentless Endurance · survive a lethal hit at 1 HP once per long rest.",
                    "Savage Attacks · additional weapon die on melee critical hits.");
            case "Githyanki" -> List.of("Martial Prodigy · light and medium armour plus short, long and greatswords.");
            case "Human" ->
                    List.of("Civil Militia · light armour, shields and polearm training.", "Human Versatility · one additional skill proficiency.");
            default -> List.of();
        };
    }

    private static void addFeatures(Hero.ClassLevel c, List<String> traits) {
        String path = c.subclass();
        Progression progression = progressionData.get(progressionKey(c.heroClass(), path));
        if (progression != null) {
            List<String> milestones = progression.features.entrySet().stream()
                    .filter(entry -> entry.getKey() <= c.level())
                    .map(entry -> GameText.format("L{0} {1}", entry.getKey(), GameText.text(entry.getValue()))).toList();
            if (!milestones.isEmpty()) traits.add(GameText.text(path) + " · " + String.join(" / ", milestones));
        }
        switch (c.heroClass()) {
            case "Barbarian" ->
                    traits.add("Rage adds " + (c.level() >= 9 ? 3 : 2) + " melee damage while active. The sheet shows ordinary hits.");
            case "Bard" -> {
                traits.add("Bardic Inspiration die · d" + (c.level() >= 10 ? 10 : c.level() >= 5 ? 8 : 6) + ".");
                if (c.level() >= 2) traits.add("Jack of All Trades adds half proficiency to untrained skills.");
            }
            case "Druid" -> {
                if (c.level() >= 2)
                    traits.add("Wild Shape uses the chosen form's physical statistics. This sheet shows humanoid form.");
            }
            case "Fighter" -> {
                if (c.level() >= 2) traits.add("Action Surge grants one additional action per short rest.");
            }
            case "Monk" -> traits.add("Martial Arts die · d" + (c.level() >= 9 ? 8 : c.level() >= 3 ? 6 : 4) + ".");
            case "Paladin" -> {
                if (c.level() >= 2)
                    traits.add("Divine Smite · a first-level slot adds 2d8 radiant damage to a melee weapon hit.");
            }
            case "Ranger" -> {
                if (c.level() >= 2)
                    traits.add("Hunter’s Mark can add 1d6 weapon damage against the marked target while concentrating.");
            }
            case "Rogue" ->
                    traits.add("Sneak Attack adds " + ((c.level() + 1) / 2) + "d6 once per turn with an eligible finesse or ranged attack.");
            case "Sorcerer" -> {
                if (c.level() >= 2) traits.add("Sorcery Points · " + c.level() + " per long rest.");
            }
            case "Warlock" -> traits.add("Pact Magic slots recover on a short rest.");
            case "Wizard" -> traits.add("Arcane Recovery · " + ((c.level() + 1) / 2) + " charges per long rest.");
            default -> {
            }
        }
        switch (path) {
            case "Gloom Stalker" ->
                    traits.add("Dread Ambusher · +3 initiative, +3 m on turn one, and a first-turn attack with +1d8 damage.");
            case "College of Swords" ->
                    traits.add("Blade Flourish spends Bardic Inspiration to modify a weapon attack.");
            case "Draconic Bloodline" ->
                    traits.add("Draconic Resilience · base AC 13 without armour and +1 HP per level.");
            case "Light Domain" -> traits.add("Warding Flare can impose disadvantage on an incoming attack.");
            case "Champion" -> traits.add("Improved Critical Hit lowers the roll required for a critical hit by one.");
            case "Thief" -> traits.add("Fast Hands adds a second bonus action each turn.");
            case "Assassin" ->
                    traits.add("Assassinate grants advantage against foes who have yet to act, and critical hits against surprised foes.");
            case "The Hexblade" ->
                    traits.add("Bind Hexed Weapon can switch a bound weapon to Charisma. The sheet assumes an unbound weapon.");
            case "Bladesinging" ->
                    traits.add("Bladesong can raise AC and movement while active. The sheet shows resting AC.");
            default -> {
            }
        }
    }

    private static List<Spell> examples(Hero.ClassLevel c, int totalLevel, int[] mod, int dc, int attack, List<Item> equipped) {
        String path = c.subclass();
        int scale = totalLevel >= 10 ? 3 : totalLevel >= 5 ? 2 : 1;
        int cantrip = wears(equipped, "potent-robe") ? mod[CHA] : 0;
        int elemental = wears(equipped, "necklace-of-elemental-augmentation") ? mod[CAREERS.get(c.heroClass()).casting] : 0;
        String hit = GameText.format("Attack roll {0}.", String.format(Locale.ROOT, "%+d", attack));
        List<Spell> spells = new ArrayList<>();
        if (c.heroClass().equals("Wizard") || c.heroClass().equals("Sorcerer") || path.equals("Eldritch Knight") || path.equals("Arcane Trickster")) {
            int evocation = path.equals("Evocation School") && c.level() >= 10 ? mod[INT] : 0;
            spells.add(new Spell("Fire Bolt", scale + "d10" + add(evocation + cantrip + elemental) + " " + GameText.text("fire"),
                    hit + " " + GameText.text("Cantrip.")));
        }
        if (c.heroClass().equals("Bard"))
            spells.add(new Spell("Vicious Mockery", scale + "d4" + add(cantrip) + " " + GameText.text("psychic"),
                    GameText.format("WIS save DC {0}. Failed save also impairs the target’s next attack.", dc)));
        if (c.heroClass().equals("Cleric")) {
            boolean potent = c.level() >= 8 && Set.of("Knowledge Domain", "Light Domain").contains(path);
            spells.add(new Spell("Sacred Flame", scale + "d8" + add(cantrip + (potent ? mod[WIS] : 0)) + " " + GameText.text("radiant"),
                    GameText.format("DEX save DC {0} negates.", dc)));
            spells.add(new Spell("Guiding Bolt", "4d6 radiant", hit + " " + GameText.text("First-level slot.")));
            if (c.level() >= 2 && path.equals("Light Domain"))
                spells.add(new Spell("Radiance of the Dawn", "2d10 + " + c.level() + " " + GameText.text("radiant"),
                        GameText.format("Channel Divinity. CON save DC {0} halves damage.", dc)));
            if (c.level() >= 5)
                spells.add(new Spell("Spirit Guardians", "3d8 radiant or necrotic",
                        GameText.format("Third-level slot, per affected foe. WIS save DC {0} halves damage.", dc)));
        }
        if (c.heroClass().equals("Druid"))
            spells.add(new Spell("Produce Flame", scale + "d8" + add(cantrip + elemental) + " " + GameText.text("fire"),
                    hit + " " + GameText.text("Hurl the conjured flame.")));
        if (c.heroClass().equals("Warlock"))
            spells.add(new Spell("Eldritch Blast", scale + " " + GameText.text(scale == 1 ? "beam" : "beams")
                    + " × (1d10" + add(cantrip) + " " + GameText.text("force") + ")", hit + " " + GameText.text("Roll once per beam.")));
        return List.copyOf(spells);
    }
}
