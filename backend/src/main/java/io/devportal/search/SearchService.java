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
        if (query == null || query.isBlank()) {
            return new SearchResult(query, List.of(), List.of());
        }
        String like = "%" + query + "%";
        // Asset side: ILIKE across many text fields, plus tags array.
        List<Asset> matched = jdbc.sql("""
            SELECT * FROM asset WHERE
              id ILIKE :q OR name ILIKE :q OR coalesce(description,'') ILIKE :q
              OR coalesce(owner,'') ILIKE :q OR coalesce(language,'') ILIKE :q
              OR coalesce(repo_url,'') ILIKE :q
              OR EXISTS (SELECT 1 FROM unnest(tags) t WHERE t ILIKE :q)
            ORDER BY id
            LIMIT 100
            """)
            .param("q", like)
            .query((rs, n) -> assetFromRow(rs))
            .list();
        List<AssetView> assetViews = matched.stream().map(AssetView::of).toList();

        List<SearchResult.DocHit> docs = includeDocs ? scanDocs(query) : List.of();

        return new SearchResult(query, assetViews, docs);
    }

    private List<SearchResult.DocHit> scanDocs(String query) {
        List<SearchResult.DocHit> hits = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
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
                            int idx = lower.indexOf(needle);
                            if (idx < 0) return FileVisitResult.CONTINUE;
                            int line = countLines(text, idx);
                            String snippet = snippetAround(text, idx, query.length());
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
            c == null ? null : c.toInstant(),
            u == null ? null : u.toInstant()
        );
    }
}
