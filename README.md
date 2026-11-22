# Faerûn Forge

A localhost Baldur’s Gate 3 party and equipment planner built with Java 25, Spring Boot, Thymeleaf and Bootstrap.

## Run on Windows

From this directory:

```powershell
mvn package
java -jar target\bg3-helper-1.0.0.jar
```

Open [the start page](http://127.0.0.1:8080). The executable JAR includes Bootstrap, the equipment catalog, the images, the build guides and the rules. Building for the first time requires Maven dependencies. Running the packaged JAR works offline. Stop the server with Ctrl+C.

## Try it

For NAS installation, see [Deploy on Synology](#deploy-on-synology).

1. Choose **Create party** to start your own party, or **Try demo** to explore Aelar’s level 5 Gloom Stalker build. The demo runs in your browser session. Choose **Exit demo** to discard your demo changes and return to the start page. Opening it again restores Aelar’s original loadout. In the demo, open Aelar’s equipment sheet. Click the head of the mannequin, or a labelled slot, to see compatible items in your party inventory. Change the ranking objective and compare the resulting statistics before equipping anything.
2. In **Inventory → Wiki catalog**, search for an item such as Titanstring Bow. Add the number you own. A copy assigned to a hero becomes unavailable to the rest of the party until you unequip it.
3. Choose **New hero**, or choose **Use build** in the build library. Edit identity, base scores, level, skills and feats. The preview updates while you work. **Update preview** and the step buttons also work with JavaScript disabled.
4. **My party** switches the active hero. **Edit hero** previews development against the saved character. Set an elixir in the equipment view to compare its effects.
5. Open the party menu and choose **Manage parties** to create or switch parties, remove a hero, or remove a whole party. New parties start empty. Removing a hero frees their equipment for the remaining heroes. Removing a party also deletes its inventory. Both actions show a confirmation before saving.
6. Choose one of the 11 playable races, then a subrace if that race has one. For example, **Drow → Seldarine Drow** and **Half-Elf → Drow Half-Elf** are distinct choices. Changing ancestry, gender, class or subclass updates the avatar preview. All artwork is bundled for offline use.
7. On the hero sheet, choose **Development tracks** beside **Edit hero**. The 15 guides cover all 12 classes. The sheet shows matching subclasses and classes first. **Follow this track** saves the guide for that hero and highlights the next level for a matching class. To create a hero with preset ability scores, subclass and class skills, choose **Build library → Use build**.

## Data and calculations

Open **Equipment synergies** on a slot’s item card to compare companions from the offline wiki catalog. Each pick explains its pairing or modeled benefit and shows its party availability. The two chest rows offer armour and clothing alternatives.

Your saved parties, their inventories and their active heroes are saved together in `data/party.json`, relative to the directory from which the app starts. An existing single-party save is automatically imported as **My party**. To back up all parties, copy that file. An alternative save location can be supplied with `--forge.save-file=C:/Games/BG3/my-party.json`.

Equipment is a bundled JSON snapshot of BG3 Wiki. Item cards link to their source. To rebuild the snapshot, use the [JShell maintenance tools](#refresh-equipment-and-artwork). See [catalog credits](src/main/resources/static/catalog-credits.txt) for attribution.

The character sheet models single-class levels 1–12, ordinary melee and ranged hits, armour class, hit points, movement, initiative, saving throws, skills, and example spell or class-action damage. Base scores include character-creation bonuses and exclude feats or equipped items. Averages per hit assume a successful ordinary hit. Target AC, enemy resistances, critical hits, conditional triggers, multiclass combinations and turn-by-turn encounters can change actual combat results. Item summaries and build milestones describe effects that require situational play.

Each starter build links to its community guide and BG3 Wiki subclass page. The level recommendations cover a single class through level 12. Suggested equipment is a shopping list and must be added to the party inventory to equip it.

## Refresh equipment and artwork

Run these optional JDK 25 JShell scripts from the project directory. The two wiki importers use jsoup and Jackson. Prepare their libraries once with Maven, and repeat this step after cleaning `target`. The artwork scripts also use `ffmpeg` with the `libwebp` encoder on your PATH.

```powershell
mvn -f tools/pom.xml dependency:copy-dependencies
jshell --class-path "target/tools/dependency/*" tools/refresh_catalog.jsh
jshell --class-path "target/tools/dependency/*" tools/refresh_avatar_art.jsh
```

The catalog script rebuilds `src/main/resources/data/items.json` and the item icons. It reuses downloads in `target/wiki-cache`. Remove that cache for a fresh wiki snapshot. The avatar script downloads class and subclass emblems and hireling portraits, then updates their credits.

To import a local illustration sheet, pass the race and image path as JShell execution properties. Quote the whole argument when a value contains spaces.

```powershell
jshell "-R-Drace=Wood Elf" "-R-Datlas=C:/Art/Wood Elf.png" tools/import_avatar_atlas.jsh
jshell "-R-Drace=Wood Elf" "-R-Datlas=C:/Art/Wood Elf Pairs.png" -R-Dpaired=true tools/import_avatar_atlas.jsh
```

Use four columns and three rows for the standard sheet, with one illustration per class in alphabetical class order, read left to right. For the paired sheet, use six columns and four rows, alternating male and female for each class. Both layouts produce framed figures and portraits. The standard layout preserves credited hireling portraits.

## Deploy on Synology

Use a 64-bit Intel, AMD or ARM NAS that supports Container Manager. Check the model list and install the package in DSM Package Center. The project uses the official Java 25 runtime image. The NAS downloads it on first start and can then run the app offline. [Synology supported models](https://www.synology.com/en-global/dsm/packages/ContainerManager) · [Eclipse Temurin images](https://hub.docker.com/_/eclipse-temurin)

1. In File Station, create a project folder such as `docker/bg3-helper`. Upload and extract the deployment ZIP so that `app.jar`, `docker-compose.yml` and the `data` folder sit directly inside it. If preparing the files yourself, run `mvn package` on your computer, copy `target/bg3-helper-1.0.0.jar` as `app.jar`, copy [docker-compose.yml](deploy/synology/docker-compose.yml), and create an empty `data` folder.
2. To bring your existing parties, copy your computer’s `data/party.json` into the NAS project’s `data` folder before first start. An empty folder gives you the new-party and demo start page.
3. In **Container Manager → Project → Create**, enter `bg3-helper` and select that project folder. Use the included `docker-compose.yml` as the project configuration. If DSM asks for an upload, select the same file from the extracted ZIP on your computer. Leave Web Station disabled and start the project when the wizard finishes. [Synology project guide](https://kb.synology.com/en-global/DSM/help/ContainerManager/docker_project)
4. Open `http://YOUR-NAS-IP:18080/` from a device on your home network. If port 18080 is occupied, change the first port in `"18080:8080"` before starting. If DSM Firewall is enabled, allow the chosen TCP port from your LAN. Keep access on your LAN or VPN because the app has no sign-in.

Party saves are in `data/party.json` on the NAS. Include that folder in your backups. For an update, stop the project, replace only `app.jar`, then build and start the project again. The container is configured to restart after a NAS reboot unless you manually stop it.

