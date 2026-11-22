package dev.forge.bg3;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WorkshopFlowTest {
    @TempDir
    static Path temp;

    @DynamicPropertySource
    static void saveFile(DynamicPropertyRegistry registry) throws IOException {
        Path file = temp.resolve("party.json");
        try (var fixture = WorkshopFlowTest.class.getResourceAsStream("/data/demo-party.json")) {
            Files.copy(fixture, file);
        }
        registry.add("forge.save-file", file::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    Catalog catalog;
    @Autowired
    PartyStore store;
    @Autowired
    ObjectMapper json;
    @LocalServerPort
    int port;

    @Test
    void startPageServesTheBrandAndTitleInUtf8() throws Exception {
        URI home = URI.create("http://127.0.0.1:" + port + "/");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(home).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var contentType = MediaType.parseMediaType(response.headers().firstValue("Content-Type").orElseThrow());
            assertThat(contentType.getCharset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(response.body()).contains("<title>Choose a party · Faerûn Forge</title>",
                    "Faerûn <b>Forge</b>", "<span>Faerûn Forge</span");
        }
    }

    @Test
    void rendersOfflinePagesAndEverySlot() throws Exception {
        for (String path : new String[]{"/", "/sheet", "/sheet?mode=melee", "/inventory", "/inventory?tab=catalog",
                "/heroes/new", "/heroes/aelar/edit", "/builds", "/parties", "/credits", "/webjars/bootstrap/5.3.3/css/bootstrap.min.css",
                "/art/ranger.webp", "/forge.js", "/forge.css"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        for (Slot slot : Slot.values()) {
            mvc.perform(get("/equipment/" + slot).param("heroId", "aelar")).andExpect(status().isOk());
        }
        assertThat(catalog.builds.stream().map(Catalog.Build::heroClass).distinct().toList())
                .containsExactlyInAnyOrderElementsOf(Rules.classes());
        for (var build : catalog.builds) {
            assertThat(catalog.items.keySet()).containsAll(build.items());
            assertThat(build.milestones()).hasSize(12).doesNotContain("");
            var response = mvc.perform(get("/heroes/new").param("build", build.id())).andExpect(status().isOk())
                    .andReturn().getModelAndView();
            var draft = (Hero) response.getModel().get("draft");
            assertThat(draft.buildId).isEqualTo(build.id());
            assertThat(draft.subclass).isEqualTo(build.subclass());
            assertThat(draft.style).isEqualTo(build.style());
            assertThat(draft.skills).containsExactlyElementsOf(build.skills()).doesNotContain("None");
            assertThat(draft.abilities).containsExactly(build.abilities());
            mvc.perform(get(build.art())).andExpect(status().isOk());
        }
    }

    @Test
    void previewsThenSavesEquipsSwitchesAndReloads() throws Exception {
        String id = UUID.randomUUID().toString();
        String party = store.snapshot().id;
        var form = post("/heroes/wizard").param("creating", "true").param("id", id)
                .param("partyId", party)
                .param("name", "Selûne").param("heroClass", "Cleric").param("race", "Dwarf").param("subrace", "Gold Dwarf")
                .param("gender", "Female")
                .param("level", "5").param("score", "10", "14", "16", "8", "16", "10")
                .param("subclass", "Light Domain").param("skill", "Insight", "Religion").param("step", "3");
        var preview = mvc.perform(form).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(preview.getContentAsString()).contains("Radiance of the Dawn",
                "/art/avatars/figures/female/gold-dwarf/cleric.webp", "/art/avatars/emblems/light-domain.webp",
                "/art/avatars/scenes/light-domain.webp");
        assertThat(store.snapshot().heroes).noneMatch(h -> h.id.equals(id));
        mvc.perform(form.param("action", "save")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/inventory/add").param("partyId", party).param("id", "luminous-armour").param("quantity", "1")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/equipment/CHEST").param("heroId", id).param("itemId", "luminous-armour")).andExpect(status().is3xxRedirection());
        var savedSheet = mvc.perform(get("/sheet")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(savedSheet.getContentAsString()).containsPattern("<span>Female</span><i>·</i>\\s*<span>Gold Dwarf</span>");
        assertThat(savedSheet.getContentAsString()).contains("/art/avatars/figures/female/gold-dwarf/cleric.webp",
                "/art/avatars/portraits/female/gold-dwarf/cleric.webp",
                "/art/avatars/scenes/light-domain.webp");
        mvc.perform(post("/party/switch").param("id", "aelar")).andExpect(status().is3xxRedirection());
        var restored = new PartyStore(json, catalog, temp.resolve("party.json").toString()).snapshot();
        assertThat(restored.active).isEqualTo("aelar");
        assertThat(restored.hero(id).gear.get("CHEST")).isEqualTo("luminous-armour");
        assertThat(restored.hero(id).skills).containsExactly("Insight", "Religion");
        assertThat(restored.hero(id).gender).isEqualTo("Female");
        assertThatThrownBy(() -> store.equip(id, Slot.RANGED, "titanstring-bow")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.equip(id, Slot.HEAD, "caustic-band")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inventoryAccountingSurvivesRejectedAndRepeatedAssignments() throws Exception {
        PartyStore isolated = store.newDemo();
        String party = isolated.snapshot().id;
        isolated.add(party, "shortsword", 1);
        isolated.equip("aelar", Slot.OFFHAND, "shortsword");
        isolated.equip("aelar", Slot.OFFHAND, "shortsword");
        assertThat(isolated.snapshot().available("shortsword")).isZero();
        assertThatThrownBy(() -> isolated.remove(party, "shortsword", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> isolated.equip("aelar", Slot.MELEE, "")).isInstanceOf(IllegalArgumentException.class);
        isolated.equip("aelar", Slot.OFFHAND, "");
        isolated.remove(party, "shortsword", 1);
        assertThat(isolated.snapshot().stock.get("shortsword")).isEqualTo(1);
    }

    @Test
    void firstEquipRedirectsToTheHeroSheetInAFreshBrowserSession() throws Exception {
        URI picker = URI.create("http://127.0.0.1:" + port + "/equipment/RANGED?heroId=aelar");
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var selection = client.send(HttpRequest.newBuilder(picker).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(selection.body()).contains("Equip item", "value=\"longbow\"");
            var equip = HttpRequest.newBuilder(picker.resolve("/equipment/RANGED"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("heroId=aelar&itemId=longbow")).build();
            var sheet = client.send(equip, HttpResponse.BodyHandlers.ofString());
            assertThat(sheet.uri().getPath()).isEqualTo("/sheet");
            assertThat(sheet.statusCode()).isEqualTo(200);
            assertThat(sheet.body()).contains("Longbow equipped.");
            assertThat(store.snapshot().hero("aelar").gear.get("RANGED")).isEqualTo("longbow");
        } finally {
            store.equip("aelar", Slot.RANGED, "titanstring-bow");
        }
    }

    @Test
    void managesPartiesAndRequiresConfirmationBeforeRemovingThem() throws Exception {
        String original = store.snapshot().id;
        mvc.perform(post("/parties").param("name", "Moonrise")).andExpect(redirectedUrl("/parties"));
        String second = store.snapshot().id;
        mvc.perform(get("/sheet")).andExpect(redirectedUrl("/parties"));
        mvc.perform(get("/parties")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Moonrise")));
        assertThat(store.snapshot().stock).isEmpty();
        assertThatThrownBy(() -> store.add(original, "longbow", 1)).isInstanceOf(IllegalArgumentException.class);
        Hero hero = new Hero();
        hero.name = "Nyx";
        store.save(second, hero, true);
        store.add(second, "longbow", 1);
        store.equip(hero.id, Slot.RANGED, "longbow");
        String removeHero = "/parties/" + second + "/heroes/" + hero.id + "/remove";
        var session = new MockHttpSession();
        mvc.perform(get(removeHero).session(session)).andExpect(status().isOk());
        mvc.perform(post(removeHero).param("token", "invalid").session(session)).andExpect(status().isBadRequest());
        assertThat(store.snapshot().heroes).hasSize(1);
        String token = (String) session.getAttribute("removalToken");
        mvc.perform(post(removeHero).param("token", token).session(session)).andExpect(redirectedUrl("/parties"));
        assertThat(store.snapshot().active).isNull();
        assertThat(store.snapshot().available("longbow")).isEqualTo(1);
        mvc.perform(post("/parties/" + original + "/switch")).andExpect(redirectedUrl("/sheet"));
        assertThat(store.snapshot().hero("aelar")).isNotNull();
        String removeParty = "/parties/" + second + "/remove";
        mvc.perform(get(removeParty).session(session)).andExpect(status().isOk());
        mvc.perform(post(removeParty).session(session).param("token", token)).andExpect(redirectedUrl("/parties"));
        assertThat(store.campaigns().parties).extracting(p -> p.id).containsExactly(original);
    }

    @Test
    void migratesLegacySaveAndAllowsRemovingTheLastParty() throws Exception {
        Path file = temp.resolve("legacy.json");
        var oldParty = store.snapshot();
        var legacy = json.valueToTree(oldParty);
        ((ObjectNode) legacy).remove(java.util.List.of("id", "name"));
        ((ObjectNode) legacy.get("heroes").get(0)).remove("gender");
        json.writeValue(file.toFile(), legacy);
        var migrated = new PartyStore(json, catalog, file.toString());
        var original = migrated.snapshot();
        assertThat(original.heroes.getFirst().gender).isEqualTo("Gender unspecified");
        assertThat(original.stock).isEqualTo(oldParty.stock);
        assertThat(original.hero("aelar").gear).isEqualTo(oldParty.hero("aelar").gear);
        migrated.createParty("Second");
        migrated.removeParty(migrated.snapshot().id);
        assertThat(migrated.snapshot().id).isEqualTo(original.id);
        migrated.removeParty(original.id);
        var reopened = new PartyStore(json, catalog, file.toString());
        assertThat(reopened.campaigns().parties).isEmpty();
        reopened.createParty("Fresh start");
        assertThat(reopened.snapshot().heroes).isEmpty();
    }
}
