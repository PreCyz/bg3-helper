package dev.forge.bg3;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkshopFlowTest extends ForgeIntegrationTest {
    @TempDir
    Path temp;

    @BeforeEach
    void seedSavedParty() {
        database.change(campaigns -> campaigns.add(database.demoParty()));
    }

    @AfterEach
    void removeSavedParties() throws Exception {
        for (var party : store.campaigns().parties) store.removeParty(party.id);
    }

    @Autowired
    MockMvc mvc;
    @Autowired ForgeDatabase database;
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
                "/art/test-image.svg", "/forge.js", "/forge.css"}) {
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
            assertThat(DatabaseFixture.assetPaths()).contains(build.art());
        }
    }

    @Test
    @Transactional
    void personalTrackKeepsTheSavedSetupAcrossParties() throws Exception {
        store.select("aelar");
        var original = store.snapshot();
        var source = original.hero(original.active).copy();
        source.otherClasses.add(new Hero.ClassLevel("Fighter", 1, "None", "Defence", List.of()));
        store.save(original.id, source, false);
        var sheet = mvc.perform(get("/sheet")).andExpect(status().isOk()).andReturn();
        assertThat(sheet.getResponse().getContentAsString()).contains("Create build", "class=\"track-grid\"")
                .doesNotContain("id=\"personal-development-tracks\"");
        String request = sheet.getModelAndView().getModel().get("buildRequestId").toString();
        var create = post("/heroes/" + source.id + "/personal-builds").param("partyId", original.id)
                .param("requestId", request).param("mode", "melee");
        String location = mvc.perform(create).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).contains("mode=melee").endsWith("#personal-development-tracks");
        mvc.perform(create).andExpect(redirectedUrl(location));
        var reopened = new PartyStore(json, catalog, database).campaigns();
        assertThat(reopened.personalBuilds).hasSize(1);
        var build = reopened.personalBuilds.getFirst();
        assertThat(build.hero()).usingRecursiveComparison().isEqualTo(source);
        var result = mvc.perform(get(URI.create(location))).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("Personal development tracks", "Fighter",
                "Defence", "Titanstring Bow", "Build 1");

        store.createParty("Second party");
        var second = store.snapshot();
        var follower = new Hero();
        follower.name = "Nyx";
        store.save(second.id, follower, true);
        mvc.perform(create).andExpect(status().isBadRequest());
        store.removeHero(original.id, source.id);
        mvc.perform(post("/heroes/" + follower.id + "/track").param("partyId", second.id).param("buildId", build.id()))
                .andExpect(status().is3xxRedirection());
        var followed = mvc.perform(get("/sheet")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(followed.getContentAsString()).contains("Nyx is following this track.", "Titanstring Bow");
        assertThat(store.snapshot().hero(follower.id).gear).isEmpty();
        assertThat(store.newDemo().campaigns().personalBuilds).isEmpty();
    }

    @Test
    @Transactional
    void followsAPolishGuideFromCreationThroughLevelUp() throws Exception {
        String party = store.snapshot().id;
        String id = UUID.randomUUID().toString();
        var form = post("/heroes/wizard").param("lang", "pl").param("partyId", party).param("creating", "true")
                .param("id", id).param("name", "Mira").param("heroClass", "Cleric").param("race", "Wood Elf")
                .param("subclass", "Light Domain").param("level", "1").param("score", "10", "14", "16", "8", "16", "10")
                .param("background", "Acolyte").param("loadoutPresent", "1").param("loadout", "Bless", "Spirit Guardians")
                .param("buildId", "radiant-cleric");
        var preview = mvc.perform(form).andExpect(status().isOk()).andReturn();
        assertThat(preview.getResponse().getContentAsString()).contains("Doradca rozwoju postaci", "Przygotuj czary",
                "Błogosławieństwo", "Leczące słowo", "Najlepiej dopasowane konfiguracje").doesNotContain("??advisor.");
        assertThat(store.snapshot().heroes).noneMatch(h -> h.id.equals(id));
        mvc.perform(form.param("action", "save")).andExpect(status().is3xxRedirection());
        assertThat(store.snapshot().hero(id).loadout).containsExactly("Bless");
        mvc.perform(post("/heroes/wizard").param("partyId", party).param("id", id).param("creating", "false")
                .param("name", "Mira").param("heroClass", "Cleric").param("race", "Wood Elf").param("subclass", "Light Domain")
                .param("level", "5").param("score", "10", "14", "16", "8", "16", "10").param("feat", "Ability Improvement · WIS +2")
                .param("loadoutPresent", "1").param("loadout", "Spirit Guardians", "Bless")
                .param("buildId", "radiant-cleric").param("action", "save")).andExpect(status().is3xxRedirection());
        var saved = new PartyStore(json, catalog, database).snapshot().hero(id);
        assertThat(saved.loadout).containsExactly("Spirit Guardians", "Bless");
        assertThat(saved.buildId).isEqualTo("radiant-cleric");
        assertThat(saved.background).isEqualTo("Acolyte");
        var sheet = mvc.perform(get("/sheet").param("lang", "pl")).andExpect(status().isOk()).andReturn();
        var advice = (BuildAdvisor.Advice) sheet.getModelAndView().getModel().get("advice");
        assertThat(advice.bestFits()).hasSize(3);
        assertThat(advice.current().level()).isEqualTo(5);
        assertThat(advice.trained()).contains("Insight", "Religion");
        assertThat(advice.next().level()).isEqualTo(6);
        assertThat(advice.increases()).contains(new BuildAdvisor.AbilityGoal("Wisdom", 18, 18));
        assertThat(advice.feats().getFirst().taken()).isTrue();
        assertThat(sheet.getResponse().getContentAsString()).contains("Duchowi strażnicy", "Zaznaczono");
    }

    @Test
    void adviceUsesClassLevelsAndTheRemainingCharacterLevels(@Autowired BuildAdvisor advisor) {
        Hero h = catalog.build("shadow-monk").draft();
        h.level = 5;
        h.subclass = "Way of Shadow";
        h.otherClasses.add(new Hero.ClassLevel("Cleric", 1, "Light Domain", "None", List.of()));
        assertThat(advisor.recommend(h, List.of(), "").bestFits().getFirst().plan().id()).isEqualTo("shadow-monk");
        var advice = advisor.recommend(h, List.of(), "radiant-cleric");
        assertThat(advice.current().level()).isEqualTo(1);
        assertThat(advice.next().level()).isEqualTo(2);
        assertThat(advisor.loadoutOptions(h)).contains("Bless").doesNotContain("Spirit Guardians");
        assertThat(advisor.recommend(h, List.of(), "four-elements-monk").available())
                .noneMatch(g -> g.kind().equals("discipline"));
        h.level = 12;
        assertThat(advisor.recommend(h, List.of(), "radiant-cleric").next()).isNull();
        for (var build : catalog.builds) {
            var full = build.draft();
            full.level = 12;
            var complete = advisor.recommend(full, List.of(), build.id());
            assertThat(complete.current().level()).isEqualTo(12);
            assertThat(complete.feats()).hasSize(Rules.featSlots(full));
            assertThat(complete.feats()).allMatch(g -> Rules.featChoices().contains(g.feat()));
            assertThat(complete.next()).isNull();
        }
    }

    @Test
    @Transactional
    void newHeroRequiresThePurchasedAllocationToCostExactly27() throws Exception {
        var party = store.snapshot();
        String id = UUID.randomUUID().toString();
        var form = post("/heroes/wizard").param("partyId", party.id).param("id", id).param("creating", "true")
                .param("name", "Nia").param("heroClass", "Cleric").param("race", "Wood Elf")
                .param("level", "1").param("step", "3").param("action", "save");
        // These combined scores are legal with a different bonus pair. Validate the actual purchase.
        var invalid = mvc.perform(form.param("purchased", "8", "15", "14", "10", "15", "10")
                        .param("plusTwo", "0").param("plusOne", "1"))
                .andExpect(status().isOk()).andReturn().getModelAndView();
        assertThat(invalid.getModel().get("step")).isEqualTo(2);
        assertThat(((PointBuy) invalid.getModel().get("allocation")).spent()).isEqualTo(29);
        assertThat(store.snapshot().heroes).noneMatch(h -> h.id.equals(id));
        form.with(req -> {
            req.setParameter("purchased", "8", "8", "8", "15", "15", "15");
            req.setParameter("plusTwo", "4");
            req.setParameter("plusOne", "4");
            return req;
        });
        mvc.perform(form).andExpect(status().isOk());
        assertThat(store.snapshot().heroes).noneMatch(h -> h.id.equals(id));
        form.with(req -> { req.setParameter("plusOne", "5"); return req; });
        mvc.perform(form).andExpect(redirectedUrl("/sheet"));
        assertThat(store.snapshot().hero(id).abilities).containsExactly(8, 8, 8, 15, 17, 16);
    }

    @Test
    void equipmentAdviceUsesOnlyFreeCopiesAndLeavesThePartyUntouched(@Autowired BuildAdvisor advisor) {
        var party = new PartyStore.Party();
        var cleric = catalog.build("radiant-cleric").draft();
        var companion = new Hero();
        companion.gear.put("RING1", "caustic-band");
        party.heroes.addAll(List.of(cleric, companion));
        party.stock.put("caustic-band", 1);
        party.stock.put("luminous-armour", 1);
        var plan = advisor.recommend(cleric, List.of(), "radiant-cleric").selected().plan();
        var picks = advisor.fillEmptySlots(party, cleric, plan);
        assertThat(picks).hasSize(1);
        assertThat(picks.getFirst().item().id).isEqualTo("luminous-armour");
        assertThat(picks.getFirst().goal()).isTrue();
        assertThat(cleric.gear).isEmpty();
        assertThat(party.available("luminous-armour")).isOne();
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
                "/art/avatars/portraits/female/gold-dwarf/cleric.webp", "class=\"figure-glow\"")
                .doesNotContain("/art/avatars/scenes/");
        mvc.perform(post("/party/switch").param("id", "aelar")).andExpect(status().is3xxRedirection());
        var restored = new PartyStore(json, catalog, database).snapshot();
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
        var lastAdded = new Hero();
        lastAdded.name = "Kara";
        store.save(second, lastAdded, true);
        mvc.perform(post("/parties/" + original + "/switch")).andExpect(redirectedUrl("/sheet"));
        mvc.perform(post("/parties/" + second + "/switch").param("heroId", hero.id))
                .andExpect(redirectedUrl("/sheet"));
        mvc.perform(get("/sheet"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"heroes/" + hero.id + "/edit\"")));
        assertThat(new PartyStore(json, catalog, database).snapshot().active).isEqualTo(hero.id);
        store.removeHero(second, lastAdded.id);
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
    void standaloneArtworkReplacesExistingFigureAndPortraitRows(
            @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Autowired org.springframework.transaction.support.TransactionTemplate tx) throws Exception {
        Path root = temp.resolve("avatar-seed");
        String avatars = "art/avatars/";
        String key = "male/test-ancestry/ranger.webp";
        String[] targets = {avatars + "figures/" + key, avatars + "portraits/" + key};
        String[] sources = {avatars + "figures-v2/" + key,
                avatars + "figures-v2/male/test-ancestry/portraits/ranger.webp"};
        for (int i = 0; i < targets.length; i++) {
            Path file = root.resolve(targets[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[]{(byte) i});
        }
        new ForgeDatabase(jdbc, json, tx, temp.resolve("party.json").toString(), root.toString(), "test-standalone-art-paths.txt");
        for (int i = 0; i < sources.length; i++) {
            assertThat(jdbc.queryForObject("SELECT content FROM forge_asset WHERE path=?", byte[].class, "/" + sources[i]))
                    .isEqualTo(Files.readAllBytes(root.resolve(targets[i])));
        }
        for (int i = 0; i < sources.length; i++) {
            Files.createDirectories(root.resolve(sources[i]).getParent());
            Files.write(root.resolve(sources[i]), new byte[]{(byte) (i + 1)});
        }
        for (byte revision : new byte[]{1, 2}) {
            Files.write(root.resolve(sources[0]), new byte[]{revision, 3});
            new ForgeDatabase(jdbc, json, tx, temp.resolve("party.json").toString(), root.toString(), "test-asset-paths.txt");
            for (int i = 0; i < targets.length; i++) {
                byte[] expected = Files.readAllBytes(root.resolve(sources[i]));
                assertThat(jdbc.queryForObject("SELECT content FROM forge_asset WHERE path=?", byte[].class, "/" + targets[i]))
                        .isEqualTo(expected);
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected));
                assertThat(jdbc.queryForObject("SELECT digest FROM forge_asset WHERE path=?", String.class, "/" + targets[i]))
                        .isEqualTo(hash);
            }
        }
    }

    @Test
    void migratesLegacySaveAndAllowsRemovingTheLastParty(@Autowired EmbeddedPostgres postgres) throws Exception {
        Path file = temp.resolve("legacy.json");
        var oldParty = store.snapshot();
        var legacy = json.valueToTree(oldParty);
        ((ObjectNode) legacy).remove(java.util.List.of("id", "name"));
        ((ObjectNode) legacy.get("heroes").get(0)).remove("gender");
        json.writeValue(file.toFile(), legacy);
        var admin = new org.springframework.jdbc.core.JdbcTemplate(postgres.getPostgresDatabase());
        admin.execute("CREATE DATABASE migration");
        try {
            var source = postgres.getDatabase("postgres", "migration", Map.of("currentSchema", "helper"));
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            DatabaseFixture.createSchemas(jdbc);
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            var db = new ForgeDatabase(jdbc, json, tx, file.toString(), "", "test-asset-paths.txt");
            var migrated = new PartyStore(json, catalog, db);
            var original = migrated.snapshot();
            assertThat(original.heroes.getFirst().gender).isEqualTo("Gender unspecified");
            assertThat(original.stock).isEqualTo(oldParty.stock);
            assertThat(original.hero("aelar").gear).isEqualTo(oldParty.hero("aelar").gear);
            migrated.createParty("Second");
            migrated.removeParty(migrated.snapshot().id);
            assertThat(migrated.snapshot().id).isEqualTo(original.id);
            migrated.removeParty(original.id);
            var assetCount = jdbc.queryForObject("SELECT count(*) FROM forge_asset", Long.class);
            jdbc.update("UPDATE forge_asset SET content=? WHERE path=?", new byte[]{1,2,3}, "/art/test-image.svg");
            jdbc.update("""
                    UPDATE forge_reference SET body = (body - 'acquisition') || '{"summary":"Local item note"}'::jsonb
                    WHERE kind='items' AND code='titanstring-bow'
                    """);
            // Recreate the previous release's layout, including a locally customised template.
            jdbc.update("""
                    WITH old AS (DELETE FROM demo.forge_party_template RETURNING code,body)
                    INSERT INTO helper.forge_reference(kind,code,position,body)
                    SELECT 'demo',code,0,body || '{"name":"Custom demo"}'::jsonb FROM old
                    """);
            var restarted = new ForgeDatabase(jdbc, json, tx, file.toString(), "", "test-asset-paths.txt");
            var reopened = new PartyStore(json, catalog, restarted);
            var updatedBow = restarted.reference("items", Item.class).get("titanstring-bow");
            assertThat(updatedBow.summary).isEqualTo("Local item note");
            assertThat(updatedBow.acquisition).extracting(Item.Acquisition::act).containsExactly("Act 1", "Act 2");
            assertThat(reopened.newDemo().snapshot().name).isEqualTo("Custom demo");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM helper.forge_reference WHERE kind='demo'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM forge_asset", Long.class)).isEqualTo(assetCount);
            assertThat(jdbc.queryForObject("SELECT content FROM forge_asset WHERE path=?", byte[].class, "/art/test-image.svg"))
                    .containsExactly(1,2,3);
            assertThat(reopened.campaigns().parties).isEmpty();
            reopened.createParty("Fresh start");
            assertThat(reopened.snapshot().heroes).isEmpty();
        } finally {
            admin.execute("DROP DATABASE migration");
        }
    }
}
