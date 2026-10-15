package com.zaza.housing.repository;

import com.zaza.housing.dto.ApplicantProfileSnapshot;
import com.zaza.housing.dto.ApplicationDetails;
import com.zaza.housing.dto.ApplicationEventResponse;
import com.zaza.housing.dto.ApplicationNoteResponse;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.enums.ApplicationActorType;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.RentalApplication;
import com.zaza.housing.model.RentalListing;
import com.zaza.housing.model.TenantProfile;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class RentalApplicationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<RentalApplication> application;
    public RentalApplicationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        application = (rs, row) -> new RentalApplication(rs.getObject("id", UUID.class), rs.getObject("applicant_id", UUID.class),
                rs.getObject("property_id", UUID.class), rs.getObject("unit_id", UUID.class), rs.getObject("listing_id", UUID.class),
                ApplicationStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getLong("listing_version"),
                rs.getLong("property_version"), rs.getLong("unit_version"), rs.getLong("profile_version"),
                json.readValue(rs.getString("details"), ApplicationDetails.class),
                json.readValue(rs.getString("applicant_snapshot"), ApplicantProfileSnapshot.class),
                json.readValue(rs.getString("listing_snapshot"), PublicListingResponse.class),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    public UUID create(UUID actor, ApplicantProfileSnapshot snapshot, TenantProfile profile, RentalListing listing, ApplicationDetails details) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rental_applications(id, applicant_id, property_id, unit_id, listing_id, listing_version,
                    property_version, unit_version, profile_version, details, applicant_snapshot, listing_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                """, id, actor, listing.propertyId(), listing.unitId(), listing.id(), listing.version(), listing.sourcePropertyVersion(),
                listing.sourceUnitVersion(), profile.version(), json.writeValueAsString(details), json.writeValueAsString(snapshot),
                json.writeValueAsString(listing.publicSnapshot()));
        return id;
    }
    public Optional<RentalApplication> own(UUID actor, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM rental_applications WHERE applicant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""), application, actor, id).stream().findFirst();
    }
    public Optional<RentalApplication> review(UUID property, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM rental_applications WHERE property_id = ? AND id = ? AND submitted_at IS NOT NULL"
                + (lock ? " FOR UPDATE" : ""), application, property, id).stream().findFirst();
    }
    public List<RentalApplication> listOwn(UUID actor, ApplicationStatus status, int limit, int offset) {
        return list("applicant_id = ?", actor, status, limit, offset);
    }
    public List<RentalApplication> listReview(UUID property, ApplicationStatus status, int limit, int offset) {
        return list("property_id = ? AND submitted_at IS NOT NULL", property, status, limit, offset);
    }
    private List<RentalApplication> list(String scope, UUID id, ApplicationStatus status, int limit, int offset) {
        String sql = "SELECT * FROM rental_applications WHERE " + scope + (status == null ? "" : " AND status = ?") + " ORDER BY updated_at DESC, id LIMIT ? OFFSET ?";
        return status == null ? jdbc.query(sql, application, id, limit, offset) : jdbc.query(sql, application, id, status.name(), limit, offset);
    }
    public Map<ApplicationStatus, Long> counts(UUID id, boolean reviewer) {
        var result = new EnumMap<ApplicationStatus, Long>(ApplicationStatus.class);
        for (var status : ApplicationStatus.values()) result.put(status, 0L);
        jdbc.query("SELECT status, count(*) AS total FROM rental_applications WHERE "
                + (reviewer ? "property_id = ? AND submitted_at IS NOT NULL" : "applicant_id = ?") + " GROUP BY status",
                rs -> { result.put(ApplicationStatus.valueOf(rs.getString("status")), rs.getLong("total")); }, id);
        return Map.copyOf(result);
    }
    public boolean hasActive(UUID actor, UUID unit) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM rental_applications WHERE applicant_id = ? AND unit_id = ?
                AND status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED','LEASED'))
                """, Boolean.class, actor, unit));
    }
    public void updateDetails(UUID id, long version, ApplicationDetails details) {
        check(jdbc.update("UPDATE rental_applications SET details = CAST(? AS jsonb), version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?",
                json.writeValueAsString(details), id, version));
    }
    public void refreshListing(UUID id, long version, RentalListing listing) {
        check(jdbc.update("""
                UPDATE rental_applications SET listing_version = ?, property_version = ?, unit_version = ?,
                    listing_snapshot = CAST(? AS jsonb), version = version + 1, updated_at = clock_timestamp()
                WHERE id = ? AND version = ?
                """, listing.version(), listing.sourcePropertyVersion(), listing.sourceUnitVersion(), json.writeValueAsString(listing.publicSnapshot()), id, version));
    }
    public void submit(UUID id, long version, TenantProfile profile, ApplicantProfileSnapshot snapshot) {
        check(jdbc.update("""
                UPDATE rental_applications SET status = 'SUBMITTED', profile_version = ?, applicant_snapshot = CAST(? AS jsonb),
                    submitted_at = COALESCE(submitted_at, clock_timestamp()), version = version + 1, updated_at = clock_timestamp()
                WHERE id = ? AND version = ?
                """, profile.version(), json.writeValueAsString(snapshot), id, version));
    }
    public void transition(UUID id, long version, ApplicationStatus status) {
        check(jdbc.update("UPDATE rental_applications SET status = ?, version = version + 1, updated_at = clock_timestamp() WHERE id = ? AND version = ?", status.name(), id, version));
    }
    public UUID event(RentalApplication current, ApplicationStatus previous, UUID actor, ApplicationActorType actorType, ApplicationEventType type, String message) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO application_events(id, application_id, type, from_status, to_status, actor_id, actor_type, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, current.id(), type.name(), previous == null ? null : previous.name(), current.status().name(), actor, actorType.name(), message);
        return id;
    }
    public List<ApplicationEventResponse> events(UUID id, int limit, int offset) {
        return jdbc.query("SELECT * FROM application_events WHERE application_id = ? ORDER BY created_at, id LIMIT ? OFFSET ?",
                (rs, row) -> new ApplicationEventResponse(rs.getObject("id", UUID.class), ApplicationEventType.valueOf(rs.getString("type")),
                        rs.getString("from_status") == null ? null : ApplicationStatus.valueOf(rs.getString("from_status")),
                        ApplicationStatus.valueOf(rs.getString("to_status")), ApplicationActorType.valueOf(rs.getString("actor_type")),
                        rs.getString("message"), rs.getTimestamp("created_at").toInstant()), id, limit, offset);
    }
    public ApplicationNoteResponse addNote(UUID id, UUID actor, String text) {
        UUID note = UUID.randomUUID();
        jdbc.update("INSERT INTO application_reviewer_notes(id, application_id, author_id, text) VALUES (?, ?, ?, ?)", note, id, actor, text);
        return jdbc.query("SELECT * FROM application_reviewer_notes WHERE id = ?", this::note, note).getFirst();
    }
    private ApplicationNoteResponse note(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ApplicationNoteResponse(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("text"), rs.getTimestamp("created_at").toInstant());
    }
    public List<ApplicationNoteResponse> notes(UUID id, int limit, int offset) {
        return jdbc.query("SELECT * FROM application_reviewer_notes WHERE application_id = ? ORDER BY created_at, id LIMIT ? OFFSET ?", this::note, id, limit, offset);
    }
    public boolean reserved(UUID property, UUID unit) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM application_unit_reservations WHERE property_id = ? AND unit_id = ?)", Boolean.class, property, unit));
    }
    public void reserve(RentalApplication application, long unitVersion) {
        check(jdbc.update("""
                UPDATE property_units SET availability = 'RESERVED', version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ? AND status = 'ACTIVE' AND availability = 'AVAILABLE'
                """, application.propertyId(), application.unitId(), unitVersion));
        jdbc.update("INSERT INTO application_unit_reservations(application_id, property_id, unit_id, reserved_unit_version) VALUES (?, ?, ?, ?)",
                application.id(), application.propertyId(), application.unitId(), unitVersion + 1);
    }
    public void release(RentalApplication application) {
        var versions = jdbc.queryForList("SELECT reserved_unit_version FROM application_unit_reservations WHERE application_id = ?", Long.class, application.id());
        if (versions.size() != 1) throw new ApiException(HttpStatus.CONFLICT, "The reservation has changed. Review it before cancelling.");
        check(jdbc.update("""
                UPDATE property_units SET availability = 'AVAILABLE', version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ? AND availability = 'RESERVED'
                """, application.propertyId(), application.unitId(), versions.getFirst()));
        jdbc.update("DELETE FROM application_unit_reservations WHERE application_id = ?", application.id());
    }
    private static void check(int count) {
        if (count != 1) throw new ApiException(HttpStatus.CONFLICT, "The application or unit has changed. Reload before continuing.");
    }
}
