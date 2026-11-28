package dev.forge.bg3;

import java.util.*;

public class Hero {
    public static final List<String> GENDERS = List.of("Gender unspecified", "Male", "Female", "Non-binary");
    public String id = UUID.randomUUID().toString();
    public String gender = GENDERS.getFirst();
    public String background = "";
    public String name = "", heroClass = "Ranger", race = "Wood Elf", subclass = "None",
            feat = "None", style = "None", skill = "None", buildId = "";
    public List<String> feats = new ArrayList<>();
    public List<String> skills = new ArrayList<>();
    public List<String> loadout = new ArrayList<>();
    public int level = 1;
    // In order of first taking each additional class. Older saves have only their starting class.
    public record ClassLevel(String heroClass, int level, String subclass, String style, List<String> skills) {
        public ClassLevel {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
        public List<String> availablePaths() { return Rules.subclasses(heroClass, level); }
        public List<String> availableStyles() { return Rules.styles(heroClass, subclass, level); }
        public List<String> availableSkills() { return Rules.skills(heroClass); }
    }
    public List<ClassLevel> otherClasses = new ArrayList<>();

    public int startingLevel() {
        int remaining = level;
        for (var entry : otherClasses) remaining -= entry.level;
        return remaining;
    }

    public int levelsIn(String career) {
        if (heroClass.equals(career)) return startingLevel();
        for (var entry : otherClasses) if (entry.heroClass.equals(career)) return entry.level;
        return 0;
    }

    public List<ClassLevel> classLevels() {
        var levels = new ArrayList<ClassLevel>();
        levels.add(new ClassLevel(heroClass, startingLevel(), subclass, style, skills));
        levels.addAll(otherClasses);
        return levels;
    }

    public String classSummary() {
        if (otherClasses.isEmpty()) return heroClass;
        return String.join(" / ", classLevels().stream().map(c -> c.heroClass + " " + c.level).toList());
    }
    public int[] abilities = {10, 16, 14, 10, 15, 10};
    public Map<String, String> gear = new LinkedHashMap<>();

    public Hero copy() {
        Hero h = new Hero();
        h.id = id;
        h.name = name;
        h.gender = gender;
        h.background = background;
        h.heroClass = heroClass;
        h.race = race;
        h.subclass = subclass;
        h.feat = feat;
        h.feats = new ArrayList<>(feats);
        h.style = style;
        h.skill = skill;
        h.skills = new ArrayList<>(skills);
        h.loadout = new ArrayList<>(loadout);
        h.level = level;
        h.otherClasses = new ArrayList<>(otherClasses);
        h.abilities = abilities.clone();
        h.gear = new LinkedHashMap<>(gear);
        h.buildId = buildId;
        return h;
    }
}
