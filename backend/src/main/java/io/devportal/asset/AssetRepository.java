package io.devportal.asset;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {

    private final JdbcClient jdbc;

    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Asset> ASSET = (ResultSet rs, int rowNum) -> new Asset(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("owner"),
        rs.getString("type"),
        rs.getString("language"),
        rs.getString("repo_url"),
        rs.getString("repo_default_branch"),
        readStringArray(rs, "tags"),
        rs.getString("lifecycle"),
        rs.getString("k8s_namespace"),
        rs.getBoolean("favorite"),
        rs.getObject("rating") == null ? null : rs.getInt("rating"),
        rs.getBoolean("dashboard_pinned"),
        toInstant(rs.getTimestamp("created_at")),
        toInstant(rs.getTimestamp("updated_at"))
    );

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static List<String> readStringArray(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) return List.of();
        Object raw = a.getArray();
        if (raw instanceof String[] s) return List.of(s);
        return List.of();
    }

    private static final RowMapper<Dependency> DEPENDENCY = (ResultSet rs, int rowNum) -> new Dependency(
        rs.getLong("id"),
        rs.getString("consumer_id"),
        rs.getString("producer_id"),
        rs.getString("version_ref"),
        rs.getString("kind")
    );

    public List<Asset> findAll(String query, String type, String lifecycle, Boolean favorite) {
        io.devportal.search.SearchQuery sq = io.devportal.search.SearchQuery.parse(query);
        boolean hasTerms = !sq.isEmpty();

        StringBuilder sql = new StringBuilder("SELECT * FROM asset WHERE 1=1");
        if (hasTerms) {
            sql.append(" AND (");
            for (int i = 0; i < sq.terms().size(); i++) {
                if (i > 0) sql.append(sq.and() ? " AND " : " OR ");
                sql.append(termPredicate(i));
            }
            sql.append(")");
        }
        if (type != null && !type.isBlank()) sql.append(" AND type = :type");
        if (lifecycle != null && !lifecycle.isBlank()) sql.append(" AND lifecycle = :lifecycle");
        if (favorite != null) sql.append(" AND favorite = :favorite");

        // Ranking: favorites and high-rated assets surface first; for queries we bias by best
        // match across all terms. The "score" expression is a sum of per-term mins (lower=better),
        // plus negative offsets for favorite/rating so they outrank ties.
        if (hasTerms) {
            sql.append(" ORDER BY (");
            sql.append("CASE WHEN favorite THEN -100 ELSE 0 END");
            sql.append(" + CASE WHEN rating IS NULL THEN 0 ELSE -rating * 5 END");
            for (int i = 0; i < sq.terms().size(); i++) {
                sql.append(" + ").append(termRankCase(i));
            }
            sql.append("), id");
        } else {
            // No query: favorites first (desc), then rating desc (nulls last), then id.
            sql.append(" ORDER BY favorite DESC, COALESCE(rating, 0) DESC, id");
        }

        var spec = jdbc.sql(sql.toString());
        for (int i = 0; i < sq.terms().size(); i++) {
            String t = sq.terms().get(i);
            spec = spec.param("t" + i, "%" + t + "%");
            spec = spec.param("e" + i, t);
            spec = spec.param("p" + i, t + "%");
        }
        if (type != null && !type.isBlank()) spec = spec.param("type", type);
        if (lifecycle != null && !lifecycle.isBlank()) spec = spec.param("lifecycle", lifecycle);
        if (favorite != null) spec = spec.param("favorite", favorite);
        return spec.query(ASSET).list();
    }

    /** Backwards-compatible overload (no favorite filter). */
    public List<Asset> findAll(String query, String type, String lifecycle) {
        return findAll(query, type, lifecycle, null);
    }

    private static String termPredicate(int i) {
        String t = ":t" + i;
        return "(id ILIKE " + t + " OR name ILIKE " + t
            + " OR coalesce(description,'') ILIKE " + t
            + " OR coalesce(owner,'') ILIKE " + t
            + " OR coalesce(language,'') ILIKE " + t
            + " OR EXISTS (SELECT 1 FROM unnest(tags) tg WHERE tg ILIKE " + t + "))";
    }

    private static String termRankCase(int i) {
        String e = ":e" + i;
        String p = ":p" + i;
        String t = ":t" + i;
        return "CASE"
            + " WHEN id ILIKE " + e + " THEN 0"
            + " WHEN id ILIKE " + p + " THEN 1"
            + " WHEN name ILIKE " + e + " THEN 2"
            + " WHEN name ILIKE " + p + " THEN 3"
            + " WHEN id ILIKE " + t + " THEN 4"
            + " WHEN name ILIKE " + t + " THEN 5"
            + " WHEN EXISTS (SELECT 1 FROM unnest(tags) tg WHERE tg ILIKE " + e + ") THEN 6"
            + " WHEN coalesce(description,'') ILIKE " + t + " THEN 7"
            + " ELSE 8 END";
    }

    public Optional<Asset> findById(String id) {
        return jdbc.sql("SELECT * FROM asset WHERE id = :id")
            .param("id", id)
            .query(ASSET)
            .optional();
    }

    public boolean existsById(String id) {
        return jdbc.sql("SELECT 1 FROM asset WHERE id = :id")
            .param("id", id)
            .query(Integer.class)
            .optional()
            .isPresent();
    }

    public void insert(Asset a) {
        jdbc.sql("""
            INSERT INTO asset (id, name, description, owner, type, language, repo_url,
                               repo_default_branch, tags, lifecycle, k8s_namespace,
                               favorite, rating, dashboard_pinned)
            VALUES (:id, :name, :description, :owner, :type, :language, :repo_url,
                    :branch, :tags, :lifecycle, :ns, :favorite, :rating, :pinned)
            """)
            .param("id", a.id())
            .param("name", a.name())
            .param("description", a.description())
            .param("owner", a.owner())
            .param("type", a.type())
            .param("language", a.language())
            .param("repo_url", a.repoUrl())
            .param("branch", a.repoDefaultBranch() == null ? "main" : a.repoDefaultBranch())
            .param("tags", a.tags() == null ? new String[0] : a.tags().toArray(String[]::new))
            .param("lifecycle", a.lifecycle() == null ? "experimental" : a.lifecycle())
            .param("ns", a.k8sNamespace() != null ? a.k8sNamespace() : a.id())
            .param("favorite", a.favorite())
            .param("rating", a.rating())
            .param("pinned", a.dashboardPinned())
            .update();
    }

    public int update(Asset a) {
        return jdbc.sql("""
            UPDATE asset SET
              name = :name,
              description = :description,
              owner = :owner,
              type = :type,
              language = :language,
              repo_url = :repo_url,
              repo_default_branch = :branch,
              tags = :tags,
              lifecycle = :lifecycle,
              k8s_namespace = :ns,
              favorite = :favorite,
              rating = :rating,
              dashboard_pinned = :pinned
            WHERE id = :id
            """)
            .param("id", a.id())
            .param("name", a.name())
            .param("description", a.description())
            .param("owner", a.owner())
            .param("type", a.type())
            .param("language", a.language())
            .param("repo_url", a.repoUrl())
            .param("branch", a.repoDefaultBranch() == null ? "main" : a.repoDefaultBranch())
            .param("tags", a.tags() == null ? new String[0] : a.tags().toArray(String[]::new))
            .param("lifecycle", a.lifecycle())
            .param("ns", a.k8sNamespace())
            .param("favorite", a.favorite())
            .param("rating", a.rating())
            .param("pinned", a.dashboardPinned())
            .update();
    }

    public int delete(String id) {
        return jdbc.sql("DELETE FROM asset WHERE id = :id").param("id", id).update();
    }

    public List<Dependency> findDependenciesOf(String consumerId) {
        return jdbc.sql("SELECT * FROM dependency WHERE consumer_id = :id ORDER BY producer_id")
            .param("id", consumerId)
            .query(DEPENDENCY)
            .list();
    }

    public List<Dependency> findConsumersOf(String producerId) {
        return jdbc.sql("SELECT * FROM dependency WHERE producer_id = :id ORDER BY consumer_id")
            .param("id", producerId)
            .query(DEPENDENCY)
            .list();
    }

    public Dependency insertDependency(String consumerId, String producerId, String versionRef, String kind) {
        Long newId = jdbc.sql("""
            INSERT INTO dependency (consumer_id, producer_id, version_ref, kind)
            VALUES (:consumer, :producer, :version, :kind)
            ON CONFLICT (consumer_id, producer_id, kind)
              DO UPDATE SET version_ref = EXCLUDED.version_ref
            RETURNING id
            """)
            .param("consumer", consumerId)
            .param("producer", producerId)
            .param("version", versionRef == null ? "main" : versionRef)
            .param("kind", kind == null ? "build" : kind)
            .query(Long.class)
            .single();
        return new Dependency(newId, consumerId, producerId,
            versionRef == null ? "main" : versionRef,
            kind == null ? "build" : kind);
    }

    public int deleteDependency(String consumerId, String producerId, String kind) {
        return jdbc.sql("""
            DELETE FROM dependency
            WHERE consumer_id = :consumer AND producer_id = :producer AND kind = :kind
            """)
            .param("consumer", consumerId)
            .param("producer", producerId)
            .param("kind", kind == null ? "build" : kind)
            .update();
    }
}
