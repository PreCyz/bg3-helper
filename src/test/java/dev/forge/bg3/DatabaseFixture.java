package dev.forge.bg3;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
final class DatabaseFixture {
    @Bean(destroyMethod = "close")
    EmbeddedPostgres postgres() throws IOException {
        return EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    static void createSchemas(JdbcTemplate jdbc) {
        jdbc.execute("CREATE SCHEMA helper");
        jdbc.execute("CREATE SCHEMA demo");
    }

    @Bean
    DynamicPropertyRegistrar testProperties(EmbeddedPostgres postgres) {
        createSchemas(new JdbcTemplate(postgres.getPostgresDatabase()));
        Path save = Path.of(System.getProperty("java.io.tmpdir"), "forge-" + UUID.randomUUID() + ".json");
        return registry -> {
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres") + "&currentSchema=helper");
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "");
            registry.add("forge.asset-manifest", () -> "test-asset-paths.txt");
            registry.add("forge.save-file", save::toString);
        };
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
