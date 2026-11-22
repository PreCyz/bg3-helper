package dev.forge.bg3;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component("avatarArt")
public class AvatarArt {
    public record Portrait(String figure, String face, String emblem, String backdrop, String description,
                           String specialization, String race, String career) {}

    public Portrait forHero(Hero hero) {
        String race = Rules.ANCESTRIES.contains(hero.race) ? hero.race : "Wood Elf";
        String career = Rules.classes().contains(hero.heroClass) ? hero.heroClass : "Ranger";
        String path = hero.subclass != null && Rules.subclasses(career, hero.startingLevel()).contains(hero.subclass)
                ? hero.subclass : "None";
        String specialization = "None".equals(path) ? career : path;
        String source = Rules.classSource(career, path);
        String badge = slug(source.substring(source.lastIndexOf('/') + 1));
        String variant = switch (hero.gender) {
            case "Male" -> "male/";
            case "Female" -> "female/";
            case null, default -> "";
        };
        String character = variant + slug(race) + "/" + slug(career) + ".webp";
        String figures = "Female".equals(hero.gender) && "Seldarine Drow".equals(race) ? "figures-v2" : "figures";
        String description = race + " " + career + ("None".equals(path) ? "" : " · " + path);
        return new Portrait("/art/avatars/" + figures + "/" + character, "/art/avatars/portraits/" + character,
                "/art/avatars/emblems/" + badge + ".webp",
                "None".equals(path) ? null : "/art/avatars/scenes/" + badge + ".webp", description, specialization, race, career);
    }

    private static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("-$", "");
    }
}
