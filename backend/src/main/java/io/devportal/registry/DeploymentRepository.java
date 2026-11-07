package io.devportal.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.devportal.registry.RegistryDtos.TargetSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeploymentRepository {

    private static final TypeReference<List<DeploymentUrl>> URLS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<>() {};

    private static final String SELECT = """
        SELECT d.*, a.name AS asset_name
          FROM deployment d JOIN asset a ON a.id = d.asset_id
        """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RowMapper<Deployment> mapper;

    public DeploymentRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = this::map;
    }

    /** Filters for {@link #find}; null fields don't filter. */
    public record Filter(String target, String assetId, String status, String source, String phase,
                         String q, boolean includeRetired) {}

    public List<Deployment> find(Filter f) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (!f.includeRetired() && !"retired".equals(f.status())) sql.append(" AND d.retired_at IS NULL");
        if (f.target() != null) { sql.append(" AND d.target = :target"); p.put("target", f.target()); }
        if (f.assetId() != null) { sql.append(" AND d.asset_id = :assetId"); p.put("assetId", f.assetId()); }
        if (f.status() != null) { sql.append(" AND d.status = :status"); p.put("status", f.status()); }
        if (f.source() != null) { sql.append(" AND d.source = :source"); p.put("source", f.source()); }
        if (f.phase() != null) { sql.append(" AND d.phase = :phase"); p.put("phase", f.phase()); }
        if (f.q() != null) {
            sql.append("""
                 AND (d.asset_id ILIKE :q OR a.name ILIKE :q OR d.target ILIKE :q OR d.namespace ILIKE :q
                      OR d.name ILIKE :q OR d.version ILIKE :q OR d.source ILIKE :q)""");
            p.put("q", "%" + f.q() + "%");
        }
        sql.append(" ORDER BY d.target, d.asset_id, d.namespace, d.name");
        return jdbc.sql(sql.toString()).params(p).query(mapper).list();
    }

    public Optional<Deployment> findById(long id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id").param("id", id).query(mapper).optional();
    }

    public Optional<Deployment> findByKey(String assetId, String target, String namespace, String name) {
        return jdbc.sql(SELECT + """
             WHERE d.asset_id = :a AND d.target = :t AND d.namespace = :ns AND d.name = :n
            """)
            .param("a", assetId).param("t", target).param("ns", namespace).param("n", name)
            .query(mapper).optional();
    }

    /** Live (non-retired) deployments of a source — the set a reconcile works over. */
    public List<Deployment> findLiveBySource(String source) {
        return jdbc.sql(SELECT + " WHERE d.source = :s AND d.retired_at IS NULL")
            .param("s", source).query(mapper).list();
    }

    /** Live deployments that have something to probe (filtered further by the poller). */
    public List<Deployment> findPollable() {
        return jdbc.sql(SELECT + " WHERE d.retired_at IS NULL").query(mapper).list();
    }

    /** Row values a report writes. */
    public record Upsert(String assetId, String target, String targetKind, String namespace, String name,
                         String version, String commit, String ref, String phase,
                         List<DeploymentUrl> urls, String healthUrl, String source,
                         Map<String, String> labels, String notes, String status, String reportedStatus) {}

    public long insert(Upsert u) {
        return jdbc.sql("""
            INSERT INTO deployment (asset_id, target, target_kind, namespace, name, version, git_commit,
                                    git_ref, phase, urls, health_url, source, labels, notes, status,
                                    reported_status, first_seen_at, last_reported_at)
            VALUES (:a, :t, :tk, :ns, :n, :v, :c, :r, :ph, CAST(:urls AS jsonb), :h, :src,
                    CAST(:labels AS jsonb), :notes, :st, :rst, now(), now())
            RETURNING id
            """)
            .params(params(u))
            .query(Long.class).single();
    }

    public void update(long id, Upsert u, boolean resetCheck) {
        Map<String, Object> p = params(u);
        p.put("id", id);
        p.put("reset", resetCheck);
        jdbc.sql("""
            UPDATE deployment SET
              target_kind = :tk, version = :v, git_commit = :c, git_ref = :r, phase = :ph,
              urls = CAST(:urls AS jsonb), health_url = :h, source = :src,
              labels = CAST(:labels AS jsonb), notes = :notes, status = :st, reported_status = :rst,
              last_error = CASE WHEN :reset THEN NULL ELSE last_error END,
              last_checked_at = CASE WHEN :reset THEN NULL ELSE last_checked_at END,
              last_reported_at = now(), retired_at = NULL
            WHERE id = :id
            """)
            .params(p)
            .update();
    }

    public int retire(long id) {
        return jdbc.sql("""
            UPDATE deployment SET status = 'retired', retired_at = now()
             WHERE id = :id AND retired_at IS NULL
            """).param("id", id).update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM deployment WHERE id = :id").param("id", id).update();
    }

    /** Health-poller write. A no-op if the deployment was retired meanwhile. */
    public void recordCheck(long id, String status, String error, Integer latencyMs) {
        jdbc.sql("""
            UPDATE deployment SET status = :st, last_error = :err, last_latency_ms = :lat,
                                  last_checked_at = now()
             WHERE id = :id AND retired_at IS NULL
            """)
            .param("id", id).param("st", status).param("err", error).param("lat", latencyMs)
            .update();
    }

    public List<TargetSummary> targets() {
        return jdbc.sql("""
            SELECT target,
                   mode() WITHIN GROUP (ORDER BY target_kind) AS target_kind,
                   count(*)                                   AS total,
                   count(*) FILTER (WHERE status = 'healthy')  AS healthy,
                   count(*) FILTER (WHERE status = 'degraded') AS degraded,
                   count(*) FILTER (WHERE status = 'down')     AS down,
                   count(*) FILTER (WHERE status = 'unknown')  AS unknown,
                   count(*) FILTER (WHERE status = 'reported') AS reported,
                   max(last_reported_at)                      AS last_reported_at
              FROM deployment
             WHERE retired_at IS NULL
             GROUP BY target
             ORDER BY target
            """)
            .query((rs, i) -> new TargetSummary(
                rs.getString("target"), rs.getString("target_kind"),
                rs.getInt("total"), rs.getInt("healthy"), rs.getInt("degraded"), rs.getInt("down"),
                rs.getInt("unknown"), rs.getInt("reported"), instant(rs.getTimestamp("last_reported_at"))))
            .list();
    }

    private Map<String, Object> params(Upsert u) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("a", u.assetId());
        p.put("t", u.target());
        p.put("tk", u.targetKind());
        p.put("ns", u.namespace());
        p.put("n", u.name());
        p.put("v", u.version());
        p.put("c", u.commit());
        p.put("r", u.ref());
        p.put("ph", u.phase());
        p.put("urls", write(u.urls() == null ? List.of() : u.urls()));
        p.put("h", u.healthUrl());
        p.put("src", u.source());
        p.put("labels", write(u.labels() == null ? Map.of() : u.labels()));
        p.put("notes", u.notes());
        p.put("st", u.status());
        p.put("rst", u.reportedStatus());
        return p;
    }

    private Deployment map(ResultSet rs, int row) throws SQLException {
        String assetId = rs.getString("asset_id");
        String target = rs.getString("target");
        String ns = rs.getString("namespace");
        String name = rs.getString("name");
        return new Deployment(
            rs.getLong("id"),
            Deployments.key(assetId, target, ns, name),
            assetId,
            rs.getString("asset_name"),
            target,
            rs.getString("target_kind"),
            ns,
            name,
            rs.getString("version"),
            rs.getString("git_commit"),
            rs.getString("git_ref"),
            rs.getString("phase"),
            read(rs.getString("urls"), URLS, List.of()),
            rs.getString("health_url"),
            rs.getString("source"),
            read(rs.getString("labels"), LABELS, Map.of()),
            rs.getString("notes"),
            rs.getString("status"),
            rs.getString("reported_status"),
            instant(rs.getTimestamp("last_checked_at")),
            rs.getString("last_error"),
            rs.getObject("last_latency_ms") == null ? null : rs.getInt("last_latency_ms"),
            instant(rs.getTimestamp("first_seen_at")),
            instant(rs.getTimestamp("last_reported_at")),
            instant(rs.getTimestamp("retired_at"))
        );
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize " + o.getClass().getSimpleName(), e);
        }
    }

    private <T> T read(String s, TypeReference<T> type, T fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return json.readValue(s, type);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
