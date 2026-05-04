package io.devportal.analyze;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetArtifactRepository {

    private final JdbcClient jdbc;

    public AssetArtifactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AssetArtifact> ROW = (ResultSet rs, int rowNum) -> new AssetArtifact(
        rs.getLong("id"),
        rs.getString("asset_id"),
        rs.getString("flavor"),
        rs.getString("group_id"),
        rs.getString("artifact_id"),
        rs.getString("version"),
        rs.getString("relative_path"),
        toInstant(rs.getTimestamp("detected_at"))
    );

    private static Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }

    public List<AssetArtifact> findByAsset(String assetId) {
        return jdbc.sql("SELECT * FROM asset_artifact WHERE asset_id=:id ORDER BY relative_path, artifact_id")
            .param("id", assetId).query(ROW).list();
    }

    public Optional<AssetArtifact> findProducer(String flavor, String groupId, String artifactId) {
        // Match (flavor, group, artifact). Multiple producers possible — return the first by id.
        return jdbc.sql("""
            SELECT * FROM asset_artifact
            WHERE flavor=:flavor AND coalesce(group_id,'')=coalesce(:gid,'') AND artifact_id=:aid
            ORDER BY id LIMIT 1
            """)
            .param("flavor", flavor)
            .param("gid", groupId)
            .param("aid", artifactId)
            .query(ROW).optional();
    }

    public AssetArtifact upsert(String assetId, String flavor, String groupId, String artifactId,
                                 String version, String relativePath) {
        long id = jdbc.sql("""
            INSERT INTO asset_artifact (asset_id, flavor, group_id, artifact_id, version, relative_path)
            VALUES (:asset, :flavor, :gid, :aid, :ver, :rel)
            ON CONFLICT (asset_id, flavor, group_id, artifact_id, relative_path)
              DO UPDATE SET version = EXCLUDED.version, detected_at = now()
            RETURNING id
            """)
            .param("asset", assetId)
            .param("flavor", flavor)
            .param("gid", groupId)
            .param("aid", artifactId)
            .param("ver", version)
            .param("rel", relativePath == null ? "." : relativePath)
            .query(Long.class).single();
        return jdbc.sql("SELECT * FROM asset_artifact WHERE id=:id")
            .param("id", id).query(ROW).single();
    }

    public int deleteForAsset(String assetId, String flavor) {
        return jdbc.sql("DELETE FROM asset_artifact WHERE asset_id=:id AND flavor=:flavor")
            .param("id", assetId).param("flavor", flavor).update();
    }
}
