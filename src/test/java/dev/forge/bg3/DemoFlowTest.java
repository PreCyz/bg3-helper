package dev.forge.bg3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DemoFlowTest {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired PartyStore saved;

    @DynamicPropertySource
    static void emptySave(DynamicPropertyRegistry registry) {
        registry.add("forge.save-file", () -> directory.resolve("parties.json").toString());
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
        byte[] personalSave = Files.readAllBytes(save);
        mvc.perform(post("/demo/parties").session(browser).param("name", "Test party")).andExpect(redirectedUrl("/demo/parties"));
        mvc.perform(get("/demo/parties").session(browser)).andExpect(content().string(containsString("Test party")));
        mvc.perform(post("/demo/exit").session(browser)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/").session(browser)).andExpect(content().string(containsString("Continue Moonrise")));
        mvc.perform(post("/demo/parties").session(browser).param("name", "Expired tab")).andExpect(redirectedUrl("/"));
        mvc.perform(post("/demo/start").session(browser)).andExpect(redirectedUrl("/demo/sheet"));
        var freshDemo = (PartyStore) browser.getAttribute(StartController.DEMO);
        assertThat(freshDemo.campaigns().parties).hasSize(1);
        assertThat(freshDemo.snapshot().hero("aelar").gear.get("RANGED")).isEqualTo("titanstring-bow");
        assertThat(Files.readAllBytes(save)).isEqualTo(personalSave);
    }
}
