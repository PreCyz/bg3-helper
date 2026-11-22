package dev.forge.bg3;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class DatabaseFixture {
    static EmbeddedPostgres start() throws IOException {
        var postgres = EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(postgres.getPostgresDatabase());
        jdbc.execute("CREATE SCHEMA helper");
        jdbc.execute("CREATE SCHEMA demo");
        jdbc.execute("ALTER ROLE postgres IN DATABASE postgres SET search_path TO helper");
        return postgres;
    }

    static void configure(DynamicPropertyRegistry registry) {
        try {
            var postgres = start();
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "");
            registry.add("forge.asset-manifest", () -> "test-asset-paths.txt");
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static Set<String> assetPaths() throws IOException {
        try (var in = DatabaseFixture.class.getResourceAsStream("/asset-paths.txt")) {
            return Set.copyOf(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
    }

    static void loadProgression() throws IOException {
        try (var in = DatabaseFixture.class.getResourceAsStream("/data/subclass-progression.json")) {
            Rules.useProgression(JsonMapper.builder().build().readValue(in, new TypeReference<>() {}));
        }
    }
}
