package io.devportal.port;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PortRepository {

    private final JdbcClient jdbc;

    public PortRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<PortReservation> ROW = (ResultSet rs, int rowNum) -> new PortReservation(
        rs.getLong("id"),
        rs.getString("asset_id"),
        rs.getString("slot_name"),
        rs.getString("scope"),
        rs.getInt("port"),
        rs.getString("protocol")
    );

    public List<PortReservation> findAll() {
        return jdbc.sql("SELECT * FROM port_reservation ORDER BY scope, port").query(ROW).list();
    }

    public List<PortReservation> findByAsset(String assetId) {
        return jdbc.sql("SELECT * FROM port_reservation WHERE asset_id=:id ORDER BY scope, slot_name")
            .param("id", assetId).query(ROW).list();
    }

    public List<PortReservation> findByAssetAndScope(String assetId, String scope) {
        return jdbc.sql("SELECT * FROM port_reservation WHERE asset_id=:id AND scope=:scope ORDER BY slot_name")
            .param("id", assetId).param("scope", scope).query(ROW).list();
    }

    public Set<Integer> portsTakenInScope(String scope, String protocol) {
        return jdbc.sql("SELECT port FROM port_reservation WHERE scope=:scope AND protocol=:proto")
            .param("scope", scope).param("proto", protocol)
            .query(Integer.class).list().stream()
            .collect(Collectors.toSet());
    }

    public PortReservation insert(String assetId, String slotName, String scope, int port, String protocol) {
        long id = jdbc.sql("""
            INSERT INTO port_reservation (asset_id, slot_name, scope, port, protocol)
            VALUES (:asset, :slot, :scope, :port, :proto)
            RETURNING id
            """)
            .param("asset", assetId)
            .param("slot", slotName)
            .param("scope", scope)
            .param("port", port)
            .param("proto", protocol)
            .query(Long.class)
            .single();
        return new PortReservation(id, assetId, slotName, scope, port, protocol);
    }

    public int deleteByAssetAndScope(String assetId, String scope) {
        return jdbc.sql("DELETE FROM port_reservation WHERE asset_id=:id AND scope=:scope")
            .param("id", assetId).param("scope", scope).update();
    }
}
