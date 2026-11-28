package dev.forge.bg3;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;

@Component
public class BuildAdvisor {
    public record Group(int level, String kind, List<String> picks) {
        boolean requiresPath() {
            return kind.startsWith("subclass") || kind.equals("discipline") || kind.equals("manoeuvre");
        }
        public boolean selectable() { return !kind.equals("action") && !kind.equals("subclassAction"); }
    }
    public record Profile(List<String> abilities, List<String> skills, Map<Integer,String> feats, List<Group> groups) {}
    private static final Profile EMPTY = new Profile(List.of(), List.of(), Map.of(), List.of());
    public record Plan(String id, String name, Hero target, List<String> items, Profile profile, Catalog.Build guide) {}
    public record Match(Plan plan, int classLevel, boolean pathMatches, int skills, int gear, int coreScores, int fit) {}
    public record Score(String ability, int actual, int target, boolean core) {}
    public record Step(String career, int level, String description) {}
    public record FeatGoal(int level, String feat, boolean taken) {}
    public record AbilityGoal(String ability, int actual, int target) {}
    public record GearPick(Slot slot, Item item, boolean goal) {}
    public record Advice(Match selected, List<Match> ranked, List<Score> scores, Set<String> trained,
                         List<String> skillGoals, List<Group> available, List<Group> upcoming,
                         Step current, Step next, int goalLevel, List<FeatGoal> feats, List<AbilityGoal> increases) {
        public List<Match> bestFits() { return ranked.stream().limit(3).toList(); }
        public PointBuy startingAllocation() { return PointBuy.recommended(selected.plan.target.abilities); }
    }

    private final Catalog catalog;
    private final Map<String, Profile> profiles;
    private final MessageSource messages;

    BuildAdvisor(Catalog catalog, ObjectMapper json, MessageSource messages) throws IOException {
        this.catalog = catalog;
        this.messages = messages;
        try (var input = new ClassPathResource("data/build-advice.json").getInputStream()) {
            profiles = json.readValue(input, new TypeReference<>() {});
        }
    }

    private List<Plan> plans(List<PartyStore.PersonalBuild> personal) {
        List<Plan> plans = new ArrayList<>();
        for (var build : catalog.builds) {
            var profile = profiles.getOrDefault(build.id(), EMPTY);
            plans.add(new Plan(build.id(), GameText.text(build.name()), build.draft(), build.items(), profile, build));
        }
        for (int i = 0; i < personal.size(); i++) {
            var build = personal.get(i);
            Hero hero = build.hero();
            String name = messages.getMessage("format.personal.build", new Object[]{i + 1, hero.name, hero.level},
                    LocaleContextHolder.getLocale());
            plans.add(new Plan(build.id(), name, hero, List.copyOf(hero.gear.values()),
                    EMPTY, null));
        }
        return plans;
    }

    private static List<String> skillGoals(Plan plan) {
        Set<String> goals = new LinkedHashSet<>(plan.profile.skills);
        if (plan.guide != null) goals.addAll(plan.guide.skills());
        else goals.addAll(Rules.proficientSkills(plan.target));
        return List.copyOf(goals);
    }

    private static Match match(Hero hero, Set<String> trained, Plan plan) {
        Hero target = plan.target;
        int levels = hero.levelsIn(target.heroClass);
        boolean pathMatches = hero.classLevels().stream().anyMatch(c -> c.heroClass().equals(target.heroClass)
                && !"None".equals(c.subclass()) && c.subclass().equals(target.subclass));
        List<String> goals = skillGoals(plan);
        int skills = (int) goals.stream().filter(trained::contains).count();
        var equipped = new ArrayList<>(hero.gear.values());
        int gear = (int) plan.items.stream().filter(equipped::remove).count();
        // Prefer an existing class and subclass. Within them, compare the distance to the build's setup.
        int fit = (levels > 0 ? 100 : 0) + 20 * levels / hero.level
                + (pathMatches ? 35 : 0) + (target.race.equals(hero.race) ? 4 : 0);
        int coreScores = 0;
        for (int i = 0; i < target.abilities.length; i++) {
            boolean core = plan.profile.abilities.contains(ForgeController.ABILITIES.get(i));
            if (core && hero.abilities[i] >= target.abilities[i]) coreScores++;
            int gap = core ? Math.max(0, target.abilities[i] - hero.abilities[i]) : Math.abs(target.abilities[i] - hero.abilities[i]);
            fit -= (core ? 3 : 1) * Math.min(8, gap);
        }
        fit += 12 * skills / Math.max(1, goals.size()) + 16 * gear / Math.max(1, plan.items.size());
        if (!"None".equals(target.style) && hero.classLevels().stream()
                .anyMatch(c -> c.heroClass().equals(target.heroClass) && target.style.equals(c.style()))) fit += 4;
        var taken = new ArrayList<>(hero.feats.isEmpty() ? List.of(hero.feat) : hero.feats);
        var wantedFeats = plan.guide == null ? target.feats : plan.profile.feats.entrySet().stream()
                .filter(e -> e.getKey() <= levels).map(Map.Entry::getValue).toList();
        for (String feat : wantedFeats) if (!"None".equals(feat) && taken.remove(feat)) fit += 3;
        Set<String> picks = new HashSet<>(target.loadout);
        for (Group group : plan.profile.groups) if (group.selectable() && group.level <= levels) picks.addAll(group.picks);
        if (!picks.isEmpty()) fit += 8 * (int) picks.stream().filter(hero.loadout::contains).count() / picks.size();
        for (var c : target.otherClasses) if (hero.levelsIn(c.heroClass()) > 0) fit += 4;
        return new Match(plan, levels, pathMatches, skills, gear, coreScores, fit);
    }

    public Advice recommend(Hero hero, List<PartyStore.PersonalBuild> personal, String selectedId) {
        var trained = Rules.proficientSkills(hero);
        var ranked = plans(personal).stream().map(p -> match(hero, trained, p))
                .sorted(Comparator.comparingInt(Match::fit).reversed()).toList();
        var selected = ranked.stream().filter(m -> m.plan.id.equals(selectedId)).findFirst().orElse(ranked.getFirst());
        Plan plan = selected.plan;
        int level = selected.classLevel;
        var scores = new ArrayList<Score>();
        for (int i = 0; i < hero.abilities.length; i++) {
            String ability = ForgeController.ABILITIES.get(i);
            scores.add(new Score(ability, hero.abilities[i], plan.target.abilities[i], plan.profile.abilities.contains(ability)));
        }
        Step current = null, next = null;
        if (plan.guide != null) {
            var steps = plan.guide.milestones();
            if (level > 0 && level <= steps.size()) current = new Step(plan.target.heroClass, level, steps.get(level - 1));
            if (hero.level < 12 && level < steps.size()) next = new Step(plan.target.heroClass, level + 1, steps.get(level));
        } else if (hero.level < 12) {
            for (var c : plan.target.classLevels()) {
                int taken = hero.levelsIn(c.heroClass());
                if (taken < c.level()) {
                    next = new Step(c.heroClass(), taken + 1, "");
                    break;
                }
            }
        }
        var available = new ArrayList<>(ancestryPicks(hero));
        plan.profile.groups.stream().filter(g -> g.level <= level && (!g.requiresPath() || selected.pathMatches))
                .forEach(available::add);
        boolean otherPath = hero.classLevels().stream().anyMatch(c -> c.heroClass().equals(plan.target.heroClass)
                && !"None".equals(c.subclass()) && !plan.target.subclass.equals(c.subclass()));
        var upcoming = plan.profile.groups.stream().filter(g -> hero.level < 12 && g.level == level + 1)
                .filter(g -> !g.requiresPath() || !otherPath).toList();
        int goalLevel = next == null ? level : next.level;
        var feats = new ArrayList<FeatGoal>();
        var increases = new ArrayList<AbilityGoal>();
        if (plan.guide != null) {
            Hero projected = plan.target.copy();
            projected.level = goalLevel;
            projected.feats.clear();
            List<String> taken = new ArrayList<>(hero.feats.isEmpty() ? List.of(hero.feat) : hero.feats);
            for (var entry : new TreeMap<>(plan.profile.feats).entrySet()) {
                if (entry.getKey() > goalLevel) break;
                projected.feats.add(entry.getValue());
                feats.add(new FeatGoal(entry.getKey(), entry.getValue(), taken.remove(entry.getValue())));
            }
            int[] targets = Rules.unbuffedScores(projected);
            int[] currentScores = Rules.unbuffedScores(hero);
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] > projected.abilities[i])
                    increases.add(new AbilityGoal(ForgeController.ABILITIES.get(i), currentScores[i], targets[i]));
            }
        }
        return new Advice(selected, ranked, scores, trained, skillGoals(plan), available, upcoming,
                current, next, goalLevel, feats, increases);
    }

    public List<String> loadoutOptions(Hero hero) {
        Set<String> options = new LinkedHashSet<>();
        ancestryPicks(hero).forEach(g -> options.addAll(g.picks));
        for (var build : catalog.builds) {
            var profile = profiles.get(build.id());
            if (profile == null) continue;
            for (var c : hero.classLevels()) {
                if (!c.heroClass().equals(build.heroClass())) continue;
                for (var group : profile.groups)
                    if (group.selectable() && group.level <= c.level() && (!group.requiresPath() || c.subclass().equals(build.subclass())))
                        options.addAll(group.picks);
            }
        }
        return List.copyOf(options);
    }

    private static List<Group> ancestryPicks(Hero hero) {
        if ("High Elf".equals(hero.race) || "High Half-Elf".equals(hero.race))
            return List.of(new Group(1, "ancestry", List.of("Mage Hand")));
        return List.of();
    }

    public List<GearPick> fillEmptySlots(PartyStore.Party party, Hero hero, Plan plan) {
        var preview = hero.copy();
        Map<String,Integer> free = new HashMap<>();
        for (String id : party.stock.keySet()) {
            int copies = party.available(id);
            if (copies > 0 && catalog.items.containsKey(id)) free.put(id, copies);
        }
        if (free.isEmpty()) return List.of();
        var result = new ArrayList<GearPick>();
        for (Slot slot : Slot.values()) {
            if (preview.gear.containsKey(slot.name())) continue;
            Item best = null;
            boolean bestGoal = false;
            double emptyScore = equipmentScore(Rules.stats(preview, catalog.items), plan);
            double bestScore = emptyScore;
            for (var entry : free.entrySet()) {
                Item item = catalog.items.get(entry.getKey());
                if (entry.getValue() <= 0 || !slot.accepts(item) || !Rules.trainedForItem(preview, item)
                        || !PartyStore.conflict(preview, slot, item, catalog.items).isEmpty()) continue;
                boolean goal = plan.items.contains(item.id);
                if (bestGoal && !goal) continue;
                preview.gear.put(slot.name(), item.id);
                double score = equipmentScore(Rules.stats(preview, catalog.items), plan);
                preview.gear.remove(slot.name());
                if (!goal && score <= emptyScore) continue;
                if (best == null || (goal && !bestGoal) || score > bestScore || (score == bestScore && item.id.compareTo(best.id) < 0)) {
                    best = item;
                    bestGoal = goal;
                    bestScore = score;
                }
            }
            if (best != null) {
                result.add(new GearPick(slot, best, bestGoal));
                preview.gear.put(slot.name(), best.id);
                free.merge(best.id, -1, Integer::sum);
            }
        }
        return result;
    }

    private static double equipmentScore(Rules.Sheet stats, Plan plan) {
        String career = plan.target.heroClass;
        boolean ranged = career.equals("Ranger") || "mystic-archer".equals(plan.id) ||
                (plan.guide == null && plan.target.gear.containsKey("RANGED"));
        var attack = ranged ? stats.ranged() : stats.melee();
        boolean caster = Set.of("Bard", "Sorcerer", "Wizard", "Cleric", "Warlock", "Druid").contains(career);
        return stats.ac() + .15 * stats.hp() + .3 * stats.initiative() + (caster ? 2 * stats.spellDc() : 0)
                + (caster && !ranged ? .25 : 1) * (attack.attack() + attack.average());
    }
}
