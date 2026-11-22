package dev.forge.bg3;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

@Service
public class PartyStore {
    public static class Party {
        public String id = UUID.randomUUID().toString();
        public String name = "My party";
        public String active;
        public List<Hero> heroes = new ArrayList<>();
        public Map<String, Integer> stock = new LinkedHashMap<>();

        public Hero hero(String id) {
            return heroes.stream().filter(h -> h.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Hero could not be found."));
        }

        public int available(String itemId) {
            int assigned = heroes.stream().mapToInt(h -> Collections.frequency(h.gear.values(), itemId)).sum();
            return stock.getOrDefault(itemId, 0) - assigned;
        }
    }

    public static class Campaigns {
        public String activeParty;
        public List<Party> parties = new ArrayList<>();

        public Party find(String id) {
            return parties.stream().filter(p -> p.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That party is no longer available."));
        }

        public Party current() {
            return activeParty == null ? null : find(activeParty);
        }

        void add(Party party) {
            parties.add(party);
            activeParty = party.id;
        }
    }

    private final ObjectMapper json;
    private final Catalog catalog;
    private final Path file;
    private Campaigns campaigns;

    @Autowired
    PartyStore(ObjectMapper json, Catalog catalog, @Value("${forge.save-file}") String file) throws IOException {
        this.json = json;
        this.catalog = catalog;
        this.file = Path.of(file).toAbsolutePath().normalize();
        campaigns = new Campaigns();
        if (!Files.exists(this.file)) {
            return;
        }
        var saved = json.readTree(this.file.toFile());
        if (saved.has("parties")) {
            campaigns = json.treeToValue(saved, Campaigns.class);
        } else {
            Party legacy = json.treeToValue(saved, Party.class);
            legacy.hero(legacy.active);
            campaigns.add(legacy);
            persist(campaigns);
        }
    }

    private PartyStore(ObjectMapper json, Catalog catalog, Party example) {
        this.json = json;
        this.catalog = catalog;
        this.file = null;
        this.campaigns = new Campaigns();
        campaigns.add(example);
    }

    Party readDemoParty() throws IOException {
        try (var input = new ClassPathResource("data/demo-party.json").getInputStream()) {
            return json.readValue(input, Party.class);
        }
    }

    PartyStore newDemo() throws IOException {
        return new PartyStore(json, catalog, readDemoParty());
    }

    public synchronized Campaigns campaigns() {
        return json.convertValue(campaigns, Campaigns.class);
    }

    public synchronized Party snapshot() {
        return json.convertValue(campaigns.current(), Party.class);
    }

    private synchronized void change(Consumer<Campaigns> action) throws IOException {
        Campaigns next = campaigns();
        action.accept(next);
        if (file != null) persist(next);
        campaigns = next;
    }

    private void update(Consumer<Party> action) throws IOException {
        change(all -> {
            Party current = all.current();
            if (current == null) throw new IllegalArgumentException("Create a party first.");
            action.accept(current);
        });
    }

    private static void requireParty(Party party, String id) {
        if (!party.id.equals(id))
            throw new IllegalArgumentException("The active party changed. Reopen the form in the intended party.");
    }

    private void persist(Campaigns next) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "party-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), next);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void select(String id) throws IOException {
        update(p -> p.active = p.hero(id).id);
    }

    public void createParty(String name) throws IOException {
        String clean = name.strip();
        if (clean.isEmpty() || clean.length() > 60)
            throw new IllegalArgumentException("Use a party name between 1 and 60 characters.");
        Party party = new Party();
        party.name = clean;
        change(all -> all.add(party));
    }

    public void switchParty(String id) throws IOException {
        change(all -> all.activeParty = all.find(id).id);
    }

    public void removeParty(String id) throws IOException {
        change(all -> {
            all.parties.remove(all.find(id));
            if (id.equals(all.activeParty))
                all.activeParty = all.parties.isEmpty() ? null : all.parties.getFirst().id;
        });
    }

    public void removeHero(String partyId, String heroId) throws IOException {
        change(all -> {
            Party party = all.find(partyId);
            party.heroes.remove(party.hero(heroId));
            if (heroId.equals(party.active))
                party.active = party.heroes.isEmpty() ? null : party.heroes.getFirst().id;
        });
    }

    public void followTrack(String partyId, String heroId, String buildId) throws IOException {
        if (catalog.build(buildId) == null) throw new IllegalArgumentException("Unknown development track.");
        update(p -> {
            requireParty(p, partyId);
            if (!heroId.equals(p.active)) throw new IllegalArgumentException("The active hero changed. Reopen their sheet.");
            p.hero(heroId).buildId = buildId;
        });
    }

    public void save(String partyId, Hero draft, boolean creating) throws IOException {
        update(p -> {
            requireParty(p, partyId);
            if (creating) {
                // A repeated submission of the same creation form must keep its original hero.
                if (p.heroes.stream().noneMatch(h -> h.id.equals(draft.id))) p.heroes.add(draft.copy());
            } else {
                Hero current = p.hero(draft.id);
                draft.buildId = current.buildId;
                draft.gear = new LinkedHashMap<>(current.gear);
                Item off = catalog.items.get(draft.gear.get("OFFHAND"));
                if (off != null && off.slot.equals("MELEE")
                        && !Rules.canDualWield(draft, catalog.items.get(draft.gear.get("MELEE")), off))
                    throw new IllegalArgumentException("Unequip your off-hand weapon before removing Dual Wielder.");
                p.heroes.set(p.heroes.indexOf(current), draft.copy());
            }
            p.active = draft.id;
        });
    }

    public void add(String partyId, String item, int quantity) throws IOException {
        if (!catalog.items.containsKey(item) || quantity < 1 || quantity > 99)
            throw new IllegalArgumentException("Choose a catalog item and a quantity from 1 to 99.");
        update(p -> {
            requireParty(p, partyId);
            p.stock.merge(item, quantity, Math::addExact);
        });
    }

    public void remove(String partyId, String item, int quantity) throws IOException {
        if (quantity < 1 || quantity > 99) throw new IllegalArgumentException("Choose a quantity from 1 to 99.");
        update(p -> {
            requireParty(p, partyId);
            if (quantity > p.available(item))
                throw new IllegalArgumentException("Only unassigned copies can be removed.");
            int remaining = p.stock.get(item) - quantity;
            if (remaining == 0) p.stock.remove(item);
            else p.stock.put(item, remaining);
        });
    }

    public static String conflict(Hero h, Slot slot, Item item, Map<String, Item> catalog) {
        Item main = catalog.get(h.gear.get("MELEE"));
        Item off = catalog.get(h.gear.get("OFFHAND"));
        if (slot == Slot.OFFHAND && item.slot.equals("MELEE") && !Rules.canDualWield(h, main, item))
            return "Equip a compatible main-hand weapon. Both weapons must be light unless you have Dual Wielder.";
        if (slot == Slot.MELEE && off != null && off.slot.equals("MELEE") && !Rules.canDualWield(h, item, off))
            return "Unequip the off-hand weapon before changing to this main-hand weapon.";
        if (slot == Slot.OFFHAND && main != null && main.twoHanded)
            return "Unequip your two-handed melee weapon first.";
        if (slot == Slot.MELEE && item.twoHanded && h.gear.containsKey("OFFHAND"))
            return "Unequip your off-hand item first.";
        return "";
    }

    public void equip(String heroId, Slot slot, String id) throws IOException {
        update(p -> {
            Hero h = p.hero(heroId);
            if (id.isBlank()) {
                Item off = catalog.items.get(h.gear.get("OFFHAND"));
                if (slot == Slot.MELEE && off != null && off.slot.equals("MELEE"))
                    throw new IllegalArgumentException("Unequip your off-hand weapon before clearing your main hand.");
                h.gear.remove(slot.name());
                return;
            }
            Item item = catalog.items.get(id);
            if (item == null || !slot.accepts(item))
                throw new IllegalArgumentException("This item does not fit this slot.");
            if (id.equals(h.gear.get(slot.name()))) return;
            if (p.available(id) <= 0)
                throw new IllegalArgumentException("Every copy of this item is already assigned.");
            String problem = conflict(h, slot, item, catalog.items);
            if (!problem.isEmpty()) throw new IllegalArgumentException(problem);
            h.gear.put(slot.name(), id);
        });
    }
}
