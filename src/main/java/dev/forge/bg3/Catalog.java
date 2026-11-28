package dev.forge.bg3;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Catalog {
    public record Build(String id, String name, String role, String icon, String heroClass, String race,
                        String subclass, int level, String style, List<String> skills, int[] abilities, String summary,
                        List<String> milestones, List<String> items, String wiki, String guide, String guideName,
                        String note, String art) {
        public Build {
            abilities = PointBuy.recommended(abilities).totals();
        }

        Hero draft() {
            Hero hero = new Hero();
            hero.heroClass = heroClass;
            hero.race = race;
            hero.subclass = subclass;
            hero.level = level;
            hero.style = style;
            hero.skills = new ArrayList<>(skills);
            hero.abilities = abilities.clone();
            hero.buildId = id;
            Rules.normalizeChoices(hero);
            return hero;
        }
    }

    public final Map<String, Item> items;
    public final List<Build> builds;

    Catalog(ForgeDatabase database) {
        items = database.reference("items", Item.class);
        builds = List.copyOf(database.reference("builds", Build.class).values());
        Rules.useProgression(database.reference("progression", Rules.Progression.class));
    }

    Build build(String id) {
        return builds.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }
}
