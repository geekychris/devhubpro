package io.devportal.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.asset.Asset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<Project> PROJECT = (ResultSet rs, int rowNum) -> new Project(
        rs.getLong("id"),
        (Long) rs.getObject("parent_id"),
        rs.getString("name"),
        rs.getString("description"),
        readJson(rs, "metadata"),
        rs.getInt("sort_order"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant()
    );

    private final RowMapper<Asset> ASSET = (ResultSet rs, int rowNum) -> new Asset(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("owner"),
        rs.getString("type"),
        rs.getString("language"),
        rs.getString("repo_url"),
        rs.getString("repo_default_branch"),
        readTagsArray(rs, "tags"),
        rs.getString("lifecycle"),
        rs.getString("k8s_namespace"),
        rs.getBoolean("favorite"),
        (Integer) rs.getObject("rating"),
        rs.getBoolean("dashboard_pinned"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant()
    );

    public List<Project> findAll() {
        return jdbc.sql("SELECT * FROM project ORDER BY parent_id NULLS FIRST, sort_order, name").query(PROJECT).list();
    }

    public Optional<Project> findById(long id) {
        return jdbc.sql("SELECT * FROM project WHERE id = :id").param("id", id).query(PROJECT).optional();
    }

    public Project insert(Long parentId, String name, String description, JsonNode metadata, int sortOrder) {
        Long id = jdbc.sql("""
                INSERT INTO project (parent_id, name, description, metadata, sort_order)
                VALUES (:parentId, :name, :description, CAST(:metadata AS jsonb), :sortOrder)
                RETURNING id
                """)
            .param("parentId", parentId)
            .param("name", name)
            .param("description", description)
            .param("metadata", metadata == null ? null : metadata.toString())
            .param("sortOrder", sortOrder)
            .query(Long.class).single();
        return findById(id).orElseThrow();
    }

    public Project update(long id, Long parentId, String name, String description,
                          JsonNode metadata, Integer sortOrder) {
        jdbc.sql("""
                UPDATE project SET
                  parent_id   = COALESCE(:parentId, parent_id),
                  name        = COALESCE(:name, name),
                  description = COALESCE(:description, description),
                  metadata    = COALESCE(CAST(:metadata AS jsonb), metadata),
                  sort_order  = COALESCE(:sortOrder, sort_order),
                  updated_at  = now()
                WHERE id = :id
                """)
            .param("id", id)
            .param("parentId", parentId)
            .param("name", name)
            .param("description", description)
            .param("metadata", metadata == null ? null : metadata.toString())
            .param("sortOrder", sortOrder)
            .update();
        return findById(id).orElseThrow();
    }

    /** Set parent explicitly (including to null = root). Used by drag-to-reparent. */
    public void setParent(long id, Long parentId) {
        jdbc.sql("UPDATE project SET parent_id = :parentId, updated_at = now() WHERE id = :id")
            .param("id", id)
            .param("parentId", parentId)
            .update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM project WHERE id = :id").param("id", id).update();
    }

    public List<Asset> findAssets(long projectId) {
        return jdbc.sql("""
                SELECT a.* FROM asset a
                  JOIN project_asset pa ON pa.asset_id = a.id
                 WHERE pa.project_id = :projectId
                 ORDER BY pa.added_at, a.id
                """)
            .param("projectId", projectId)
            .query(ASSET).list();
    }

    /** True when the (projectId, assetId) row was added; false when it already existed. */
    public boolean addAsset(long projectId, String assetId) {
        int n = jdbc.sql("""
                INSERT INTO project_asset (project_id, asset_id) VALUES (:projectId, :assetId)
                ON CONFLICT (project_id, asset_id) DO NOTHING
                """)
            .param("projectId", projectId)
            .param("assetId", assetId)
            .update();
        return n > 0;
    }

    public boolean removeAsset(long projectId, String assetId) {
        int n = jdbc.sql("DELETE FROM project_asset WHERE project_id = :projectId AND asset_id = :assetId")
            .param("projectId", projectId)
            .param("assetId", assetId)
            .update();
        return n > 0;
    }

    /** Project ids each asset is currently a member of — used to denormalize on the assets page. */
    public List<long[]> findMemberships(List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT project_id, asset_id FROM project_asset WHERE asset_id IN (:ids)
                """)
            .param("ids", assetIds)
            .query((rs, n) -> new long[]{ rs.getLong("project_id"), 0L /* asset id is string, packed below */})
            .list();
    }

    public List<MemberRow> memberRows(List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT project_id, asset_id FROM project_asset WHERE asset_id IN (:ids)
                """)
            .param("ids", assetIds)
            .query((rs, n) -> new MemberRow(rs.getLong("project_id"), rs.getString("asset_id")))
            .list();
    }

    public record MemberRow(long projectId, String assetId) {}

    private JsonNode readJson(ResultSet rs, String col) throws SQLException {
        String raw = rs.getString(col);
        if (raw == null) return null;
        try {
            return json.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> readTagsArray(ResultSet rs, String col) throws SQLException {
        java.sql.Array a = rs.getArray(col);
        if (a == null) return List.of();
        Object o = a.getArray();
        if (o instanceof String[] arr) return List.of(arr);
        return List.of();
    }
}
