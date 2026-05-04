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

    public List<Asset> findAll(String query, String type, String lifecycle) {
        StringBuilder sql = new StringBuilder("SELECT * FROM asset WHERE 1=1");
        if (query != null && !query.isBlank()) {
            sql.append(" AND (id ILIKE :q OR name ILIKE :q OR coalesce(description,'') ILIKE :q)");
        }
        if (type != null && !type.isBlank()) sql.append(" AND type = :type");
        if (lifecycle != null && !lifecycle.isBlank()) sql.append(" AND lifecycle = :lifecycle");
        sql.append(" ORDER BY id");

        var spec = jdbc.sql(sql.toString());
        if (query != null && !query.isBlank()) spec = spec.param("q", "%" + query + "%");
        if (type != null && !type.isBlank()) spec = spec.param("type", type);
        if (lifecycle != null && !lifecycle.isBlank()) spec = spec.param("lifecycle", lifecycle);
        return spec.query(ASSET).list();
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
                               repo_default_branch, tags, lifecycle)
            VALUES (:id, :name, :description, :owner, :type, :language, :repo_url,
                    :branch, :tags, :lifecycle)
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
              lifecycle = :lifecycle
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
