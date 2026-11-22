package dev.forge.bg3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DemoFlowTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired PartyStore saved;

    @Test
    void polishAppliesToPagesFiltersAndWizardWithoutChangingSavedRuleValues() throws Exception {
        var session = new MockHttpSession();
        var home = mvc.perform(get("/").session(session).param("lang", "pl"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Wybierz drużynę")))
                .andExpect(content().string(containsString("lang=\"pl\"")))
                .andReturn().getResponse();
        var language = home.getCookie("forge-language");
        assertThat(language).isNotNull();
        mvc.perform(post("/demo/start").session(session).cookie(language));
        var sheet = mvc.perform(get("/demo/sheet").session(session).cookie(language))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Karta postaci")))
                .andExpect(content().string(containsString("Elf leśny"))).andReturn().getResponse().getContentAsString();
        assertThat(sheet.indexOf("class=\"language-menu\"")).isLessThan(sheet.indexOf("class=\"party-menu\""));
        assertThat(sheet).contains("class=\"figure-glow\"", "class=\"hero-figure\"").doesNotContain("/avatars/scenes/");
        mvc.perform(get("/demo/inventory").session(session).cookie(language).param("tab","catalog")
                        .param("q","Zgrabny habit").param("category","CLOTHING"))
                .andExpect(content().string(containsString("Zgrabny habit")))
                .andExpect(content().string(containsString("Gdzie zdobyć")));
        mvc.perform(get("/demo/heroes/aelar/edit").session(session).cookie(language))
                .andExpect(content().string(containsString("Tożsamość postaci")))
                .andExpect(content().string(containsString("value=\"Ranger\"")))
                .andExpect(content().string(containsString("Łowca")));
        mvc.perform(get("/demo/sheet").session(session).cookie(language).param("lang","en"))
                .andExpect(content().string(containsString("Hero sheet")))
                .andExpect(content().string(containsString("Wood Elf")));
    }

    @Test
    void wizardCanAddRemoveAndSaveAllTwelveClasses() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        String party = store.snapshot().id;
        var add = editRace(session, party, "Human").param("classAllocation", "1")
                .param("newClass", "Cleric").param("action", "addClass");
        var added = mvc.perform(add).andExpect(status().isOk())
                .andExpect(content().string(containsString("Remove Cleric")))
                .andExpect(content().string(containsString("Light Domain"))).andReturn().getModelAndView();
        assertThat(((Hero) added.getModel().get("draft")).level).isEqualTo(6);
        assertThat(store.snapshot().hero("aelar").otherClasses).isEmpty();

        String[] others = Rules.classes().stream().filter(c -> !c.equals("Ranger")).toArray(String[]::new);
        var form = post("/demo/heroes/wizard").session(session).param("partyId", party).param("id", "aelar")
                .param("creating", "false").param("name", "Aelar").param("race", "Human")
                .param("heroClass", "Ranger").param("subclass", "Gloom Stalker").param("level", "1")
                .param("score", "10", "16", "14", "10", "15", "10").param("classAllocation", "1")
                .param("extraClass", others).param("path.Cleric", "Light Domain").param("action", "save");
        mvc.perform(form).andExpect(redirectedUrl("/demo/sheet"));
        var h = store.snapshot().hero("aelar");
        assertThat(h.level).isEqualTo(12);
        assertThat(h.classLevels()).hasSize(12).allMatch(c -> c.level() == 1);
        assertThat(h.subclass).isEqualTo("None");
        assertThat(h.feats).isEmpty();
        assertThat(Rules.stats(h, java.util.Map.of()).attacks()).isEqualTo(1);
        mvc.perform(get("/demo/sheet").session(session)).andExpect(content().string(containsString("Cleric · Light Domain")));
        mvc.perform(get("/demo/heroes/aelar/edit").session(session)).andExpect(content().string(containsString("Remove Wizard")));
        mvc.perform(form.param("levels.Cleric", "2")).andExpect(status().isBadRequest());
        mvc.perform(editRace(session, party, "Human").param("classAllocation", "1")
                .param("extraClass", "Ranger").param("action", "save")).andExpect(status().isBadRequest());
        assertThat(store.snapshot().hero("aelar").level).isEqualTo(12);
        var removed = mvc.perform(editRace(session, party, "Human").param("classAllocation", "1")
                .param("extraClass", "Cleric", "Monk").param("action", "remove.Cleric"))
                .andExpect(status().isOk()).andReturn().getModelAndView();
        assertThat(((Hero) removed.getModel().get("draft")).classSummary()).isEqualTo("Ranger 5 / Monk 1");
    }

    @DynamicPropertySource
    static void emptySave(DynamicPropertyRegistry registry) {
        DatabaseFixture.configure(registry);
        registry.add("forge.save-file", () -> directory.resolve("parties.json").toString());
    }

    @Test
    void rosterPortraitOpensTheChosenHeroAndEditsOnlyThatHero() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        var partyId = store.snapshot().id;
        var second = new Hero();
        second.name = "Nyx";
        store.save(partyId, second, true);
        assertThat(store.snapshot().active).isEqualTo(second.id);

        store.createParty("Moonrise");
        var otherPartyId = store.snapshot().id;
        String roster = mvc.perform(get("/demo/parties").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(roster).containsPattern("(?s)<form[^>]*action=\"parties/" + partyId
                + "/switch\"[^>]*>\\s*<button[^>]*name=\"heroId\"[^>]*value=\"aelar\"[^>]*>\\s*<span[^>]*class=\"hero-portrait\"");
        mvc.perform(post("/demo/parties/" + partyId + "/switch").session(session).param("heroId", "missing"))
                .andExpect(status().isBadRequest());
        assertThat(store.snapshot().id).isEqualTo(otherPartyId);

        mvc.perform(post("/demo/parties/" + partyId + "/switch").session(session).param("heroId", "aelar"))
                .andExpect(redirectedUrl("/demo/sheet"));
        mvc.perform(get("/demo/sheet").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"heroes/aelar/edit\"")));
        mvc.perform(get("/demo/heroes/aelar/edit").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Aelar\"")));
        mvc.perform(editRace(session, partyId, "Wood Elf").param("gender", "Female").param("action", "save"))
                .andExpect(redirectedUrl("/demo/sheet"));
        assertThat(store.snapshot().hero("aelar").gender).isEqualTo("Female");
        assertThat(store.snapshot().hero(second.id).gender).isEqualTo(second.gender);
        assertThat(store.snapshot().hero(second.id).name).isEqualTo("Nyx");

        mvc.perform(post("/demo/parties/" + partyId + "/switch").session(session).param("heroId", second.id))
                .andExpect(redirectedUrl("/demo/sheet"));
        mvc.perform(get("/demo/sheet").session(session))
                .andExpect(content().string(containsString("href=\"heroes/" + second.id + "/edit\"")));
    }

    @Test
    void inventorySynergiesRankOwnedCopiesAndRespectAssignments() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        var party = store.snapshot();
        var request = get("/demo/equipment/HEAD").session(session).param("synergy", "shadow-of-menzoberranzan")
                .param("heroId", "aelar").param("synergyPool", "owned");
        var view = mvc.perform(request).andExpect(status().isOk()).andReturn();
        String html = view.getResponse().getContentAsString();
        assertThat(html.indexOf("Synergies with your inventory")).isBetween(html.indexOf("Ability scores &amp; saves") + 1,
                html.indexOf("Equipment synergies") - 1);
        @SuppressWarnings("unchecked")
        var picks = (List<EquipmentSynergies.Pick>) view.getModelAndView().getModel().get("synergyPicks");
        assertThat(picks).filteredOn(p -> p.item() != null).isNotEmpty()
                .allMatch(p -> party.stock.containsKey(p.item().id));
        assertThat(html).doesNotContain("Hellfire Hand Crossbow");
        store.add(party.id, "hellfire-hand-crossbow", 1);
        mvc.perform(request).andExpect(content().string(containsString("Hellfire Hand Crossbow")));
        var other = new Hero();
        other.name = "Scout";
        store.save(party.id, other, true);
        store.equip(other.id, Slot.RANGED, "hellfire-hand-crossbow");
        store.select("aelar");
        var assigned = mvc.perform(request).andReturn().getResponse().getContentAsString();
        assertThat(assigned).doesNotContain("Hellfire Hand Crossbow");
        assertThat(store.snapshot().hero("aelar").gear).isEqualTo(party.hero("aelar").gear);
    }

    @Test
    void inventoryCardsShowWikiAcquisitionRoutesInBothTabs() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        for (var tab : List.of("owned", "catalog")) {
            var html = mvc.perform(get("/demo/inventory").session(session).param("tab", tab).param("q", "Titanstring Bow"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Where to find", "Act 1", "Zhentarim Basement", "Brem", "Find the Missing Shipment",
                    "Act 2", "Moonrise Towers", "Lann Tarv");
        }
    }

    @Test
    void showsAnchorSpecificSynergiesFromTheCatalogWithoutChangingTheHero() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        var before = store.snapshot();
        var original = before.hero("aelar").gear;
        var sheet = mvc.perform(get("/demo/equipment/HEAD").session(session)
                        .param("heroId", "aelar").param("synergy", "shadow-of-menzoberranzan"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Equipment synergies")))
                .andExpect(content().string(containsString("Hellfire Hand Crossbow")))
                .andExpect(content().string(containsString("Invisibility sets up Hellstalker")))
                .andReturn().getModelAndView();
        @SuppressWarnings("unchecked")
        var picks = (List<EquipmentSynergies.Pick>) sheet.getModel().get("synergyPicks");
        assertThat(picks).extracting(EquipmentSynergies.Pick::label).containsExactlyInAnyOrder(
                "Cloak", "Armour", "Robe / clothing", "Gloves", "Boots", "Amulet", "Ring I", "Ring II", "Main hand", "Off hand", "Ranged");
        assertThat(picks.stream().filter(p -> p.item() != null).map(p -> p.item().id)).doesNotHaveDuplicates();
        assertThat(picks.stream().filter(p -> p.slot() == Slot.RANGED).findFirst().orElseThrow().item().id)
                .isEqualTo("hellfire-hand-crossbow");
        assertThat(store.snapshot().hero("aelar").gear).isEqualTo(original);
        assertThat(store.snapshot().stock).isEqualTo(before.stock);

        mvc.perform(post("/demo/inventory/add").session(session).param("partyId", before.id)
                .param("id", "helmet-of-arcane-acuity")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/demo/equipment/HEAD").session(session).param("synergy", "helmet-of-arcane-acuity"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Band of the Mystic Scoundrel")))
                .andExpect(content().string(containsString("bonus-action illusion or enchantment")));
        mvc.perform(post("/demo/inventory/add").session(session).param("partyId", before.id)
                .param("id", "returning-pike")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/demo/equipment/MELEE").session(session).param("synergy", "returning-pike"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Returning Pike uses both hands.")));
    }

    @Test
    void drowMonkSeesItsSubclassFirstAndCanFollowAnyMonkTrack() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        var party = store.snapshot();
        var monk = party.hero("aelar").copy();
        monk.id = "drow-monk";
        monk.race = "Lolth-Sworn Drow";
        monk.heroClass = "Monk";
        monk.subclass = "Way of Shadow";
        monk.buildId = "";
        store.save(party.id, monk, true);
        var view = mvc.perform(get("/demo/sheet").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("4 for Monk · 15 total")))
                .andReturn().getModelAndView().getModel();
        assertThat(((Catalog.Build) view.get("build")).id()).isEqualTo("shadow-monk");
        @SuppressWarnings("unchecked")
        var builds = (List<Catalog.Build>) view.get("builds");
        assertThat(builds).hasSize(15);
        assertThat(builds.subList(0, 4)).allMatch(b -> b.heroClass().equals("Monk"));
        for (var build : builds.subList(0, 4)) {
            assertThat(build.milestones()).hasSize(12).doesNotContain("");
            assertThat(build.draft().subclass).isEqualTo(build.subclass());
            mvc.perform(get(build.art())).andExpect(status().isOk());
            mvc.perform(post("/demo/heroes/drow-monk/track").session(session).param("partyId", party.id)
                    .param("buildId", build.id())).andExpect(status().is3xxRedirection());
            assertThat(store.snapshot().hero(monk.id).race).isEqualTo("Lolth-Sworn Drow");
            assertThat(store.snapshot().hero(monk.id).buildId).isEqualTo(build.id());
        }
    }

    @Test
    void previewsAndFollowsDevelopmentTracksForTheActiveHero() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var partyStore = (PartyStore) session.getAttribute(StartController.DEMO);
        var party = partyStore.snapshot();
        var original = party.hero("aelar");
        mvc.perform(get("/demo/sheet").session(session))
                .andExpect(content().string(containsString("Development tracks")))
                .andExpect(content().string(containsString("Aelar is following this track.")))
                .andExpect(content().string(containsString("Next at level 6")));
        mvc.perform(get("/demo/sheet").session(session).param("track", "radiant-cleric"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Follow this track with Aelar")))
                .andExpect(content().string(containsString("Prepare Spirit Guardians")))
                .andExpect(content().string(containsString("Level 12")));
        assertThat(partyStore.snapshot().hero("aelar").buildId).isEqualTo(original.buildId);
        mvc.perform(post("/demo/heroes/aelar/track").session(session).param("partyId", party.id)
                .param("buildId", "radiant-cleric")).andExpect(status().is3xxRedirection());
        var updated = partyStore.snapshot().hero("aelar");
        assertThat(updated.buildId).isEqualTo("radiant-cleric");
        assertThat(updated.heroClass).isEqualTo(original.heroClass);
        assertThat(updated.gear).isEqualTo(original.gear);
        assertThat(updated.level).isEqualTo(original.level);
        mvc.perform(get("/demo/sheet").session(session))
                .andExpect(content().string(containsString("Aelar is following this track.")))
                .andExpect(content().string(containsString("Prepare Spirit Guardians")));
        mvc.perform(post("/demo/heroes/aelar/track").session(session).param("partyId", party.id)
                .param("buildId", "unlisted")).andExpect(status().isBadRequest());
        var staleEdit = original.copy();
        staleEdit.heroClass = "Cleric";
        staleEdit.level = 4;
        partyStore.save(party.id, staleEdit, false);
        mvc.perform(get("/demo/sheet").session(session)).andExpect(content().string(containsString("Next at level 5")))
                .andExpect(content().string(containsString("Prepare Spirit Guardians")));
    }

    @Test
    void findsClothingByPolishNameInCatalogInventoryAndChestChooser() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var party = ((PartyStore) session.getAttribute(StartController.DEMO)).snapshot();
        var catalog = get("/demo/inventory").session(session).param("tab", "catalog").param("category", "CLOTHING");
        mvc.perform(catalog.param("q", " zGrabny HaBit ")).andExpect(status().isOk())
                .andExpect(model().attribute("count", 1))
                .andExpect(content().string(containsString("The Graceful Cloth")))
                .andExpect(content().string(containsString("Zgrabny habit")));
        mvc.perform(get("/demo/inventory").session(session).param("tab", "catalog")
                        .param("category", "CLOTHING").param("q", "Luminous Armour"))
                .andExpect(model().attribute("count", 0));
        mvc.perform(post("/demo/inventory/add").session(session).param("partyId", party.id)
                .param("id", "the-graceful-cloth")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/demo/inventory").session(session).param("category", "CLOTHING").param("q", "Graceful"))
                .andExpect(model().attribute("count", 1));
        mvc.perform(get("/demo/equipment/CHEST").session(session).param("category", "CLOTHING")
                        .param("q", "habit").param("heroId", "aelar"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("<h2>The Graceful Cloth</h2>")));
    }

    @Test
    void wizardOffersPlayableRacesAndOnlyTheirOwnSubraces() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        String party = store.snapshot().id;
        var edit = mvc.perform(get("/demo/heroes/aelar/edit").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(options(edit, "race")).containsExactly("Human", "Elf", "Drow", "Half-Elf", "Half-Orc",
                "Halfling", "Dwarf", "Gnome", "Tiefling", "Githyanki", "Dragonborn");
        for (var race : Rules.PLAYABLE_RACES) {
            String field = "subrace." + race.name();
            if (race.subraces().isEmpty()) {
                assertThat(edit).doesNotContain("name=\"" + field + "\"");
            } else {
                assertThat(options(edit, field)).containsExactlyElementsOf(race.subraces());
                assertThat(edit).contains("#wizardForm:has(#race-" + race.name() + ":checked) #subrace-" + race.name());
            }
        }
        var refresh = editRace(session, party, "Drow").param("subraceFor", "Elf").param("subrace", "Wood Elf")
                .param("action", "next");
        String drow = mvc.perform(refresh).andExpect(status().isOk()).andExpect(model().attribute("step", 1))
                .andReturn().getResponse().getContentAsString();
        assertThat(options(drow, "subrace.Drow")).containsExactly("Lolth-Sworn Drow", "Seldarine Drow");
        mvc.perform(editRace(session, party, "Drow").param("subrace.Drow", "Drow Half-Elf").param("action", "save"))
                .andExpect(status().isBadRequest());
        assertThat(store.snapshot().hero("aelar").race).isEqualTo("Wood Elf");
        mvc.perform(editRace(session, party, "Drow").param("subrace.Drow", "Seldarine Drow")
                .param("subrace.Elf", "Wood Elf").param("action", "save")).andExpect(redirectedUrl("/demo/sheet"));
        assertThat(store.snapshot().hero("aelar").race).isEqualTo("Seldarine Drow");
        mvc.perform(editRace(session, party, "Half-Elf").param("subrace.Half-Elf", "Drow Half-Elf").param("action", "save"))
                .andExpect(redirectedUrl("/demo/sheet"));
        String halfElf = mvc.perform(get("/demo/heroes/aelar/edit").session(session)).andReturn().getResponse().getContentAsString();
        assertThat(options(halfElf, "subrace.Half-Elf")).containsExactly("High Half-Elf", "Wood Half-Elf", "Drow Half-Elf");
        assertThat(store.snapshot().hero("aelar").race).isEqualTo("Drow Half-Elf");
        mvc.perform(editRace(session, party, "Human").param("subrace.Drow", "Seldarine Drow").param("action", "save"))
                .andExpect(redirectedUrl("/demo/sheet"));
        assertThat(store.snapshot().hero("aelar").race).isEqualTo("Human");
    }

    private static MockHttpServletRequestBuilder editRace(MockHttpSession session, String party, String race) {
        return post("/demo/heroes/wizard").session(session).param("partyId", party)
                .param("id", "aelar").param("creating", "false").param("name", "Aelar")
                .param("heroClass", "Ranger").param("race", race).param("level", "5")
                .param("score", "12", "17", "14", "8", "15", "8");
    }

    private static List<String> options(String page, String field) {
        var select = Pattern.compile("<select name=\"" + Pattern.quote(field) + "\"[^>]*>(.*?)</select", Pattern.DOTALL).matcher(page);
        assertThat(select.find()).as(field).isTrue();
        return Pattern.compile("<option\\s+[^>]*?\\bvalue=\"([^\"]+)\"").matcher(select.group(1))
                .results().map(match -> match.group(1)).toList();
    }

    @Test
    void editingTheSameHeroSwitchesBothIllustrations() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/demo/start").session(session));
        var store = (PartyStore) session.getAttribute(StartController.DEMO);
        var form = post("/demo/heroes/wizard").session(session)
                .param("partyId", store.snapshot().id).param("id", "aelar").param("creating", "false")
                .param("name", "Aelar").param("gender", "Female").param("race", "Wood Elf")
                .param("heroClass", "Ranger").param("subclass", "Gloom Stalker").param("level", "5")
                .param("score", "12", "17", "14", "8", "15", "8");
        String face = "/art/avatars/portraits/female/wood-elf/ranger.webp";
        String figure = "/art/avatars/figures/female/wood-elf/ranger.webp";
        mvc.perform(form).andExpect(status().isOk()).andExpect(content().string(containsString(figure)));
        assertThat(store.snapshot().hero("aelar").gender).isEqualTo("Male");
        mvc.perform(form.param("action", "save")).andExpect(redirectedUrl("/demo/sheet"));
        mvc.perform(get("/demo/sheet").session(session)).andExpect(content().string(containsString(figure)))
                .andExpect(content().string(containsString(face)));
        mvc.perform(get("/demo/parties").session(session)).andExpect(content().string(containsString(face)));
    }

    @Test
    void firstRunDemoAndSavedCampaignsStayIndependent() throws Exception {
        Path save = directory.resolve("parties.json");
        var browser = new MockHttpSession();
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Create party")))
                .andExpect(content().string(containsString("/art/avatars/portraits/male/wood-elf/ranger.webp")))
                .andExpect(content().string(containsString("Try demo")));
        assertThat(saved.campaigns().parties).isEmpty();
        mvc.perform(post("/demo/start").session(browser)).andExpect(redirectedUrl("/demo/sheet"));
        mvc.perform(get("/demo/sheet").session(browser)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Aelar")))
                .andExpect(content().string(containsString("/art/avatars/figures/male/wood-elf/ranger.webp")))
                .andExpect(content().string(containsString("/art/avatars/portraits/male/wood-elf/ranger.webp")))
                .andExpect(content().string(containsString("Titanstring Bow")))
                .andExpect(content().string(containsString("<base href=\"/demo/\"")))
                .andExpect(content().string(containsString("Exit demo")));
        mvc.perform(post("/demo/equipment/RANGED").session(browser).param("heroId", "aelar").param("itemId", "longbow"))
                .andExpect(redirectedUrl("/demo/sheet"));
        mvc.perform(get("/demo/sheet").session(browser)).andExpect(content().string(containsString("Longbow equipped.")));
        mvc.perform(get("/demo/sheet").session(new MockHttpSession())).andExpect(redirectedUrl("/"));
        assertThat(save).doesNotExist();

        mvc.perform(post("/parties").session(browser).param("name", "Moonrise")).andExpect(redirectedUrl("/parties"));
        assertThat(saved.snapshot().heroes).isEmpty();
        assertThat(saved.snapshot().stock).isEmpty();
        var personalParty = saved.snapshot().id;
        mvc.perform(post("/demo/parties").session(browser).param("name", "Test party")).andExpect(redirectedUrl("/demo/parties"));
        mvc.perform(get("/demo/parties").session(browser)).andExpect(content().string(containsString("Test party")));
        mvc.perform(post("/demo/exit").session(browser)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(browser)).andExpect(content().string(containsString("Continue Moonrise")));
        mvc.perform(post("/demo/parties").session(browser).param("name", "Expired tab")).andExpect(redirectedUrl("/"));
        mvc.perform(post("/demo/start").session(browser)).andExpect(redirectedUrl("/demo/sheet"));
        var freshDemo = (PartyStore) browser.getAttribute(StartController.DEMO);
        assertThat(freshDemo.campaigns().parties).hasSize(1);
        assertThat(freshDemo.snapshot().hero("aelar").gear.get("RANGED")).isEqualTo("titanstring-bow");
        assertThat(saved.snapshot().id).isEqualTo(personalParty);
        assertThat(saved.campaigns().parties).hasSize(1);
        assertThat(save).doesNotExist();
    }
}
