package com.zaza.housing.repository;

import com.zaza.housing.dto.TenantProfileDetails;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.TenantProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TenantProfileRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<TenantProfile> profile;
    public TenantProfileRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        profile = (rs, row) -> new TenantProfile(rs.getObject("user_id", UUID.class),
                json.readValue(rs.getString("details"), TenantProfileDetails.class), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    public Optional<TenantProfile> find(UUID user, boolean lock) {
        return jdbc.query("SELECT * FROM tenant_profiles WHERE user_id = ?" + (lock ? " FOR UPDATE" : ""), profile, user).stream().findFirst();
    }
    public void create(UUID user, TenantProfileDetails details) {
        jdbc.update("INSERT INTO tenant_profiles(user_id, details) VALUES (?, CAST(? AS jsonb))", user, json.writeValueAsString(details));
    }
    public void update(UUID user, long version, TenantProfileDetails details) {
        if (jdbc.update("UPDATE tenant_profiles SET details = CAST(? AS jsonb), version = version + 1, updated_at = clock_timestamp() WHERE user_id = ? AND version = ?",
                json.writeValueAsString(details), user, version) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "The profile has changed. Reload before saving.");
        }
    }
}
