/open tools/wiki.jsh

class AcquisitionImport {
    static final Path CACHE = Wiki.ROOT.resolve("target/wiki-cache/acquisitions");
    static final String AGENT = "OfflineBG3GearPlanner/1.0 (item acquisition snapshot)";
    final Map<String,Set<String>> acts = new HashMap<>();

    byte[] cached(URI uri) throws Exception {
        Path file = CACHE.resolve(Wiki.hash(uri.toASCIIString()));
        if (!Files.exists(file)) Wiki.write(file, Wiki.download(uri, AGENT));
        return Files.readAllBytes(file);
    }

    void loadActs() throws Exception {
        for (var entry : Map.of("One", "Act 1", "Two", "Act 2", "Three", "Act 3").entrySet()) {
            acts.put("Act " + entry.getKey(), Set.of(entry.getValue()));
            String next = "";
            do {
                String url = "/w/api.php?action=query&format=json&list=categorymembers&cmnamespace=0&cmlimit=500"
                        + "&cmtitle=Category:Act_" + entry.getKey() + "_Locations" + next;
                var result = Wiki.JSON.readTree(cached(Wiki.SITE.resolve(url)));
                for (var member : result.required("query").required("categorymembers"))
                    acts.computeIfAbsent(member.required("title").asString(), k -> new TreeSet<>()).add(entry.getValue());
                var more = result.path("continue").path("cmcontinue").asString("");
                next = more.isEmpty() ? "" : "&cmcontinue=" + URLEncoder.encode(more, StandardCharsets.UTF_8);
            } while (!next.isEmpty());
        }
    }

    void addRoute(Element block, String context, Set<String> parentActs, ArrayNode routes) {
        var children = new ArrayList<Element>();
        for (var list : block.select("> ul, > ol")) {
            children.addAll(list.children());
            list.remove();
        }
        String text = block.text().replaceAll("[\\u200b\\u2060\\ufeff]", "").replaceAll("(?U)\\s+", " ").strip();
        String details = (context + " " + text).strip();
        var found = new TreeSet<>(parentActs);
        for (var link : block.select("a[title]")) found.addAll(acts.getOrDefault(link.attr("title"), Set.of()));
        if (!children.isEmpty()) {
            for (var child : children) addRoute(child, details, found, routes);
        } else if (!details.isEmpty()) {
            var route = routes.addObject().put("details", details);
            if (found.size() == 1) route.put("act", found.first());
        }
    }

    ArrayNode routes(JsonNode item) throws Exception {
        var page = Jsoup.parse(new String(cached(URI.create(item.required("source").asString())), StandardCharsets.UTF_8));
        var section = page.getElementById("Where_to_find");
        var result = Wiki.JSON.createArrayNode();
        if (section == null) return result;
        var box = section.closest("h2,h3,h4").nextElementSibling();
        if (box == null || !box.hasClass("bg3wiki-tooltip-box"))
            throw new IOException("Unexpected acquisition markup for " + item.required("name").asString());
        box.select("sup.reference,.mw-editsection,style,script").remove();
        for (var node : box.children()) {
            if (node.is("ul,ol")) {
                for (var li : node.children()) addRoute(li, "", Set.of(), result);
            } else addRoute(node, "", Set.of(), result);
        }
        if (result.isEmpty()) throw new IOException("Empty acquisition section for " + item.required("name").asString());
        return result;
    }

    void fillFromTreasureTable(String title, String description, String sourcePage,
                               Map<String,ArrayNode> byPath) throws Exception {
        var uri = Wiki.SITE.resolve("/wiki/" + title.replace(' ', '_'));
        var page = Jsoup.parse(new String(cached(uri), StandardCharsets.UTF_8));
        var cells = page.select("table.wikitable tr > :first-child a[href^=/wiki/]");
        if (cells.isEmpty()) throw new IOException("No drop table for " + title);
        for (var link : cells) {
            var routes = byPath.get(link.attr("href"));
            if (routes != null && routes.isEmpty()) routes.addObject().put("details", description)
                    .put("source", Wiki.SITE.resolve("/wiki/" + sourcePage.replace(' ', '_')).toString());
        }
    }

    void run() throws Exception {
        loadActs();
        var items = Wiki.JSON.readTree(Wiki.RES.resolve("data/items.json").toFile()).valueStream().toList();
        var rows = Wiki.parallel(items, this::routes);
        if (rows.stream().allMatch(ArrayNode::isEmpty)) throw new IOException("No wiki acquisition sections found");
        var snapshot = Wiki.JSON.createObjectNode();
        var byPath = new HashMap<String,ArrayNode>();
        var supplements = Wiki.JSON.readTree(Wiki.ROOT.resolve("tools/acquisition-fallbacks.json").toFile());
        for (int i = 0; i < rows.size(); i++) {
            String id = items.get(i).required("id").asString();
            var routes = rows.get(i);
            if (routes.isEmpty() && supplements.has(id)) routes.add(supplements.get(id));
            byPath.put(URI.create(items.get(i).required("source").asString()).getRawPath(), routes);
            snapshot.set(id, routes);
        }
        String traders = "May appear in Dammon’s rotating stock in the Emerald Grove (Act 1) or at Last Light Inn (Act 2).";
        for (String kind : List.of("Martial", "Simple"))
            fillFromTreasureTable(kind + " Melee Weapon Subtable", traders, "Melee Weapon Trader Table", byPath);
        for (String table : List.of("Boots Variation Table", "Shoes Variation Table", "Medium Hat Table", "Martial Ranged Weapon Subtable"))
            fillFromTreasureTable(table, "Possible random loot from the " + table + ".", table, byPath);
        long documented = rows.stream().filter(r -> !r.isEmpty()).count();
        Wiki.write(Wiki.RES.resolve("data/item-acquisitions.json"),
                (Wiki.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.printf("Found acquisition details for %d of %d catalog items.%n", documented, items.size());
    }
}

int acquisitionExit = 1;
try { new AcquisitionImport().run(); acquisitionExit = 0; } catch (Exception e) { e.printStackTrace(System.err); }
/exit acquisitionExit
