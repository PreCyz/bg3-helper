package dev.forge.bg3;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.*;

@Controller
@RequestScope
@RequestMapping({"", "/demo"})
public class ForgeController {
    private final Catalog catalog;
    private final EquipmentSynergies synergies;
    private final BuildAdvisor advisor;
    private PartyStore.Campaigns campaigns;
    private final PartyStore store;
    private final String basePath;
    static final List<String> ABILITIES = List.of("Strength", "Dexterity", "Constitution", "Intelligence", "Wisdom", "Charisma");
    static final List<String> SHORT = List.of("STR", "DEX", "CON", "INT", "WIS", "CHA");
    static final List<String> ABILITY_ICONS = List.of("strength", "bow", "heart", "book", "eye", "spark");
    static final List<Slot> LEFT = List.of(Slot.HEAD, Slot.CHEST, Slot.HANDS, Slot.FEET, Slot.MELEE, Slot.RANGED);
    static final List<Slot> RIGHT = List.of(Slot.CLOAK, Slot.NECK, Slot.RING1, Slot.RING2, Slot.OFFHAND, Slot.ELIXIR);

    ForgeController(Catalog catalog, EquipmentSynergies synergies, BuildAdvisor advisor, PartyStore saved, HttpServletRequest request) {
        this.catalog = catalog;
        this.synergies = synergies;
        this.advisor = advisor;
        String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        boolean demo = route.startsWith("/demo/");
        basePath = demo ? "/demo/" : "/";
        var session = request.getSession(false);
        store = demo ? (session == null ? null : (PartyStore) session.getAttribute(StartController.DEMO)) : saved;
    }

    @ModelAttribute("basePath")
    String basePath() {
        if (store == null) throw new NoDemo();
        return basePath;
    }

    private static class NoDemo extends RuntimeException {}

    @ExceptionHandler(NoDemo.class)
    String expiredDemo() {
        return "redirect:/";
    }

    private String redirect(String path) {
        return "redirect:" + basePath + path;
    }

    private void layout(Model m, String section, PartyStore.Party party) {
        m.addAttribute("section", section);
        m.addAttribute("party", party);
        m.addAttribute("hero", party == null || party.active == null ? null : party.hero(party.active));
        m.addAttribute("catalog", catalog.items);
        m.addAttribute("abilities", ABILITIES);
        m.addAttribute("shortNames", SHORT);
        m.addAttribute("abilityIcons", ABILITY_ICONS);
    }

    private PartyStore.Campaigns campaigns() {
        if (campaigns == null) campaigns = store.campaigns();
        return campaigns;
    }

    private PartyStore.Party page(Model m, String section) {
        PartyStore.Party party = campaigns().current();
        if (party == null) throw new NoActiveParty();
        layout(m, section, party);
        return party;
    }

    private static class NoActiveParty extends RuntimeException {
    }

    @ExceptionHandler(NoActiveParty.class)
    String chooseParty() {
        return basePath.equals("/") ? "redirect:/" : redirect("parties");
    }

    @GetMapping("/parties")
    String parties(Model m) {
        var all = store.campaigns();
        layout(m, "parties", all.current());
        m.addAttribute("parties", all.parties);
        return "parties";
    }

    @PostMapping("/parties")
    String createParty(@RequestParam String name) throws IOException {
        store.createParty(name);
        return redirect("parties");
    }

    @PostMapping("/parties/{partyId}/switch")
    String switchParty(@PathVariable String partyId, @RequestParam(required = false) String heroId) throws IOException {
        store.switchParty(partyId, heroId);
        return redirect("sheet");
    }

    @GetMapping({"/parties/{partyId}/remove", "/parties/{partyId}/heroes/{heroId}/remove"})
    String confirmRemoval(@PathVariable String partyId, @PathVariable(required = false) String heroId,
                          Model m, HttpSession session) {
        var all = store.campaigns();
        var target = all.find(partyId);
        layout(m, "parties", all.current());
        m.addAttribute("targetParty", target);
        m.addAttribute("targetHero", heroId == null ? null : target.hero(heroId));
        if (session.getAttribute("removalToken") == null)
            session.setAttribute("removalToken", UUID.randomUUID().toString());
        m.addAttribute("removalToken", session.getAttribute("removalToken"));
        return "remove";
    }

    @PostMapping({"/parties/{partyId}/remove", "/parties/{partyId}/heroes/{heroId}/remove"})
    String remove(@PathVariable String partyId, @PathVariable(required = false) String heroId,
                  @RequestParam String token, HttpSession session, RedirectAttributes flash) throws IOException {
        if (!token.equals(session.getAttribute("removalToken")))
            throw new IllegalArgumentException("Reopen the removal confirmation and try again.");
        if (heroId == null) store.removeParty(partyId);
        else store.removeHero(partyId, heroId);
        flash.addFlashAttribute("notice", heroId == null ? "Party removed." : "Hero removed. Their equipment is available in the party inventory.");
        return redirect("parties");
    }

    @GetMapping("/sheet")
    String sheet(Model m, @RequestParam(defaultValue = "auto") String mode,
                 @RequestParam(defaultValue = "") String track) {
        var campaigns = store.campaigns();
        var p = campaigns.current();
        if (p == null) throw new NoActiveParty();
        if (p.active == null) return redirect("parties");
        layout(m, "sheet", p);
        Hero h = p.hero(p.active);
        m.addAttribute("heroGender", h.gender);
        var s = Rules.stats(h, catalog.items);
        boolean offhand = "offhand".equals(mode) && s.offhand() != null;
        boolean ranged = !offhand && !"melee".equals(mode) && (h.gear.containsKey("RANGED") || "ranged".equals(mode));
        m.addAttribute("stats", s);
        m.addAttribute("strike", offhand ? s.offhand() : ranged ? s.ranged() : s.melee());
        m.addAttribute("offhand", offhand);
        m.addAttribute("ranged", ranged);
        m.addAttribute("leftSlots", LEFT);
        m.addAttribute("rightSlots", RIGHT);
        m.addAttribute("slotCount", Slot.values().length);
        String selectedId = track.isBlank() ? h.buildId : track;
        var advice = advisor.recommend(h, campaigns.personalBuilds, selectedId);
        m.addAttribute("advice", advice);
        m.addAttribute("gearPicks", advisor.fillEmptySlots(p, h, advice.selected().plan()));
        if (selectedId.isBlank()) selectedId = advice.selected().plan().id();
        var preview = catalog.build(selectedId);
        var personal = campaigns.personalBuild(selectedId);
        if (!track.isBlank() && preview == null && personal == null)
            throw new IllegalArgumentException("Unknown development track.");
        var personalBuilds = campaigns.personalBuilds.reversed();
        m.addAttribute("personalBuilds", personalBuilds);
        m.addAttribute("personalSelected", personal != null);
        if (!personalBuilds.isEmpty())
            m.addAttribute("personalBuild", personal != null ? personal : personalBuilds.getFirst());
        m.addAttribute("buildRequestId", UUID.randomUUID());
        var orderedBuilds = catalog.builds.stream().sorted(Comparator
                .comparing((Catalog.Build b) -> !b.heroClass().equals(h.heroClass))
                .thenComparing(b -> !b.subclass().equals(h.subclass))).toList();
        if (preview == null) preview = orderedBuilds.stream().filter(b -> b.heroClass().equals(h.heroClass)).findFirst().orElse(null);
        m.addAttribute("build", preview);
        m.addAttribute("builds", orderedBuilds);
        m.addAttribute("classTrackCount", orderedBuilds.stream().filter(b -> b.heroClass().equals(h.heroClass)).count());
        m.addAttribute("mode", mode);
        return "sheet";
    }

    @PostMapping("/heroes/{id}/track")
    String followTrack(@PathVariable String id, @RequestParam String partyId, @RequestParam String buildId,
                       @RequestParam(defaultValue = "auto") String mode, RedirectAttributes flash) throws IOException {
        store.followTrack(partyId, id, buildId);
        flash.addAttribute("mode", mode);
        return redirect(catalog.build(buildId) == null ? "sheet#personal-development-tracks" : "sheet#development-tracks");
    }

    @PostMapping("/heroes/{id}/personal-builds")
    String createPersonalBuild(@PathVariable String id, @RequestParam String partyId, @RequestParam UUID requestId,
                               @RequestParam(defaultValue = "auto") String mode, RedirectAttributes attributes) {
        attributes.addAttribute("track", store.createPersonalBuild(partyId, id, requestId));
        attributes.addAttribute("mode", mode);
        return redirect("sheet#personal-development-tracks");
    }

    @PostMapping("/party/switch")
    String select(@RequestParam String id) throws IOException {
        store.select(id);
        return redirect("sheet");
    }

    public record Option(Item item, int free, boolean equipped, String conflict, Rules.Sheet after, double gain) {
    }

    @GetMapping("/equipment/{slot}")
    String equipment(@PathVariable Slot slot, @RequestParam(required = false) String heroId,
                     @RequestParam(defaultValue = "") String priority, @RequestParam(defaultValue = "") String q,
                     @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "") String synergy,
                     @RequestParam(defaultValue = "catalog") String synergyPool, Model m) {
        var party = page(m, "sheet");
        Hero h = party.hero(heroId == null ? party.active : heroId);
        m.addAttribute("hero", h);
        m.addAttribute("slot", slot);
        var before = Rules.stats(h, catalog.items);
        Map<String, String> priorities = new LinkedHashMap<>();
        priorities.put("ac", "Armour class");
        priorities.put("melee", "Melee damage per hit");
        priorities.put("ranged", "Ranged damage per hit");
        priorities.put("accuracy", "Ranged attack bonus");
        priorities.put("spell", "Spell save DC");
        priorities.put("hp", "Hit points");
        priorities.put("initiative", "Initiative");
        String objective = priorities.containsKey(priority) ? priority :
                slot == Slot.RANGED ? "ranged" : slot == Slot.MELEE ? "melee" : "ac";
        m.addAttribute("priorities", priorities);
        m.addAttribute("objective", objective);
        boolean clothingOnly = slot == Slot.CHEST && "CLOTHING".equals(category);
        m.addAttribute("clothingOnly", clothingOnly);
        m.addAttribute("q", q);
        List<Option> options = new ArrayList<>();
        party.stock.forEach((id, quantity) -> {
            Item i = catalog.items.get(id);
            if (i == null || !slot.accepts(i) || !i.matchesName(q) || clothingOnly && !i.matchesCategory("CLOTHING")) return;
            boolean equipped = id.equals(h.gear.get(slot.name()));
            Hero candidate = h.copy();
            candidate.gear.put(slot.name(), id);
            var after = Rules.stats(candidate, catalog.items);
            double gain = switch (objective) {
                case "melee" -> after.melee().average() - before.melee().average();
                case "ranged" -> after.ranged().average() - before.ranged().average();
                case "accuracy" -> after.ranged().attack() - before.ranged().attack();
                case "spell" -> after.spellDc() - before.spellDc();
                case "hp" -> after.hp() - before.hp();
                case "initiative" -> after.initiative() - before.initiative();
                default -> after.ac() - before.ac();
            };
            options.add(new Option(i, party.available(id), equipped, PartyStore.conflict(h, slot, i, catalog.items), after, gain));
        });
        options.sort(Comparator.comparingDouble(Option::gain).reversed().thenComparing(o -> o.item.name));
        m.addAttribute("before", before);
        m.addAttribute("options", options);
        m.addAttribute("synergyId", synergy);
        boolean ownedOnly = "owned".equals(synergyPool);
        m.addAttribute("synergyPool", ownedOnly ? "owned" : "catalog");
        options.stream().map(Option::item).filter(i -> i.id.equals(synergy)).findFirst().ifPresent(anchor ->
                m.addAttribute("synergyPicks", synergies.forItem(party, h, slot, anchor, objective, ownedOnly)));
        m.addAttribute("category", clothingOnly ? "CLOTHING" :
                slot == Slot.RING1 || slot == Slot.RING2 ? "RING" : slot.name());
        return "equipment";
    }

    @PostMapping("/equipment/{slot}")
    String equip(@PathVariable Slot slot, @RequestParam String heroId, @RequestParam String itemId, RedirectAttributes flash) throws IOException {
        try {
            store.equip(heroId, slot, itemId);
            flash.addFlashAttribute("notice", itemId.isBlank()
                    ? GameText.format("{0} cleared.", GameText.text(slot.label))
                    : GameText.format("{0} equipped.", GameText.text(catalog.items.get(itemId).name)));
            return redirect("sheet");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("notice", e.getMessage());
            flash.addAttribute("heroId", heroId);
            return redirect("equipment/" + slot);
        }
    }

    @GetMapping("/inventory")
    String inventory(Model m, @RequestParam(defaultValue = "owned") String tab, @RequestParam(defaultValue = "") String q,
                     @RequestParam(defaultValue = "") String category, @RequestParam(defaultValue = "1") int page) {
        var p = page(m, "inventory");
        boolean owned = !"catalog".equals(tab);
        List<Item> match = catalog.items.values().stream().filter(i -> !owned || p.stock.containsKey(i.id))
                .filter(i -> i.matchesCategory(category) && i.matchesName(q))
                .sorted(Comparator.comparing(i -> i.name)).toList();
        int pages = Math.max(1, (match.size() + 23) / 24);
        int current = Math.clamp(page, 1, pages);
        m.addAttribute("items", match.subList((current - 1) * 24, Math.min(current * 24, match.size())));
        m.addAttribute("tab", owned ? "owned" : "catalog");
        m.addAttribute("q", q);
        m.addAttribute("category", category);
        m.addAttribute("categories", Arrays.stream(Slot.values()).filter(s -> s != Slot.RING2).toList());
        m.addAttribute("count", match.size());
        m.addAttribute("current", current);
        m.addAttribute("pages", pages);
        m.addAttribute("totalOwned", p.stock.values().stream().mapToInt(Integer::intValue).sum());
        return "inventory";
    }

    @PostMapping("/inventory/add")
    String add(@RequestParam String partyId, @RequestParam String id, @RequestParam(defaultValue = "1") int quantity, @RequestParam(defaultValue = "catalog") String tab,
               @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String category,
               @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "add") String change, RedirectAttributes flash) throws IOException {
        try {
            boolean removing = "remove".equals(change);
            if (removing) store.remove(partyId, id, quantity);
            else store.add(partyId, id, quantity);
            flash.addFlashAttribute("notice", GameText.format(removing ? "Removed {0} × {1}." : "Added {0} × {1}.",
                    quantity, GameText.text(catalog.items.get(id).name)));
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("notice", e.getMessage());
        }
        flash.addAttribute("tab", tab);
        flash.addAttribute("q", q);
        flash.addAttribute("category", category);
        flash.addAttribute("page", page);
        return redirect("inventory");
    }

    @GetMapping("/builds")
    String builds(Model m) {
        page(m, "builds");
        m.addAttribute("builds", catalog.builds);
        return "builds";
    }

    @GetMapping("/credits")
    String credits(Model m) {
        layout(m, "", store.snapshot());
        return "credits";
    }

    @GetMapping("/heroes/new")
    String create(@RequestParam(defaultValue = "") String build, Model m) {
        page(m, "wizard");
        Catalog.Build preset = catalog.build(build);
        Hero h = preset == null ? new Hero() : preset.draft();
        Hero base = new Hero();
        base.id = h.id;
        return wizard(m, h, base, true, 1);
    }

    @GetMapping("/heroes/{id}/edit")
    String edit(@PathVariable String id, @RequestParam(defaultValue = "1") int step, Model m) {
        var p = page(m, "wizard");
        var h = p.hero(id);
        return wizard(m, h.copy(), h, false, Math.clamp(step, 1, 3));
    }

    @PostMapping("/heroes/wizard")
    String develop(@RequestParam String partyId, @RequestParam String id, @RequestParam boolean creating, @RequestParam String name, @RequestParam String heroClass,
                   @RequestParam String race, @RequestParam(required = false) String subrace, @RequestParam(required = false) String subraceFor,
                   @RequestParam Map<String, String> fields,
                   @RequestParam(name = "extraClass", required = false) List<String> extraClasses,
                   @RequestParam(required = false) String gender, @RequestParam int level, @RequestParam(required = false) int[] score,
                   @RequestParam(required = false) int[] purchased, @RequestParam(required = false) Integer plusTwo,
                   @RequestParam(required = false) Integer plusOne, @RequestParam(defaultValue = "None") String subclass,
                   @RequestParam(defaultValue = "None") String style, @RequestParam(name = "skill", required = false) List<String> skills,
                   @RequestParam(name = "feat", required = false) List<String> feats, @RequestParam(defaultValue = "") String buildId,
                   @RequestParam(required = false) List<String> loadout,
                   @RequestParam(defaultValue = "1") int step, @RequestParam(defaultValue = "preview") String action,
                   Model m, RedirectAttributes flash) throws IOException {
        var p = page(m, "wizard");
        if (!p.id.equals(partyId))
            throw new IllegalArgumentException("The active party changed. Reopen the hero wizard.");
        if (creating) {
            try {
                UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
        Hero base = creating ? new Hero() : p.hero(id);
        Hero h = base.copy();
        h.id = id;
        h.name = name.strip();
        if (fields.containsKey("background")) {
            h.background = fields.get("background");
            if (!h.background.isEmpty() && !Rules.BACKGROUNDS.containsKey(h.background))
                throw new IllegalArgumentException("Choose a background from the list.");
        }
        if (gender != null) {
            if (!Hero.GENDERS.contains(gender)) throw new IllegalArgumentException("Choose a gender from the list.");
            h.gender = gender;
        }
        PointBuy allocation = null;
        if (purchased != null || plusTwo != null || plusOne != null) {
            if (!creating || score != null || plusTwo == null || plusOne == null)
                throw new IllegalArgumentException(PointBuy.INVALID);
            allocation = new PointBuy(purchased, plusTwo, plusOne);
            score = allocation.totals();
        } else if (creating) {
            allocation = PointBuy.exact(score).orElseThrow(() ->
                    new IllegalArgumentException("Starting ability scores must use exactly 27 points and both creation bonuses."));
        }
        if (h.name.length() > 60 || score == null || score.length != 6 || Arrays.stream(score).anyMatch(a -> a < 3 || a > 30)
                || level < 1 || level > 12 || !Rules.classes().contains(heroClass))
            throw new IllegalArgumentException("Check the hero’s identity, level and six ability scores.");
        h.heroClass = heroClass;
        var family = Rules.raceFor(race);
        if (!family.name().equals(race)) {
            // Open forms from older versions submit the specific ancestry as the race.
            if (subrace != null || subraceFor != null || fields.keySet().stream().anyMatch(k -> k.startsWith("subrace.")))
                throw new IllegalArgumentException("Choose a playable race.");
            h.race = race;
        } else if (fields.containsKey("subrace." + race)) {
            h.race = family.ancestry(fields.get("subrace." + race));
        } else if (subraceFor != null && !race.equals(subraceFor)) {
            // A plain HTML submission can change race before the subrace control is refreshed.
            if (family.subraces().isEmpty()) {
                h.race = race;
            } else {
                h.race = family.subraces().getFirst();
                step = 1;
                action = "preview";
            }
        } else {
            h.race = family.ancestry(subrace);
        }
        if (fields.containsKey("classAllocation")) {
            h.otherClasses = readAdditionalClasses(heroClass, extraClasses, fields, action);
            h.level = level + h.otherClasses.stream().mapToInt(Hero.ClassLevel::level).sum();
            if (h.level > 12) throw new IllegalArgumentException("The combined class levels must be 12 or less.");
        } else {
            h.level = level;
            if (h.startingLevel() < 1) throw new IllegalArgumentException("Keep at least one level in the starting class.");
        }
        h.abilities = score;
        h.subclass = subclass;
        h.style = style;
        h.skill = "None";
        h.feat = "None";
        h.skills = skills == null ? new ArrayList<>() : new ArrayList<>(skills);
        h.feats = feats == null ? new ArrayList<>() : new ArrayList<>(feats);
        if (action.startsWith("guide.")) buildId = action.substring("guide.".length());
        if (!buildId.isBlank() && catalog.build(buildId) == null && campaigns().personalBuild(buildId) == null)
            throw new IllegalArgumentException("Unknown development track.");
        h.buildId = buildId;
        Rules.normalizeChoices(h);
        if (fields.containsKey("loadoutPresent")) {
            var options = advisor.loadoutOptions(h);
            h.loadout = loadout == null ? new ArrayList<>() :
                    new ArrayList<>(loadout.stream().distinct().filter(options::contains).toList());
        }
        if (allocation != null) {
            m.addAttribute("allocation", allocation);
            if (!allocation.complete() && ("save".equals(action) || ("next".equals(action) && step == 2))) {
                step = 2;
                action = "preview";
                m.addAttribute("notice", allocation.plusOne() == allocation.plusTwo()
                        ? "Assign the +2 and +1 bonuses to different abilities."
                        : GameText.format("Spend exactly 27 points. Your allocation uses {0}.", allocation.spent()));
            }
        }
        if ("save".equals(action) && !h.name.isBlank()) {
            String original = fields.get("originalBuildId");
            store.save(partyId, h, creating, original != null && !original.equals(h.buildId));
            flash.addFlashAttribute("notice", GameText.format(creating ? "{0} joined your party." : "{0} updated.", h.name));
            return redirect("sheet");
        }
        if ("save".equals(action)) {
            m.addAttribute("notice", "Give your hero a name before saving.");
            step = 1;
        } else if ("next".equals(action)) step++;
        else if ("back".equals(action)) step--;
        m.addAttribute("originalBuildId", fields.getOrDefault("originalBuildId", base.buildId));
        return wizard(m, h, base, creating, Math.clamp(step, 1, 3));
    }

    private static List<Hero.ClassLevel> readAdditionalClasses(String startingClass, List<String> submitted,
                                                               Map<String, String> fields, String action) {
        var names = new ArrayList<>(submitted == null ? List.<String>of() : submitted);
        if (action.startsWith("remove.")) names.remove(action.substring("remove.".length()));
        if ("addClass".equals(action)) names.add(fields.get("newClass"));
        var used = new HashSet<>(Set.of(startingClass));
        var result = new ArrayList<Hero.ClassLevel>();
        for (String name : names) {
            if (name == null || !Rules.classes().contains(name) || !used.add(name))
                throw new IllegalArgumentException("Choose a different class for each allocation.");
            int levels = Integer.parseInt(fields.getOrDefault("levels." + name, "1"));
            if (levels < 1 || levels > 12) throw new IllegalArgumentException("Choose 1–12 levels for each class.");
            var skills = new ArrayList<String>();
            for (int i = 0; i < Rules.additionalSkillSlots(name); i++)
                skills.add(fields.getOrDefault("skill." + name + "." + i, "None"));
            result.add(new Hero.ClassLevel(name, levels, fields.getOrDefault("path." + name, "None"),
                    fields.getOrDefault("style." + name, "None"), skills));
        }
        return result;
    }

    private String wizard(Model m, Hero h, Hero base, boolean creating, int step) {
        Rules.normalizeChoices(h);
        m.addAttribute("draft", h);
        var advice = advisor.recommend(h, campaigns().personalBuilds, h.buildId);
        m.addAttribute("advice", advice);
        if (creating) {
            if (!m.containsAttribute("allocation")) m.addAttribute("allocation", PointBuy.recommended(h.abilities));
            m.addAttribute("pointCosts", PointBuy.COST);
        }
        m.addAttribute("gearPicks", advisor.fillEmptySlots(campaigns().current(), h, advice.selected().plan()));
        if (!m.containsAttribute("originalBuildId")) m.addAttribute("originalBuildId", base.buildId);
        m.addAttribute("loadoutOptions", advisor.loadoutOptions(h));
        m.addAttribute("creating", creating);
        m.addAttribute("step", step);
        m.addAttribute("classes", Rules.classes());
        m.addAttribute("races", Rules.PLAYABLE_RACES);
        m.addAttribute("selectedRace", Rules.raceFor(h.race));
        m.addAttribute("genders", Hero.GENDERS);
        m.addAttribute("backgrounds", Rules.BACKGROUNDS);
        m.addAttribute("subclasses", Rules.subclasses(h.heroClass, h.startingLevel()));
        m.addAttribute("styles", Rules.styles(h.heroClass, h.subclass, h.startingLevel()));
        m.addAttribute("skills", Rules.skills(h.heroClass));
        m.addAttribute("skillSlots", Rules.skillSlots(h.heroClass));
        m.addAttribute("classSource", Rules.classSource(h.heroClass, h.subclass));
        m.addAttribute("featChoices", Rules.featChoices());
        m.addAttribute("featSlots", Rules.featSlots(h));
        m.addAttribute("before", Rules.stats(base, catalog.items));
        m.addAttribute("stats", Rules.stats(h, catalog.items));
        return "wizard";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException e, Model m) {
        layout(m, "", store.snapshot());
        m.addAttribute("problem", e.getMessage());
        return "problem";
    }
}
