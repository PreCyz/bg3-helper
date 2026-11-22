package dev.forge.bg3;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
class ArtworkController {
    private final JdbcTemplate jdbc;

    ArtworkController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping({"/art/**", "/items/**"})
    ResponseEntity<byte[]> asset(HttpServletRequest request, WebRequest conditional) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        record Metadata(String type, String etag) {}
        var meta = jdbc.query("SELECT content_type,digest FROM forge_asset WHERE path=?",
                (rs, row) -> new Metadata(rs.getString(1), "\"" + rs.getString(2) + "\""), path);
        if (meta.isEmpty()) return ResponseEntity.notFound().build();
        var image = meta.getFirst();
        if (conditional.checkNotModified(image.etag())) return null;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
                .cacheControl(CacheControl.noCache()).eTag(image.etag())
                .body(jdbc.queryForObject("SELECT content FROM forge_asset WHERE path=?", byte[].class, path));
    }
}
