package io.devportal.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog of tags currently in use across all assets, for autocomplete in the tag editor.
 * Sorted by recency (max(asset.updated_at) per tag) so the MRU tag is on top.
 */
@RestController
@RequestMapping("/api/tags")
public class TagCatalogController {

    private final JdbcClient jdbc;

    public TagCatalogController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TagSummary(String tag, int usageCount, Instant lastUsedAt) {}

    @GetMapping
    public List<TagSummary> list(@RequestParam(name = "q", required = false) String prefix) {
        boolean hasPrefix = prefix != null && !prefix.isBlank();
        // unnest the tags array, group by tag, count assets, and take the latest update.
        String sql = "SELECT t AS tag, COUNT(*) AS usage_count, MAX(updated_at) AS last_used_at"
            + " FROM asset, unnest(tags) t"
            + (hasPrefix ? " WHERE t ILIKE :prefix" : "")
            + " GROUP BY t"
            + " ORDER BY last_used_at DESC, usage_count DESC, t"
            + " LIMIT 200";
        var spec = jdbc.sql(sql);
        if (hasPrefix) spec = spec.param("prefix", prefix + "%");
        return spec.query((ResultSet rs, int n) -> mapRow(rs)).list();
    }

    private static TagSummary mapRow(ResultSet rs) throws SQLException {
        var t = rs.getTimestamp("last_used_at");
        return new TagSummary(
            rs.getString("tag"),
            rs.getInt("usage_count"),
            t == null ? null : t.toInstant()
        );
    }
}
