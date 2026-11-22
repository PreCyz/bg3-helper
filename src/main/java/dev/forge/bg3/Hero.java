package dev.forge.bg3;

import java.util.*;

public class Hero {
    public static final List<String> GENDERS = List.of("Gender unspecified", "Male", "Female", "Non-binary");
    public String id = UUID.randomUUID().toString();
    public String gender = GENDERS.getFirst();
    public String name = "", heroClass = "Ranger", race = "Wood Elf", subclass = "None",
            feat = "None", style = "None", skill = "None", buildId = "";
    public List<String> feats = new ArrayList<>();
    public List<String> skills = new ArrayList<>();
    public int level = 1;
    public int[] abilities = {10, 16, 14, 10, 15, 10};
    public Map<String, String> gear = new LinkedHashMap<>();

    public Hero copy() {
        Hero h = new Hero();
        h.id = id;
        h.name = name;
        h.gender = gender;
        h.heroClass = heroClass;
        h.race = race;
        h.subclass = subclass;
        h.feat = feat;
        h.feats = new ArrayList<>(feats);
        h.style = style;
        h.skill = skill;
        h.skills = new ArrayList<>(skills);
        h.level = level;
        h.abilities = abilities.clone();
        h.gear = new LinkedHashMap<>(gear);
        h.buildId = buildId;
        return h;
    }
}
