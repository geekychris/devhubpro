package io.devportal.search;

import io.devportal.asset.Asset;
import io.devportal.asset.AssetRepository;
import io.devportal.asset.dto.AssetView;
import io.devportal.search.dto.SearchResult;
import io.devportal.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Cross-asset search. Searches asset metadata via SQL ILIKE and asset workspace docs (.md files)
 * via simple substring scan. Tuned for "good enough" — scales to a few hundred assets and
 * megabytes of docs without an external index.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_DOC_HITS = 50;
    private static final int MAX_DOC_BYTES_PER_FILE = 1_000_000;
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle", ".idea", ".vscode",
        "vendor", "out", ".mvn"
    );

    private final AssetRepository assets;
    private final JdbcClient jdbc;
    private final WorkspaceService workspace;

    public SearchService(AssetRepository assets, JdbcClient jdbc, WorkspaceService workspace) {
        this.assets = assets;
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    public SearchResult search(String query, boolean includeDocs) {
        SearchQuery sq = SearchQuery.parse(query);
        if (sq.isEmpty()) {
            return new SearchResult(query, List.of(), List.of());
        }
        // Asset side: per-term predicate combined with AND/OR depending on the parsed query.
        StringBuilder sql = new StringBuilder("SELECT * FROM asset WHERE (");
        for (int i = 0; i < sq.terms().size(); i++) {
            if (i > 0) sql.append(sq.and() ? " AND " : " OR ");
            String p = ":t" + i;
            sql.append("(id ILIKE ").append(p)
               .append(" OR name ILIKE ").append(p)
               .append(" OR coalesce(description,'') ILIKE ").append(p)
               .append(" OR coalesce(owner,'') ILIKE ").append(p)
               .append(" OR coalesce(language,'') ILIKE ").append(p)
               .append(" OR coalesce(repo_url,'') ILIKE ").append(p)
               .append(" OR EXISTS (SELECT 1 FROM unnest(tags) tg WHERE tg ILIKE ").append(p).append("))");
        }
        sql.append(") ORDER BY favorite DESC, COALESCE(rating, 0) DESC, id LIMIT 100");
        var spec = jdbc.sql(sql.toString());
        for (int i = 0; i < sq.terms().size(); i++) {
            spec = spec.param("t" + i, "%" + sq.terms().get(i) + "%");
        }
        List<Asset> matched = spec.query((rs, n) -> assetFromRow(rs)).list();
        List<AssetView> assetViews = matched.stream().map(AssetView::of).toList();

        List<SearchResult.DocHit> docs = includeDocs ? scanDocs(sq) : List.of();

        return new SearchResult(query, assetViews, docs);
    }

    private List<SearchResult.DocHit> scanDocs(SearchQuery sq) {
        List<SearchResult.DocHit> hits = new ArrayList<>();
        List<String> needles = sq.terms().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        for (Asset a : assets.findAll(null, null, null)) {
            if (hits.size() >= MAX_DOC_HITS) break;
            Path ws = workspace.workspaceFor(a.id());
            if (!Files.isDirectory(ws.resolve(".git"))) continue;
            try {
                Files.walkFileTree(ws, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (!dir.equals(ws) && SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!n.endsWith(".md")) return FileVisitResult.CONTINUE;
                        if (attrs.size() > MAX_DOC_BYTES_PER_FILE) return FileVisitResult.CONTINUE;
                        try {
                            String text = Files.readString(file);
                            String lower = text.toLowerCase(Locale.ROOT);
                            int firstHitIdx = -1;
                            String hitTerm = null;
                            if (sq.and()) {
                                // AND: every term must occur somewhere in the file.
                                for (String t : needles) {
                                    int idx = lower.indexOf(t);
                                    if (idx < 0) return FileVisitResult.CONTINUE;
                                    if (firstHitIdx < 0 || idx < firstHitIdx) {
                                        firstHitIdx = idx;
                                        hitTerm = t;
                                    }
                                }
                            } else {
                                for (String t : needles) {
                                    int idx = lower.indexOf(t);
                                    if (idx >= 0 && (firstHitIdx < 0 || idx < firstHitIdx)) {
                                        firstHitIdx = idx;
                                        hitTerm = t;
                                    }
                                }
                                if (firstHitIdx < 0) return FileVisitResult.CONTINUE;
                            }
                            int line = countLines(text, firstHitIdx);
                            String snippet = snippetAround(text, firstHitIdx, hitTerm.length());
                            hits.add(new SearchResult.DocHit(
                                a.id(),
                                ws.relativize(file).toString(),
                                line,
                                snippet
                            ));
                            if (hits.size() >= MAX_DOC_HITS) return FileVisitResult.TERMINATE;
                        } catch (IOException ignored) {}
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.debug("scan {} failed: {}", a.id(), e.getMessage());
            }
        }
        return hits;
    }

    private static int countLines(String text, int upTo) {
        int n = 1;
        for (int i = 0; i < upTo && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static String snippetAround(String text, int idx, int matchLen) {
        int start = Math.max(0, idx - 60);
        int end = Math.min(text.length(), idx + matchLen + 60);
        String s = text.substring(start, end).replace("\n", " ");
        if (start > 0) s = "…" + s;
        if (end < text.length()) s = s + "…";
        return s;
    }

    private static Asset assetFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Array tagsArr = rs.getArray("tags");
        List<String> tags = List.of();
        if (tagsArr != null) {
            Object raw = tagsArr.getArray();
            if (raw instanceof String[] s) tags = List.of(s);
        }
        java.sql.Timestamp c = rs.getTimestamp("created_at");
        java.sql.Timestamp u = rs.getTimestamp("updated_at");
        Object ratingObj = rs.getObject("rating");
        return new Asset(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("owner"),
            rs.getString("type"),
            rs.getString("language"),
            rs.getString("repo_url"),
            rs.getString("repo_default_branch"),
            tags,
            rs.getString("lifecycle"),
            rs.getString("k8s_namespace"),
            rs.getBoolean("favorite"),
            ratingObj == null ? null : rs.getInt("rating"),
            rs.getBoolean("dashboard_pinned"),
            c == null ? null : c.toInstant(),
            u == null ? null : u.toInstant()
        );
    }
}
