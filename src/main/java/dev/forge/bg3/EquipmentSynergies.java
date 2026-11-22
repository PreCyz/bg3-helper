package dev.forge.bg3;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.ToDoubleFunction;

@Component
class EquipmentSynergies {
    record PairRule(Set<String> enablers, Set<String> partners, String reason) {}
    public record Pick(String label, Slot slot, Item item, String reason, String availability) {}
    private record Candidate(Item item, String pair, double interaction, boolean buildPair, double priority,
                             double overall, boolean buildGoal, boolean available, String reason) {}
    private record Metric(String key, String label, double weight, ToDoubleFunction<Rules.Sheet> read) {
        double gain(Rules.Sheet after, Rules.Sheet before) {
            return read.applyAsDouble(after) - read.applyAsDouble(before);
        }
    }
    private static final List<Metric> METRICS = List.of(
            new Metric("ac", "armour class", 1, Rules.Sheet::ac),
            new Metric("melee", "average melee damage per hit", .4, s -> s.melee().average()),
            new Metric("ranged", "average ranged damage per hit", .4, s -> s.ranged().average()),
            new Metric("accuracy", "ranged attack bonus", .75, s -> s.ranged().attack()),
            new Metric("spell", "spell save DC", 1, Rules.Sheet::spellDc),
            new Metric("hp", "hit points", .15, Rules.Sheet::hp),
            new Metric("initiative", "initiative", .75, Rules.Sheet::initiative),
            new Metric("meleeAttack", "melee attack bonus", .75, s -> s.melee().attack()),
            new Metric("offhand", "average off-hand damage per hit", .4, s -> s.offhand() == null ? 0 : s.offhand().average()),
            new Metric("movement", "metres of movement", .25, Rules.Sheet::movement),
            new Metric("spellAttack", "spell attack bonus", .75, Rules.Sheet::spellAttack));
    private static final Comparator<Candidate> BEST = Comparator
            .comparing((Candidate c) -> c.pair != null)
            .thenComparingDouble(Candidate::interaction)
            .thenComparing(Candidate::buildPair)
            .thenComparingDouble(Candidate::priority)
            .thenComparing(Candidate::buildGoal)
            .thenComparingDouble(Candidate::overall)
            .thenComparing(Candidate::available)
            .thenComparing(c -> c.item.name, Comparator.reverseOrder());

    private final Catalog catalog;
    private final Map<String,Map<String,String>> pairs = new HashMap<>();

    EquipmentSynergies(Catalog catalog, ObjectMapper mapper) throws IOException {
        this.catalog = catalog;
        try (var in = new ClassPathResource("data/equipment-synergies.json").getInputStream()) {
            for (var rule : mapper.readValue(in, PairRule[].class)) {
                for (String left : rule.enablers) for (String right : rule.partners) {
                    if (!catalog.items.containsKey(left) || !catalog.items.containsKey(right))
                        throw new IOException("Unknown synergy item: " + left + " / " + right);
                    pairs.computeIfAbsent(left, k -> new HashMap<>()).put(right, rule.reason);
                    pairs.computeIfAbsent(right, k -> new HashMap<>()).put(left, rule.reason);
                }
            }
        }
    }

    List<Pick> forItem(PartyStore.Party party, Hero hero, Slot anchorSlot, Item anchor, String priority) {
        Hero preview = hero.copy();
        preview.gear.entrySet().removeIf(e -> !anchorSlot.name().equals(e.getKey()) && anchor.id.equals(e.getValue()));
        if (!PartyStore.conflict(preview, anchorSlot, anchor, catalog.items).isEmpty()) {
            if (anchorSlot == Slot.MELEE) preview.gear.remove("OFFHAND");
            if (anchorSlot == Slot.OFFHAND) preview.gear.remove("MELEE");
        }
        preview.gear.put(anchorSlot.name(), anchor.id);
        var metric = METRICS.stream().filter(m -> m.key.equals(priority)).findFirst().orElse(METRICS.getFirst());
        var build = catalog.build(hero.buildId);
        List<Pick> result = new ArrayList<>();
        Set<String> reserved = new HashSet<>(Set.of(anchor.id));
        for (Slot slot : Slot.values()) {
            if (slot == anchorSlot || slot == Slot.ELIXIR) continue;
            if (slot == Slot.CHEST) {
                result.add(best(party, preview, anchorSlot, anchor, slot, "Armour", false, reserved, metric, build));
                result.add(best(party, preview, anchorSlot, anchor, slot, "Robe / clothing", true, reserved, metric, build));
                continue;
            }
            var pick = best(party, preview, anchorSlot, anchor, slot, slot.label, false, reserved, metric, build);
            result.add(pick);
            if (pick.item != null) {
                reserved.add(pick.item.id);
                // Use the proposed first ring and main hand when evaluating their partner slots.
                if (slot == Slot.MELEE || slot == Slot.RING1) preview.gear.put(slot.name(), pick.item.id);
            }
        }
        return result;
    }

    private Pick best(PartyStore.Party party, Hero preview, Slot anchorSlot, Item anchor, Slot slot, String label,
                      boolean clothing, Set<String> reserved, Metric metric, Catalog.Build build) {
        Hero with = preview.copy();
        with.gear.remove(slot.name());
        Item main = catalog.items.get(with.gear.get("MELEE"));
        if (slot == Slot.OFFHAND && main != null && main.twoHanded)
            return new Pick(label, slot, null, main.name + " uses both hands.", "");
        Hero without = with.copy();
        without.gear.remove(anchorSlot.name());
        var baseline = Rules.stats(with, catalog.items);
        var control = Rules.stats(without, catalog.items);
        Candidate best = null;
        for (Item item : catalog.items.values()) {
            if (!slot.accepts(item) || reserved.contains(item.id) || !Rules.trainedForItem(with, item)) continue;
            if (slot == Slot.CHEST && clothing != "Clothing".equals(item.armorType)) continue;
            if ((slot == Slot.RING1 && item.id.equals(with.gear.get("RING2")))
                    || (slot == Slot.RING2 && item.id.equals(with.gear.get("RING1")))
                    || (slot == Slot.OFFHAND && item.id.equals(with.gear.get("MELEE")))
                    || (slot == Slot.MELEE && item.id.equals(with.gear.get("OFFHAND")))) continue;
            if (!PartyStore.conflict(with, slot, item, catalog.items).isEmpty()) continue;
            with.gear.put(slot.name(), item.id);
            without.gear.put(slot.name(), item.id);
            var after = Rules.stats(with, catalog.items);
            var alone = Rules.stats(without, catalog.items);
            var candidate = score(anchor, item, build, metric, baseline, control, after, alone,
                    party.available(item.id) > 0 || item.id.equals(preview.gear.get(slot.name())));
            if (best == null || BEST.compare(candidate, best) > 0) best = candidate;
        }
        if (best == null) return new Pick(label, slot, null, "No compatible, proficient option for this slot.", "");
        String status = best.item.id.equals(preview.gear.get(slot.name())) ? "Equipped"
                : party.available(best.item.id) > 0 ? "Available in party"
                : party.stock.containsKey(best.item.id) ? "Assigned in party" : "Catalog only";
        return new Pick(label, slot, best.item, best.reason, status);
    }

    private Candidate score(Item anchor, Item item, Catalog.Build build, Metric priority, Rules.Sheet baseline,
                            Rules.Sheet control, Rules.Sheet after, Rules.Sheet alone, boolean available) {
        double overall = 0, interaction = 0, strongest = 0;
        String pairedStat = "", fallback = "No measured stat gain. Compare the item’s conditional effects on its wiki page.";
        for (Metric m : METRICS) {
            double gain = m.gain(after, baseline);
            overall += gain * m.weight;
            double extra = gain - m.gain(alone, control);
            if (extra * m.weight > interaction) {
                interaction = extra * m.weight;
                pairedStat = "Pairing these items adds an extra " + number(extra) + " " + m.label + ".";
            }
            if (gain * m.weight > strongest) {
                strongest = gain * m.weight;
                fallback = "+" + number(gain) + " " + m.label + " with " + anchor.name + " equipped.";
            }
        }
        String pair = pairs.getOrDefault(anchor.id, Map.of()).get(item.id);
        boolean goal = build != null && build.items().contains(item.id);
        boolean buildPair = goal && build.items().contains(anchor.id);
        double gain = priority.gain(after, baseline);
        String reason = pair != null ? pair : interaction > 0 ? pairedStat
                : buildPair ? "Both items are equipment goals for " + build.name() + "."
                : gain > 0 ? "+" + number(gain) + " " + priority.label + " with " + anchor.name + " equipped."
                : goal ? "Equipment goal for " + build.name() + "." : fallback;
        return new Candidate(item, pair, interaction, buildPair, gain, overall, goal, available, reason);
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
