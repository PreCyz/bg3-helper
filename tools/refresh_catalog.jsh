/open tools/wiki.jsh

import java.text.Normalizer;
import java.util.stream.StreamSupport;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;

class GearCatalog {
    static final Path CACHE = Wiki.ROOT.resolve("target/wiki-cache");
    static final String AGENT = "OfflineBG3GearPlanner/1.0 (local snapshot)";
    final JsonNode rules;
    final Map<String,String> skillNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    record Entry(ObjectNode item, String icon) {}

    GearCatalog() throws IOException {
        rules = Wiki.JSON.readTree(Wiki.ROOT.resolve("tools/catalog-rules.json").toFile());
        for (var group : rules.get("SKILLS")) for (var name : group) skillNames.put(name.asString(),name.asString());
    }

    static String slug(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "")
                .toLowerCase(Locale.ROOT).replace("'", "").replace("+", " plus ")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static byte[] download(String path) throws Exception {
        Path cached = CACHE.resolve(Wiki.hash(path));
        if (Files.exists(cached)) return Files.readAllBytes(cached);
        byte[] bytes = Wiki.download(Wiki.SITE.resolve(path), AGENT);
        Wiki.write(cached, bytes);
        return bytes;
    }

    static String plain(Element element) {
        String markup = element.html().replaceAll("(?s)<sup\\b[^>]*>.*?</sup>", "")
                .replaceAll("<(?:br\\b[^>]*|/(?:li|dl|p|dd))>", " ")
                .replace("</dt>", ": ").replaceAll("<[^>]+>", "");
        return Parser.unescapeEntities(markup,false).replace('\u2060',' ').replace("\u200b","")
                .replaceAll("(?U)\\s+", " ").trim().replaceAll("\\(\\s*\\+?\\s*\\)", "")
                .replaceAll("\\(\\s+", "(").replaceAll("\\s+([,.:])", "$1").trim();
    }

    static String title(String text) {
        var words = new ArrayList<String>();
        for (var word : text.split("[- ]")) words.add(Character.toUpperCase(word.charAt(0))+word.substring(1));
        return String.join(" ", words);
    }

    static Matcher match(String regex, String text) { return Pattern.compile(regex).matcher(text); }

    boolean contains(String rule, String value) {
        return StreamSupport.stream(rules.get(rule).spliterator(),false).anyMatch(v -> value.equals(v.asString()));
    }

    String weapon(ObjectNode item, String kind, List<Element> cells, String summary) {
        String damage = plain(cells.get(2));
        var dice = match("(\\d+)d(\\d+)", damage);
        var fixed = match("^(\\d+)\\b", damage);
        item.put("weaponType", kind);
        if (dice.find()) item.put("diceCount", Integer.parseInt(dice.group(1))).put("die", Integer.parseInt(dice.group(2)));
        else if (fixed.find()) item.put("diceCount", Integer.parseInt(fixed.group(1))).put("die", 1);
        else return null;
        var type = cells.get(3).select("span").stream().filter(s -> s.childrenSize()==0 && !plain(s).isBlank()).findFirst();
        item.put("damageType",plain(type.orElse(cells.get(3))));
        var enchant = match("[+-]\\d+", plain(cells.get(1)));
        if (enchant.find()) item.put("enchant",Integer.parseInt(enchant.group()));
        if (contains("TWO_HAND",kind)) item.put("twoHanded",true);
        if (contains("FINESSE",kind)) item.put("finesse",true);
        if (rules.get("VERSATILE").has(kind)) item.set("versatileDie", rules.get("VERSATILE").get(kind));

        var parts = cells.get(2).select("span[class^=bg3wiki-damage-type-]");
        if (parts.size() <= 1) return summary;
        var additions = new ArrayList<String>();
        var riders = Wiki.JSON.createArrayNode();
        for (var part : parts.subList(1,parts.size())) {
            String amount = plain(part);
            String damageType = part.className().substring("bg3wiki-damage-type-".length());
            additions.add(amount+" "+damageType);
            var value = match("(\\d+)(?:d(\\d+))?", amount);
            if (value.matches()) riders.addObject().put("count", Integer.parseInt(value.group(1)))
                    .put("die", value.group(2)==null ? 1 : Integer.parseInt(value.group(2))).put("type",title(damageType));
        }
        // The Lightning Jabber's secondary die applies only when thrown.
        if (!riders.isEmpty() && !"lightning-jabber".equals(item.get("id").asString())) item.set("riders",riders);
        return "Additional listed damage: " + String.join(", ",additions) + ". " + summary;
    }

    static void addBonus(ObjectNode item, String field, String name, int amount) {
        ObjectNode modifiers = item.withObjectProperty(field);
        modifiers.put(name, modifiers.path(name).asInt(0)+amount);
    }

    void bonuses(ObjectNode item, Element effects, String summary) {
        var aliases = Map.of("armour class","ac","saving throws","save","saving throw","save",
                "initiative","initiative","attack rolls","attack","spell save dc","spellDc");
        for (var li : effects.select("li")) {
            var bonus = match("([A-Za-z ]+?)\\s*([+-]\\s*\\d+)\\.?",plain(li));
            if (!bonus.matches()) continue;
            String name = bonus.group(1).strip();
            int amount = Integer.parseInt(bonus.group(2).replace(" ",""));
            if (skillNames.containsKey(name)) addBonus(item,"skillBonuses",skillNames.get(name),amount);
            else if (name.endsWith(" Saving Throws")) {
                var ability = rules.get("ABILITIES").get(name.substring(0, name.length()-" Saving Throws".length()));
                if (ability != null) addBonus(item,"saveBonuses",ability.asString(),amount);
            } else {
                String field = aliases.get(name.toLowerCase(Locale.ROOT));
                if (field != null) item.put(field,amount);
            }
        }
        var aid = match("(?:Body Aid|Balance): Gain a \\+(\\d+) bonus to (Strength|Dexterity) Saving Throws and Ability Checks", summary);
        while (aid.find()) {
            int amount = Integer.parseInt(aid.group(1));
            String ability = rules.get("ABILITIES").get(aid.group(2)).asString();
            addBonus(item,"saveBonuses",ability,amount);
            for (var skill : rules.get("SKILLS").get(ability)) addBonus(item,"skillBonuses",skill.asString(),amount);
        }
        if (summary.contains("Stealthy: Gain a +1 bonus to Stealth Checks.")) addBonus(item,"skillBonuses","Stealth",1);
        var arcane = match("Arcane Enchantment: You gain a \\+(\\d+) bonus to Spell Save DC and Spell Attack rolls\\.",summary);
        if (arcane.find()) item.put("spellAttack",Integer.parseInt(arcane.group(1))).put("spellDc",Integer.parseInt(arcane.group(1)));
        var high = match("High Spellcasting: You gain a \\+(\\d+) bonus to Spell Save DC\\.",summary);
        if (high.find()) item.put("spellDc",Integer.parseInt(high.group(1)));
        var initiative = match("(?i)(?:Ambusher|Sentinel Weapon|Seldom Caught Unawares): (?:You gain|Gain) a \\+(\\d+) bonus to Initiative",summary);
        if (initiative.find()) item.put("initiative",Integer.parseInt(initiative.group(1)));
    }

    Entry entry(String category, String section, List<String> headers, List<Element> cells) {
        var first = cells.getFirst();
        var link = first.select("a[href^=/wiki/]").stream().filter(a -> !plain(a).isEmpty()).reduce((a,b) -> b);
        if (link.isEmpty()) return null;
        String name = plain(link.get()), id = slug(name);
        String kind = rules.get("WEAPONS").path(category).asString("");
        String slot = rules.get("SLOTS").path(category).asString(kind.toLowerCase(Locale.ROOT).contains("bow") ? "RANGED" : "MELEE");
        var rarity = match("bg3wiki-itemicon-(uncommon|rare|very-rare|legendary|story)",first.html());
        Element effects = cells.get(headers.indexOf(slot.equals("ELIXIR") ? "Effect" : "Special"));
        String summary = plain(effects);
        var item = Wiki.JSON.createObjectNode().put("id",id).put("name",name).put("slot",slot)
                .put("rarity", rarity.find() ? title(rarity.group(1)) : "Common");
        if (rules.get("POLISH_NAMES").has(id)) item.set("polishName", rules.get("POLISH_NAMES").get(id));
        item.put("summary",summary).put("source",Wiki.SITE.resolve(link.get().attr("href")).toString());
        if (!kind.isEmpty()) {
            summary = weapon(item,kind,cells,summary);
            if (summary == null) return null;
        }
        var dexCaps = Map.of("Light",99,"Medium",2,"Heavy",0);
        switch (category) {
            case "Armour" -> {
                String armor = dexCaps.keySet().stream().filter(t -> section.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT)))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown armour section: " + section));
                item.put("armorType",armor).put("baseAc",Integer.parseInt(plain(cells.get(1)))).put("dexCap",dexCaps.get(armor));
            }
            case "Clothing" -> item.put("armorType","Clothing").put("baseAc",10).put("dexCap",99);
            case "Shields" -> item.put("ac",Integer.parseInt(plain(cells.get(1))));
            case "Headwear","Footwear","Handwear" -> { if (dexCaps.containsKey(section)) item.put("armorType",section); }
        }
        if (headers.contains("Special")) bonuses(item,cells.get(headers.indexOf("Special")),summary);
        if (rules.get("BONUSES").get(id) instanceof ObjectNode overrides) item.setAll(overrides);
        item.put("summary",rules.get("ITEM_NOTES").path(id).asString(summary.isBlank() ? "Standard equipment." : summary.strip()));
        var img = first.selectFirst("img[src^=/w/images/]");
        return new Entry(item, img == null ? null : img.attr("src"));
    }

    List<Entry> category(String category) throws Exception {
        var doc = Jsoup.parse(new String(download("/wiki/"+category),StandardCharsets.UTF_8));
        doc.outputSettings().prettyPrint(false);
        var items = new ArrayList<Entry>();
        String section = "";
        for (var node : doc.select("span.mw-headline[id],table.wikitable")) {
            if (!node.tagName().equals("table")) {
                section = URLDecoder.decode(node.id().replace("+","%2B"),StandardCharsets.UTF_8).replace('_',' ');
                continue;
            }
            var rows = node.select("tr");
            if (rows.isEmpty()) continue;
            var headers = rows.getFirst().select("th").stream().map(GearCatalog::plain).toList();
            if (headers.isEmpty() || !List.of("Item","Name").contains(headers.getFirst()) || headers.contains("Race")) continue;
            for (var row : rows.subList(1,rows.size())) {
                var cells = row.children();
                if (cells.size() != headers.size()) continue;
                var item = entry(category,section,headers,cells);
                if (item != null) items.add(item);
            }
        }
        if (items.isEmpty()) throw new IOException("No item rows in " + category);
        return items;
    }

    static String saveIcon(String path) throws Exception {
        byte[] data = download(path);
        String suffix;
        if (data.length >= 12 && new String(data,0,4,StandardCharsets.US_ASCII).equals("RIFF")
                && new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP")) suffix = ".webp";
        else if (data.length >= 4 && data[0] == (byte)0x89 && data[1]=='P' && data[2]=='N' && data[3]=='G') suffix = ".png";
        else throw new IOException("Unrecognised image: " + path);
        String filename = Wiki.hash(path).substring(0,16)+suffix;
        Wiki.write(Wiki.RES.resolve("static/items").resolve(filename),data);
        return "/items/"+filename;
    }

    void run() throws Exception {
        var categories = new ArrayList<>(rules.get("SLOTS").propertyNames());
        categories.addAll(rules.get("WEAPONS").propertyNames());
        var byId = new LinkedHashMap<String,Entry>();
        for (var group : Wiki.parallel(categories,this::category))
            for (var entry : group) byId.putIfAbsent(entry.item().get("id").asString(),entry);
        var icons = byId.values().stream().map(Entry::icon).filter(Objects::nonNull).distinct().sorted().toList();
        System.out.printf("%d items with %d distinct icons.%n",byId.size(),icons.size());
        var urls = Wiki.parallel(icons,GearCatalog::saveIcon);
        var paths = new HashMap<String,String>();
        for (int i = 0; i < icons.size(); i++) paths.put(icons.get(i),urls.get(i));
        var result = new ArrayList<ObjectNode>();
        for (var entry : byId.values()) {
            if (entry.icon() != null) entry.item().put("image",paths.get(entry.icon()));
            result.add(entry.item());
        }
        result.sort(Comparator.comparing((ObjectNode item) -> item.get("slot").asString())
                .thenComparing(item -> item.get("name").asString().toLowerCase(Locale.ROOT)));
        Path destination = Wiki.RES.resolve("data/items.json");
        var indent = new DefaultIndenter("  ", "\n");
        var separators = Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER);
        var format = new DefaultPrettyPrinter(separators).withArrayIndenter(indent).withObjectIndenter(indent);
        String json = Wiki.JSON.writer().with(format).writeValueAsString(result);
        Wiki.write(destination,(json+"\n").getBytes(StandardCharsets.UTF_8));
        System.out.printf("Wrote %d records to %s%n",result.size(),destination);
    }
}

int catalogExit = 1;
try { new GearCatalog().run(); catalogExit = 0; } catch (Exception e) { e.printStackTrace(System.err); }
/exit catalogExit
