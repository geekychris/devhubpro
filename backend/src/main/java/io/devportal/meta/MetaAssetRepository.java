package io.devportal.meta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MetaAssetRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetaAssetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private final RowMapper<MetaAsset> META = (ResultSet rs, int rowNum) -> new MetaAsset(
        rs.getString("id"),
        rs.getString("name"),
        rs.getString("kind"),
        readJsonMap(rs, "config"),
        rs.getBoolean("provisioned_by_portal"),
        toInstant(rs.getTimestamp("created_at")),
        toInstant(rs.getTimestamp("updated_at"))
    );

    private final RowMapper<Consumes> CONSUMES = (ResultSet rs, int rowNum) -> new Consumes(
        rs.getLong("id"),
        rs.getString("asset_id"),
        rs.getString("meta_asset_id"),
        rs.getString("role")
    );

    private static Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }

    private Map<String, Object> readJsonMap(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        if (s == null || s.isBlank()) return Map.of();
        try {
            return mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public List<MetaAsset> findAll() {
        return jdbc.sql("SELECT * FROM meta_asset ORDER BY id").query(META).list();
    }

    public Optional<MetaAsset> findById(String id) {
        return jdbc.sql("SELECT * FROM meta_asset WHERE id=:id").param("id", id).query(META).optional();
    }

    public boolean existsById(String id) {
        return jdbc.sql("SELECT 1 FROM meta_asset WHERE id=:id").param("id", id)
            .query(Integer.class).optional().isPresent();
    }

    public void insert(MetaAsset m) {
        String configJson;
        try { configJson = mapper.writeValueAsString(m.config() == null ? Map.of() : m.config()); }
        catch (Exception e) { configJson = "{}"; }
        jdbc.sql("""
            INSERT INTO meta_asset (id, name, kind, config, provisioned_by_portal)
            VALUES (:id, :name, :kind, :config::jsonb, :p)
            """)
            .param("id", m.id())
            .param("name", m.name())
            .param("kind", m.kind())
            .param("config", configJson)
            .param("p", m.provisionedByPortal())
            .update();
    }

    public int update(MetaAsset m) {
        String configJson;
        try { configJson = mapper.writeValueAsString(m.config() == null ? Map.of() : m.config()); }
        catch (Exception e) { configJson = "{}"; }
        return jdbc.sql("""
            UPDATE meta_asset SET name=:name, kind=:kind, config=:config::jsonb, provisioned_by_portal=:p
            WHERE id=:id
            """)
            .param("id", m.id())
            .param("name", m.name())
            .param("kind", m.kind())
            .param("config", configJson)
            .param("p", m.provisionedByPortal())
            .update();
    }

    public int delete(String id) {
        return jdbc.sql("DELETE FROM meta_asset WHERE id=:id").param("id", id).update();
    }

    public List<Consumes> consumesFor(String assetId) {
        return jdbc.sql("SELECT * FROM consumes WHERE asset_id=:id ORDER BY meta_asset_id")
            .param("id", assetId).query(CONSUMES).list();
    }

    public List<Consumes> consumersOf(String metaAssetId) {
        return jdbc.sql("SELECT * FROM consumes WHERE meta_asset_id=:id ORDER BY asset_id")
            .param("id", metaAssetId).query(CONSUMES).list();
    }

    public Consumes addConsumes(String assetId, String metaAssetId, String role) {
        long id = jdbc.sql("""
            INSERT INTO consumes (asset_id, meta_asset_id, role)
            VALUES (:asset, :meta, :role)
            ON CONFLICT (asset_id, meta_asset_id, role) DO UPDATE SET role=EXCLUDED.role
            RETURNING id
            """)
            .param("asset", assetId).param("meta", metaAssetId).param("role", role)
            .query(Long.class).single();
        return new Consumes(id, assetId, metaAssetId, role);
    }

    public int removeConsumes(String assetId, String metaAssetId, String role) {
        return jdbc.sql("DELETE FROM consumes WHERE asset_id=:asset AND meta_asset_id=:meta AND coalesce(role,'')=coalesce(:role,'')")
            .param("asset", assetId).param("meta", metaAssetId).param("role", role)
            .update();
    }
}
