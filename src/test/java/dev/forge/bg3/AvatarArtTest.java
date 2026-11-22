package dev.forge.bg3;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarArtTest {
    @org.junit.jupiter.api.BeforeAll
    static void loadRulesFixture() throws java.io.IOException {
        DatabaseFixture.loadProgression();
    }

    private final AvatarArt avatars = new AvatarArt();

    @Test
    void everySelectableAppearanceHasLocalArtwork() throws java.io.IOException {
        var paths = DatabaseFixture.assetPaths();
        var hero = new Hero();
        hero.level = 12;
        var figures = new HashSet<String>();
        var faces = new HashSet<String>();
        var emblems = new HashSet<String>();
        var scenes = new HashSet<String>();
        for (String race : Rules.ANCESTRIES) {
            hero.race = race;
            for (String career : Rules.classes()) {
                hero.heroClass = career;
                for (String subclass : Rules.subclasses(career, hero.level)) {
                    hero.subclass = subclass;
                    var art = avatars.forHero(hero);
                    figures.add(art.figure());
                    faces.add(art.face());
                    emblems.add(art.emblem());
                    if ("None".equals(subclass)) {
                        assertThat(art.backdrop()).isNull();
                    } else {
                        scenes.add(art.backdrop());
                    }
                }
            }
        }
        int combinations = Rules.ANCESTRIES.size() * Rules.classes().size();
        int specializations = Rules.classes().stream().mapToInt(c -> Rules.subclasses(c, 12).size() - 1).sum();
        assertThat(figures).hasSize(combinations);
        assertThat(faces).hasSize(combinations);
        assertThat(scenes).hasSize(specializations);
        assertThat(emblems).hasSize(specializations + Rules.classes().size());
        for (var group : List.of(figures, faces, emblems, scenes)) {
            for (String path : group) {
                assertThat(paths.contains(path)).as(path).isTrue();
            }
        }
    }

    @Test
    void genderSelectsMatchingFiguresAndFacesAcrossEveryRaceAndClass() throws java.io.IOException {
        var paths = DatabaseFixture.assetPaths();
        var hero = new Hero();
        for (String race : Rules.ANCESTRIES) {
            hero.race = race;
            for (String career : Rules.classes()) {
                hero.heroClass = career;
                hero.gender = "Gender unspecified";
                var original = avatars.forHero(hero);
                hero.gender = "Non-binary";
                assertThat(avatars.forHero(hero)).isEqualTo(original);
                for (String gender : List.of("Male", "Female")) {
                    hero.gender = gender;
                    var art = avatars.forHero(hero);
                    String folder = "/" + gender.toLowerCase(java.util.Locale.ROOT) + "/";
                    String figures = gender.equals("Female") && race.equals("Seldarine Drow") ? "/figures-v2" : "/figures";
                    assertThat(art.figure()).isEqualTo(original.figure().replace("/figures/", figures + folder));
                    assertThat(art.face()).isEqualTo(original.face().replace("/portraits/", "/portraits" + folder));
                    for (String path : List.of(art.figure(), art.face())) {
                        assertThat(paths.contains(path)).as(path).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void changingLevelOrClassClearsAnUnavailableSpecialization() {
        var hero = new Hero();
        hero.race = "Gold Dwarf";
        hero.heroClass = "Ranger";
        hero.subclass = "Hunter";
        hero.level = 3;
        var hunter = avatars.forHero(hero);
        assertThat(hunter.description()).isEqualTo("Gold Dwarf Ranger · Hunter");
        assertThat(hunter.backdrop()).endsWith("/hunter.webp");
        hero.level = 2;
        assertThat(avatars.forHero(hero).backdrop()).isNull();
        hero.level = 5;
        hero.heroClass = "Cleric";
        var cleric = avatars.forHero(hero);
        assertThat(cleric.emblem()).endsWith("/cleric.webp");
        assertThat(cleric.figure()).isNotEqualTo(hunter.figure());
    }
}
