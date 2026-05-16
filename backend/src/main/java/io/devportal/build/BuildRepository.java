package io.devportal.build;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BuildRepository {

    private final JdbcClient jdbc;

    public BuildRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Build> BUILD = (ResultSet rs, int rowNum) -> new Build(
        rs.getLong("id"),
        rs.getString("asset_id"),
        rs.getObject("parent_build_id") == null ? null : rs.getLong("parent_build_id"),
        BuildMode.fromDb(rs.getString("mode")),
        rs.getString("command_name"),
        rs.getString("command_line"),
        rs.getString("git_ref"),
        rs.getString("git_sha"),
        rs.getString("workspace_path"),
        rs.getString("log_path"),
        BuildStatus.fromDb(rs.getString("status")),
        rs.getObject("exit_code") == null ? null : rs.getInt("exit_code"),
        toInstant(rs.getTimestamp("started_at")),
        toInstant(rs.getTimestamp("finished_at")),
        toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp t) { return t == null ? null : t.toInstant(); }

    public long insertQueued(String assetId, Long parentBuildId, BuildMode mode,
                             String commandName, String commandLine,
                             String gitRef, String workspacePath, String logPath) {
        return jdbc.sql("""
            INSERT INTO build (asset_id, parent_build_id, mode, command_name, command_line,
                               git_ref, workspace_path, log_path, status)
            VALUES (:assetId, :parentId, :mode, :cmdName, :cmdLine, :ref, :ws, :log, 'queued')
            RETURNING id
            """)
            .param("assetId", assetId)
            .param("parentId", parentBuildId)
            .param("mode", mode.dbValue())
            .param("cmdName", commandName)
            .param("cmdLine", commandLine)
            .param("ref", gitRef)
            .param("ws", workspacePath)
            .param("log", logPath)
            .query(Long.class)
            .single();
    }

    public void markRunning(long buildId, String gitSha) {
        jdbc.sql("UPDATE build SET status='running', started_at=now(), git_sha=:sha WHERE id=:id")
            .param("id", buildId)
            .param("sha", gitSha)
            .update();
    }

    public void markFinished(long buildId, BuildStatus status, Integer exitCode) {
        jdbc.sql("UPDATE build SET status=:status, exit_code=:code, finished_at=now() WHERE id=:id")
            .param("id", buildId)
            .param("status", status.dbValue())
            .param("code", exitCode)
            .update();
    }

    public Optional<Build> findById(long id) {
        return jdbc.sql("SELECT * FROM build WHERE id=:id")
            .param("id", id).query(BUILD).optional();
    }

    public List<Build> findByAsset(String assetId, int limit) {
        return jdbc.sql("SELECT * FROM build WHERE asset_id=:id ORDER BY created_at DESC LIMIT :lim")
            .param("id", assetId).param("lim", limit).query(BUILD).list();
    }

    public List<Build> findRecent(int limit) {
        return jdbc.sql("SELECT * FROM build ORDER BY created_at DESC LIMIT :lim")
            .param("lim", limit).query(BUILD).list();
    }

    public int deleteById(long id) {
        return jdbc.sql("DELETE FROM build WHERE id = :id").param("id", id).update();
    }

    /** Walk parent_build_id up to the root, then collect everything that descends from it. */
    public List<Build> findChain(long buildId) {
        return jdbc.sql("""
            WITH RECURSIVE up(b_id, b_parent) AS (
              SELECT id, parent_build_id FROM build WHERE id = :id
              UNION
              SELECT b.id, b.parent_build_id FROM build b
                JOIN up u ON b.id = u.b_parent
            ),
            root_id AS (
              SELECT b_id AS rid FROM up WHERE b_parent IS NULL LIMIT 1
            ),
            chain(c_id) AS (
              SELECT rid FROM root_id
              UNION
              SELECT b.id FROM build b
                JOIN chain c ON b.parent_build_id = c.c_id
            )
            SELECT b.* FROM build b JOIN chain c ON b.id = c.c_id ORDER BY b.created_at
            """)
            .param("id", buildId).query(BUILD).list();
    }
}
