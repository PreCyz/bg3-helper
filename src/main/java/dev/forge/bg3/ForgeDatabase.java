package dev.forge.bg3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

@Component
class ForgeDatabase {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    ForgeDatabase(JdbcTemplate jdbc, ObjectMapper json, TransactionTemplate transactions,
                  @Value("${forge.save-file}") String legacyFile,
                  @Value("${forge.asset-directory:}") String assetDirectory,
                  @Value("${forge.asset-manifest:asset-paths.txt}") String manifest) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        transactions.executeWithoutResult(status -> {
            // Serialize first startup, including DDL, across application instances.
            jdbc.execute("SELECT pg_advisory_xact_lock(6032174159041)");
            jdbc.execute((ConnectionCallback<Void>) con -> {
                ScriptUtils.executeSqlScript(con, new ClassPathResource("db/forge.sql"));
                return null;
            });
            try {
                seedReference();
                seedAssets(assetDirectory, manifest);
                if (jdbc.update("INSERT INTO forge_campaign(id) VALUES (1) ON CONFLICT DO NOTHING") == 1) {
                    var file = Path.of(legacyFile);
                    if (Files.exists(file)) writeCampaigns(importCampaigns(json, file), Set.of());
                }
            } catch (IOException ex) {
                throw new UncheckedIOException("Unable to seed the Forge database", ex);
            }
        });
    }

    static PartyStore.Campaigns importCampaigns(ObjectMapper json, Path legacy) throws IOException {
        var node = json.readTree(legacy.toFile());
        if (node.has("parties")) return json.treeToValue(node, PartyStore.Campaigns.class);
        var campaigns = new PartyStore.Campaigns();
        campaigns.add(json.treeToValue(node, PartyStore.Party.class));
        return campaigns;
    }

    private JsonNode seed(String name) throws IOException {
        try (var in = new ClassPathResource("data/" + name + ".json").getInputStream()) {
            return json.readTree(in);
        }
    }

    private void seedReference() throws IOException {
        List<Object[]> rows = new ArrayList<>();
        for (String kind : List.of("items", "builds")) {
            int position = 0;
            for (var node : seed(kind))
                rows.add(new Object[]{kind, node.required("id").asString(), position++, node.toString()});
        }
        for (var entry : seed("subclass-progression").properties())
            rows.add(new Object[]{"progression", entry.getKey(), 0, entry.getValue().toString()});
        int position = 0;
        for (var node : seed("equipment-synergies")) {
            String key = node.required("enablers") + "/" + node.required("partners");
            rows.add(new Object[]{"synergies", key, position++, node.toString()});
        }
        jdbc.batchUpdate("INSERT INTO forge_reference(kind,code,position,body) VALUES (?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING", rows);
        // Refresh only the acquisition field of each existing or newly seeded item.
        jdbc.update("""
                UPDATE forge_reference r
                SET body = jsonb_set(r.body, '{acquisition}', source.value)
                FROM jsonb_each(CAST(? AS jsonb)) AS source
                WHERE r.kind = 'items' AND r.code = source.key
                  AND r.body->'acquisition' IS DISTINCT FROM source.value
                """, seed("item-acquisitions").toString());
        jdbc.update("INSERT INTO demo.forge_party_template(code,body) VALUES ('aelar',CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                seed("demo-party").toString());
    }

    PartyStore.Party demoParty() {
        return jdbc.queryForObject("SELECT body FROM demo.forge_party_template WHERE code='aelar'",
                (rs, row) -> json.readValue(rs.getString(1), PartyStore.Party.class));
    }

    private void seedAssets(String directory, String manifest) throws IOException {
        Map<String, String> existing = new HashMap<>();
        jdbc.query("SELECT path, digest FROM forge_asset", (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> existing.put(rs.getString(1), rs.getString(2)));
        var paths = new TreeSet<>(new ClassPathResource(manifest).getContentAsString(StandardCharsets.UTF_8).lines().toList());
        var resolver = new PathMatchingResourcePatternResolver();
        Path root = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
        // Pick up new assets even before the shipped inventory has been regenerated.
        for (String folder : List.of("art", "items")) {
            for (Resource resource : resolver.getResources("classpath*:static/" + folder + "/**/*.*")) {
                String url = resource.getURL().toExternalForm();
                paths.add(url.substring(url.lastIndexOf("/static/") + "/static".length()));
            }
            if (root != null && Files.isDirectory(root.resolve(folder))) {
                try (var files = Files.walk(root.resolve(folder))) {
                    files.filter(Files::isRegularFile).forEach(f -> paths.add("/" + root.relativize(f).toString().replace('\\', '/')));
                }
            }
        }
        List<Object[]> batch = new ArrayList<>(32);
        for (String path : paths) {
            String previous = existing.get(path);
            if (previous != null && !path.startsWith("/art/avatars/figures-v2/")) continue;
            byte[] bytes = assetBytes(root, path);
            // The importer also writes each standalone image to its tracked application path.
            // A checkout can seed the versioned rows from those copies.
            if (bytes == null && path.startsWith("/art/avatars/figures-v2/")) {
                String key = path.substring("/art/avatars/figures-v2/".length());
                String canonical = key.contains("/portraits/") ? "portraits/" + key.replace("/portraits/", "/") : "figures/" + key;
                bytes = assetBytes(root, "/art/avatars/" + canonical);
            }
            if (bytes == null && previous != null) continue;
            if (bytes == null) throw new IOException("Missing seed asset " + path
                    + ". Restore the artwork database backup or supply the art and items folders via forge.asset-directory.");
            String hash = digest(bytes);
            if (hash.equals(previous)) continue;
            String type = org.springframework.http.MediaTypeFactory.getMediaType(path)
                    .orElse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM).toString();
            batch.add(new Object[]{path, type, hash, bytes});
            if (batch.size() == 32) insertAssets(batch);
        }
        if (!batch.isEmpty()) insertAssets(batch);
        // Keep existing hero and roster image URLs valid when upgrading a populated database.
        jdbc.update("""
                UPDATE forge_asset current_art
                SET content = new_art.content, digest = new_art.digest, content_type = new_art.content_type
                FROM forge_asset new_art
                WHERE starts_with(new_art.path, '/art/avatars/figures-v2/')
                  AND current_art.path = CASE WHEN strpos(new_art.path, '/portraits/') > 0
                      THEN replace(replace(new_art.path, '/portraits/', '/'), '/figures-v2/', '/portraits/')
                      ELSE replace(new_art.path, '/figures-v2/', '/figures/') END
                  AND current_art.digest IS DISTINCT FROM new_art.digest
                """);
    }

    private static byte[] assetBytes(Path root, String path) throws IOException {
        if (root != null) {
            Path local = root.resolve(path.substring(1));
            if (Files.isRegularFile(local)) return Files.readAllBytes(local);
        }
        var resource = new ClassPathResource("static" + path);
        return resource.exists() ? resource.getContentAsByteArray() : null;
    }

    private void insertAssets(List<Object[]> batch) {
        jdbc.batchUpdate("""
                INSERT INTO forge_asset(path,content_type,digest,content) VALUES (?,?,?,?)
                ON CONFLICT (path) DO UPDATE SET content_type=excluded.content_type, digest=excluded.digest, content=excluded.content
                WHERE forge_asset.digest IS DISTINCT FROM excluded.digest
                """, batch);
        batch.clear();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    <T> Map<String, T> reference(String kind, Class<T> type) {
        Map<String, T> entries = new LinkedHashMap<>();
        jdbc.query("SELECT code,body FROM forge_reference WHERE kind=? ORDER BY position,code",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> entries.put(rs.getString(1), json.readValue(rs.getString(2), type)), kind);
        return Collections.unmodifiableMap(entries);
    }

    PartyStore.Campaigns campaigns() {
        var result = new PartyStore.Campaigns();
        jdbc.query("""
                SELECT c.active_party,p.body FROM forge_campaign c LEFT JOIN forge_party p ON true
                WHERE c.id=1 ORDER BY p.position
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            result.activeParty = rs.getString(1);
            if (rs.getString(2) != null) result.parties.add(json.readValue(rs.getString(2), PartyStore.Party.class));
        });
        return result;
    }

    void change(Consumer<PartyStore.Campaigns> action) {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM forge_campaign WHERE id=1 FOR UPDATE", Integer.class);
            var campaigns = campaigns();
            var previous = new HashSet<String>();
            campaigns.parties.forEach(p -> previous.add(p.id));
            action.accept(campaigns);
            writeCampaigns(campaigns, previous);
        });
    }

    private void writeCampaigns(PartyStore.Campaigns campaigns, Set<String> previousIds) {
        var removed = new HashSet<>(previousIds);
        int position = 0;
        for (var party : campaigns.parties) {
            removed.remove(party.id);
            jdbc.update("""
                    INSERT INTO forge_party(id,position,body) VALUES (?,?,CAST(? AS jsonb))
                    ON CONFLICT(id) DO UPDATE SET position=excluded.position,body=excluded.body
                    WHERE (forge_party.position,forge_party.body) IS DISTINCT FROM (excluded.position,excluded.body)
                    """, party.id, position++, json.writeValueAsString(party));
        }
        for (String id : removed) jdbc.update("DELETE FROM forge_party WHERE id=?", id);
        jdbc.update("UPDATE forge_campaign SET active_party=? WHERE id=1", campaigns.activeParty);
    }
}
