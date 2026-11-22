package dev.forge.bg3;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class Catalog {
    public record Build(String id, String name, String role, String icon, String heroClass, String race,
                        String subclass, int level, String style, List<String> skills, int[] abilities, String summary,
                        List<String> milestones, List<String> items, String wiki, String guide, String guideName,
                        String note, String art) {
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

    public Catalog(ObjectMapper mapper) throws IOException {
        Map<String, Item> catalog = new LinkedHashMap<>();
        try (var stream = new ClassPathResource("data/items.json").getInputStream()) {
            for (Item i : mapper.readValue(stream, Item[].class)) {
                if (catalog.putIfAbsent(i.id, i) != null) throw new IOException("Duplicate catalog ID " + i.id);
            }
        }
        items = Collections.unmodifiableMap(catalog);
        try (var stream = new ClassPathResource("data/builds.json").getInputStream()) {
            builds = List.of(mapper.readValue(stream, Build[].class));
        }
    }

    Build build(String id) {
        return builds.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }
}
