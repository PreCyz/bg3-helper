/open tools/wiki.jsh
/open tools/art.jsh

class AvatarRefresh {
    static final String AGENT = "FaerunForge/1.0 (offline art snapshot)";
    record Asset(String key, URI page, URI image) {
        String credit() { return key + ": " + page + " | " + image; }
    }

    static Document page(URI address) throws Exception {
        return Jsoup.parse(new String(Wiki.download(address, AGENT), StandardCharsets.UTF_8), address.toString());
    }

    static URI original(String address) {
        return Wiki.SITE.resolve(address.replaceFirst("/thumb/(.+)/[^/]+$", "/$1"));
    }

    static String ogImage(Document doc) {
        return doc.expectFirst("meta[property=og:image]").absUrl("content");
    }

    static Map<URI,String> classIcons(String career) throws Exception {
        URI address = Wiki.SITE.resolve("/wiki/" + career);
        var doc = page(address);
        var icons = new HashMap<URI,String>();
        icons.put(address, ogImage(doc));
        for (var link : doc.select("a[href^=/wiki/]")) {
            var img = link.selectFirst("img[src]");
            if (img != null && URLDecoder.decode(img.attr("src"), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("badge_icon"))
                icons.put(URI.create(link.absUrl("href")), img.absUrl("src"));
        }
        return icons;
    }

    static void save(Asset asset, String folder, int size) throws Exception {
        byte[] bytes = Wiki.download(asset.image(), AGENT);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            Path temp = Files.createTempFile("forge-wiki-image-", ".img");
            try {
                Files.write(temp, bytes);
                source = Art.read(temp);
            } finally { Files.deleteIfExists(temp); }
        }
        Art.webp(Art.fit(source, size, size, false), Art.OUT.resolve(folder).resolve(asset.key()+".webp"), 91, 6);
    }

    static List<Asset> hirelings() throws Exception {
        var doc = page(Wiki.SITE.resolve("/wiki/Hirelings"));
        var assets = new ArrayList<Asset>();
        for (var row : doc.select("tr")) {
            var cells = row.select("> td");
            if (cells.size() < 4 || cells.get(0).selectFirst(".bg3wiki-imagetext-image") == null) continue;
            String race = cells.get(1).expectFirst("[title]").attr("title");
            String career = cells.get(2).expectFirst("[title]").attr("title");
            if (!Art.CLASSES.contains(career)) throw new IOException("Unknown hireling class: " + career);
            var first = cells.get(0);
            assets.add(new Asset(Art.slug(race)+"/"+Art.slug(career),
                    URI.create(first.expectFirst("a[href^=/wiki/]").absUrl("href")),
                    original(first.expectFirst("img[src]").absUrl("src"))));
        }
        if (assets.size() != 12) throw new IOException("Expected twelve hirelings, found " + assets.size());
        return assets;
    }

    static void run() throws Exception {
        var pages = new ArrayList<URI>();
        for (String career : Art.CLASSES) pages.add(Wiki.SITE.resolve("/wiki/"+career));
        var progression = Wiki.JSON.readTree(Art.RES.resolve("data/subclass-progression.json").toFile());
        for (var subclass : progression) pages.add(URI.create(subclass.get("source").asString()));
        var portraits = hirelings();
        var icons = new HashMap<URI,String>();
        for (var group : Wiki.parallel(Art.CLASSES, AvatarRefresh::classIcons)) icons.putAll(group);
        var emblems = Wiki.parallel(pages, address -> {
            String image = icons.get(address);
            if (image == null) image = ogImage(page(address));
            String key = Art.slug(address.toString().substring(address.toString().lastIndexOf('/')+1));
            var asset = new Asset(key, address, original(image));
            save(asset, "emblems", 160);
            return asset.credit();
        });
        var credits = Wiki.parallel(portraits, asset -> {
            save(asset, "portraits", 192);
            return asset.credit();
        });
        Collections.sort(credits);
        Collections.sort(emblems);
        String header = """
                BG3 Wiki contributors supplied the source pages and file histories below.
                Baldur's Gate 3 character portraits and game emblems are game assets of their respective owners.
                Downloaded assets were resized and converted to WebP for offline display.
                https://bg3.wiki/wiki/bg3wiki:Copyrights

                Hirelings
                """;
        String result = header + String.join("\n", credits) + "\n\nClass and subclass emblems\n" + String.join("\n", emblems) + "\n";
        Wiki.write(Art.OUT.resolve("credits.txt"), result.getBytes(StandardCharsets.UTF_8));
        System.out.printf("Saved %d portraits and %d emblems.%n", credits.size(), emblems.size());
    }
}

int avatarExit = 1;
try { AvatarRefresh.run(); avatarExit = 0; } catch (Exception e) { e.printStackTrace(System.err); }
/exit avatarExit
