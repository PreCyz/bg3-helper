# Faerûn Forge

A localhost Baldur’s Gate 3 party and equipment planner built with Java 25, Spring Boot, Thymeleaf and Bootstrap.

## Run on Windows

Install PostgreSQL and create a dedicated database. In psql, connected as a local administrator, run the following once. The password prompt keeps the password out of SQL history.

```sql
CREATE ROLE bg3_forge LOGIN;
\password bg3_forge
CREATE DATABASE bg3_forge OWNER bg3_forge ENCODING 'UTF8' TEMPLATE template0;
\connect bg3_forge
CREATE SCHEMA helper AUTHORIZATION bg3_forge;
CREATE SCHEMA demo AUTHORIZATION bg3_forge;
```

Copy `database.properties.example` to `data/database.properties` and set the password there. Create the `data` directory first on a fresh checkout. Spring also accepts `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`. Run from the project directory so the application can find the config and any existing save.

From this directory:

```powershell
mvn package
java -jar target\bg3-helper-1.0.0.jar
```

Open [the start page](http://127.0.0.1:8080). The executable JAR includes Bootstrap, the equipment catalog, the images, the build guides and the rules. Building for the first time requires Maven dependencies. The local PostgreSQL service must be running. The app works without an internet connection. Stop the server with Ctrl+C.

Maven embeds the project version and seven-character Git commit hash in the application. The header shows them beside the language selector. When running from IntelliJ, run the Maven `process-resources` phase after changing commits to refresh the label.

Logback writes console output and appends to `logs/bg3-helper.YYYY-MM-DD.log` in the working directory. It starts a new file on the first log entry of each local calendar day and appends to the same file after a restart that day. Set `LOGGING_FILE_PATH` to use a different directory.

## Try it

Open [the editable UI navigation diagram](docs/ui-navigation.drawio) in draw.io to see where each action leads. On the Parties screen, click a hero’s portrait or name to open their sheet, then choose **Edit hero** to develop that character.

1. Choose **Create party** to start your own party, or **Try demo** to explore Aelar’s level 5 Gloom Stalker build. The demo runs in your browser session. Choose **Exit demo** to discard your demo changes and return to the start page. Opening it again restores Aelar’s original loadout. In the demo, open Aelar’s equipment sheet. Click the head of the mannequin, or a labelled slot, to see compatible items in your party inventory. Change the ranking objective and compare the resulting statistics before equipping anything.
2. In **Inventory → Wiki catalog**, search for an item such as Titanstring Bow. Add the number you own. A copy assigned to a hero becomes unavailable to the rest of the party until you unequip it.
3. Choose **New hero**, or choose **Use build** in the build library. Edit identity, base scores, level, skills and feats. The preview updates while you work. **Update preview** and the step buttons also work with JavaScript disabled.
4. **My party** switches the active hero. **Edit hero** previews development against the saved character. Set an elixir in the equipment view to compare its effects.
5. Open the party menu and choose **Manage parties** to create or switch parties, remove a hero, or remove a whole party. New parties start empty. Removing a hero frees their equipment for the remaining heroes. Removing a party also deletes its inventory. Both actions show a confirmation before saving.
6. Choose one of the 11 playable races, then a subrace if that race has one. For example, **Drow → Seldarine Drow** and **Half-Elf → Drow Half-Elf** are distinct choices. Changing ancestry, gender, class or subclass updates the avatar preview. All artwork is bundled for offline use.
7. On the hero sheet, choose **Development tracks** beside **Edit hero**. The 15 guides cover all 12 classes. The sheet shows matching subclasses and classes first. **Follow this track** saves the guide for that hero and highlights the next level for a matching class. To create a hero with preset ability scores, subclass and class skills, choose **Build library → Use build**.
8. Choose **Create build** on a hero sheet to save that hero’s current setup, including equipped gear. Open **Personal development tracks** on any hero sheet to view or follow a saved build. Later changes to the original hero leave the saved build intact. You can expand or collapse either track section.
9. For a new playthrough, open **New hero**, choose your ancestry and class, and check **Build advisor**. Compare the three closest builds or select any development track. On each level-up, update the class level in **Edit hero** and record the guide spells, cantrips or techniques you use in **Development**. The advisor shows the current and next class-level decisions, feat and ability targets, and equipment goals. Its inventory suggestions fill empty slots with compatible available gear. Personal tracks use the saved character as their target. The 15 built-in guides also include a level-by-level plan.

## Data and calculations

Open **Synergies with your inventory** to see pairings from the current party. Available copies and gear worn by the selected hero take priority over items assigned to another hero. Open **Equipment synergies** on the same card to compare companions from the offline wiki catalog. Each pick explains its pairing or modeled benefit and shows its party availability. The two chest rows offer armour and clothing alternatives.

PostgreSQL stores each party in `forge_party`, including its heroes, inventories, equipped slots and development choices. The app commits each change in one transaction. On first initialization it imports `data/party.json` if present, including older single-party saves. Use `--forge.save-file=C:/Games/BG3/my-party.json` to import from a different location. This file is only an import source. The demo starts from Aelar’s template in `demo.forge_party_template`. Each browser session receives a temporary copy. Personal parties are stored in `helper.forge_party`.

Startup creates the [tables](src/main/resources/db/forge.sql) and inserts missing item, build, progression, synergy, demo and artwork records. The acquisition snapshot updates the “Where to find” field on inventory cards, including alternate sources and quest conditions. Other existing catalog fields retain their database values. Images are served from `forge_asset` at their original URLs. The tracked `asset-paths.txt` lists the expected seed files. A fresh database needs the artwork from a local checkout, a JAR built with those assets, or a database backup. To seed from outside the JAR set `forge.asset-directory` to a directory containing `art/` and `items/`. Additional files found there are also imported. A populated database serves artwork when the original image files are absent.

To back up everything, use `pg_dump -Fc -h localhost -U bg3_forge -d bg3_forge -f forge.dump`. Restore into a **new, empty** database owned by the application role with `pg_restore --no-owner -h localhost -U bg3_forge -d bg3_forge_restored forge.dump`, then configure the app to use that database. You can check the first import with `SELECT count(*), sum(octet_length(content)) FROM forge_asset;`. The WebP files can then be untracked in Git while keeping the working copies.

Bundled JSON snapshots from BG3 Wiki supply the initial equipment records. Item cards link to their source. To rebuild the snapshot, use the [JShell maintenance tools](#refresh-equipment-and-artwork). See [catalog credits](src/main/resources/static/catalog-credits.txt) for attribution.

In the wizard’s Identity step, pick a starting class, then use **Additional class → Add class** for each new class in the order you first take it. Set the levels in each class, up to 12 combined. Choose each unlocked subclass in Development. Hit points, feats and class features follow individual class levels. Proficiency and cantrip damage follow total character level. The starting class supplies saving throw proficiencies, and the last newly added class supplies the casting ability for item spells. [BG3 Wiki multiclassing rules](https://bg3.wiki/wiki/Classes#Multiclassing)

The character sheet models ordinary melee and ranged hits, armour class, hit points, movement, initiative, saving throws, skills, and example spell or class-action damage. Base scores include character-creation bonuses and exclude feats or equipped items. Averages per hit assume a successful ordinary hit. Target AC, enemy resistances, critical hits, conditional triggers and turn-by-turn encounters can change actual combat results. Item summaries and build milestones describe effects that require situational play.

Each starter build links to its community guide and BG3 Wiki subclass page. The level recommendations cover a single class through level 12. Suggested equipment is a shopping list and must be added to the party inventory to equip it.

## Language

The selector to the left of the party menu switches between English and Polish.
The browser remembers the choice. Both languages work offline, and equipment
search accepts names in either language. The Polish item descriptions are fan
translations of the wiki snapshot. Interface messages and game translations are
stored in the [resource bundles](src/main/resources/i18n).

## Refresh equipment and artwork

Run these optional JDK 25 JShell scripts from the project directory. The wiki importers use jsoup and Jackson. Prepare their libraries once with Maven, and repeat this step after cleaning `target`. The artwork scripts also use `ffmpeg` with the `libwebp` encoder on your PATH.

```powershell
mvn -f tools/pom.xml dependency:copy-dependencies
jshell --class-path "target/tools/dependency/*" tools/refresh_catalog.jsh
jshell --class-path "target/tools/dependency/*" tools/refresh_acquisitions.jsh
jshell --class-path "target/tools/dependency/*" tools/refresh_avatar_art.jsh
```

The catalog script rebuilds `src/main/resources/data/items.json` and the item icons. It reuses downloads in `target/wiki-cache`. Remove that cache for a fresh wiki snapshot. The avatar script downloads class and subclass emblems and hireling portraits, then updates their credits.

To replace an avatar with standalone artwork, place a transparent PNG under an input directory using its character path, for example `male/wood-elf/ranger.png` or `human/bard.png` for the unspecified appearance. Run the importer with that directory:

```powershell
jshell "-R-Davatar.input=C:/Art/Standalone" tools/import_standalone_avatars.jsh
```

It exports a 640 × 960 figure and a matching roster portrait under `figures-v2`, updates the existing image files and records the new paths in the asset manifest. The next application startup publishes the replacements to PostgreSQL at the existing image URLs.

To import a local illustration sheet, pass the race and image path as JShell execution properties. Quote the whole argument when a value contains spaces.

```powershell
jshell "-R-Drace=Wood Elf" "-R-Datlas=C:/Art/Wood Elf.png" tools/import_avatar_atlas.jsh
jshell "-R-Drace=Wood Elf" "-R-Datlas=C:/Art/Wood Elf Pairs.png" -R-Dpaired=true tools/import_avatar_atlas.jsh
```

Use four columns and three rows for the standard sheet, with one illustration per class in alphabetical class order, read left to right. For the paired sheet, use six columns and four rows, alternating male and female for each class. Both layouts produce framed figures and portraits. The standard layout preserves credited hireling portraits.
