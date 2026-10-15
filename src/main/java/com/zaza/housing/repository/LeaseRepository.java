package com.zaza.housing.repository;

import com.zaza.housing.dto.LeaseEventResponse;
import com.zaza.housing.dto.LeaseParties;
import com.zaza.housing.dto.LeaseSignatureResponse;
import com.zaza.housing.dto.LeaseTerminationResponse;
import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.LeaseTerminationStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.LeaseRevision;
import com.zaza.housing.model.LeaseTermination;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.RentalApplication;
import com.zaza.housing.model.RentalLease;
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
public class LeaseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final RowMapper<RentalLease> LEASE = (rs, row) -> new RentalLease(
            rs.getObject("id", UUID.class), rs.getObject("application_id", UUID.class), rs.getObject("property_id", UUID.class),
            rs.getObject("unit_id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("owner_organization_id", UUID.class),
            LeaseStatus.valueOf(rs.getString("status")), rs.getLong("version"), rs.getObject("current_revision_id", UUID.class),
            rs.getObject("shared_revision_id", UUID.class), rs.getLong("unit_version"), instant(rs,"first_shared_at"),
            instant(rs,"activated_at"), instant(rs,"closed_at"), instant(rs,"unit_released_at"), instant(rs,"created_at"), instant(rs,"updated_at"));
    private static final RowMapper<LeaseSignatureResponse> SIGNATURE = (rs,row) -> new LeaseSignatureResponse(rs.getObject("id",UUID.class),
            LeaseParty.valueOf(rs.getString("party")), rs.getObject("signer_id",UUID.class), rs.getString("typed_name"), rs.getString("email"),
            rs.getString("document_hash"), rs.getString("method"), instant(rs,"signed_at"));
    public LeaseRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    private static java.time.Instant instant(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        return rs.getTimestamp(field)==null ? null : rs.getTimestamp(field).toInstant();
    }
    public UUID create(RentalApplication application, Property property, long unitVersion) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO rental_leases(id,application_id,property_id,unit_id,tenant_id,owner_organization_id,unit_version) VALUES (?,?,?,?,?,?,?)",
                id,application.id(),property.id(),application.unitId(),application.applicantId(),property.ownerOrganizationId(),unitVersion);
        return id;
    }
    public String ownerName(UUID id) { return jdbc.queryForObject("SELECT name FROM organizations WHERE id=?",String.class,id); }
    public void recall(RentalLease lease,UUID revision) {
        check(jdbc.update("UPDATE rental_leases SET status='DRAFT',current_revision_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",revision,lease.id(),lease.version()));
    }
    public Optional<RentalLease> find(UUID id) { return jdbc.query("SELECT * FROM rental_leases WHERE id=?",LEASE,id).stream().findFirst(); }
    public Optional<RentalLease> own(UUID user,UUID id) { return jdbc.query("SELECT * FROM rental_leases WHERE id=? AND tenant_id=? AND first_shared_at IS NOT NULL",LEASE,id,user).stream().findFirst(); }
    public Optional<RentalLease> scoped(UUID property,UUID id) { return jdbc.query("SELECT * FROM rental_leases WHERE property_id=? AND id=?",LEASE,property,id).stream().findFirst(); }
    public List<RentalLease> list(UUID scope,boolean tenant,LeaseStatus status,int limit,int offset) {
        String where=tenant ? "tenant_id=? AND first_shared_at IS NOT NULL" : "property_id=?";
        String sql="SELECT * FROM rental_leases WHERE "+where+(status==null?"":" AND status=?")+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?";
        return status==null?jdbc.query(sql,LEASE,scope,limit,offset):jdbc.query(sql,LEASE,scope,status.name(),limit,offset);
    }
    public Map<LeaseStatus,Long> counts(UUID scope,boolean tenant) {
        var counts=new EnumMap<LeaseStatus,Long>(LeaseStatus.class);
        for(var status:LeaseStatus.values()) counts.put(status,0L);
        jdbc.query("SELECT status,count(*) AS total FROM rental_leases WHERE "+(tenant?"tenant_id=? AND first_shared_at IS NOT NULL":"property_id=?")+" GROUP BY status",
                rs->{counts.put(LeaseStatus.valueOf(rs.getString("status")),rs.getLong("total"));},scope);
        return Map.copyOf(counts);
    }
    public long awaitingCheckout(UUID scope,boolean tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM rental_leases WHERE "+(tenant?"tenant_id=? AND first_shared_at IS NOT NULL":"property_id=?")
                +" AND status IN ('COMPLETED','TERMINATED') AND unit_released_at IS NULL",Long.class,scope);
    }
    public boolean holdsApplication(UUID id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rental_leases WHERE application_id=? AND unit_released_at IS NULL)",Boolean.class,id)); }
    public boolean holdsUnit(UUID property,UUID unit) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM rental_leases WHERE property_id=? AND unit_id=? AND unit_released_at IS NULL)",Boolean.class,property,unit)); }
    public long reservationVersion(RentalApplication application) {
        return jdbc.query("SELECT reserved_unit_version FROM application_unit_reservations WHERE application_id=? AND property_id=? AND unit_id=?",
                (rs,row)->rs.getLong(1),application.id(),application.propertyId(),application.unitId()).stream().findFirst()
                .orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"The approved application's reservation is unavailable."));
    }
    public UUID addRevision(UUID lease,int number,LeaseTerms terms,LeaseParties parties,String document,String hash) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lease_revisions(id,lease_id,number,terms,parties,document,document_hash) VALUES (?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)",
                id,lease,number,json.writeValueAsString(terms),json.writeValueAsString(parties),document,hash);
        return id;
    }
    public LeaseRevision revision(UUID lease,UUID id) {
        return jdbc.query("SELECT r.*, EXISTS(SELECT 1 FROM lease_revision_shares s WHERE s.revision_id=r.id) AS shared FROM lease_revisions r WHERE r.lease_id=? AND r.id=?",
                (rs,row)->new LeaseRevision(rs.getObject("id",UUID.class),lease,rs.getInt("number"),json.readValue(rs.getString("terms"),LeaseTerms.class),
                        json.readValue(rs.getString("parties"),LeaseParties.class),rs.getString("document"),rs.getString("document_hash"),rs.getBoolean("shared"),instant(rs,"created_at")),lease,id)
                .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Agreement revision not found."));
    }
    public List<LeaseRevision> revisions(UUID lease,boolean tenant,int limit,int offset) {
        var ids=jdbc.queryForList("SELECT r.id FROM lease_revisions r WHERE r.lease_id=?"+(tenant?" AND EXISTS(SELECT 1 FROM lease_revision_shares s WHERE s.revision_id=r.id)":"")+" ORDER BY r.number DESC LIMIT ? OFFSET ?",UUID.class,lease,limit,offset);
        return ids.stream().map(id->revision(lease,id)).toList();
    }
    public void currentRevision(RentalLease lease,UUID revision,boolean initial) {
        check(jdbc.update("UPDATE rental_leases SET current_revision_id=?, version=version+?,updated_at=clock_timestamp() WHERE id=? AND version=?",
                revision,initial?0:1,lease.id(),lease.version()));
    }
    public void share(RentalLease lease) {
        jdbc.update("INSERT INTO lease_revision_shares(revision_id) VALUES (?)",lease.currentRevisionId());
        check(jdbc.update("UPDATE rental_leases SET status='SENT',shared_revision_id=current_revision_id,first_shared_at=COALESCE(first_shared_at,clock_timestamp()),version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",lease.id(),lease.version()));
    }
    public void state(RentalLease lease,LeaseStatus status) {
        check(jdbc.update("UPDATE rental_leases SET status=?,version=version+1,updated_at=clock_timestamp(),unit_released_at=CASE WHEN ?='CANCELLED' THEN clock_timestamp() ELSE unit_released_at END WHERE id=? AND version=?",status.name(),status.name(),lease.id(),lease.version()));
    }
    public void touch(RentalLease lease) { check(jdbc.update("UPDATE rental_leases SET version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",lease.id(),lease.version())); }
    public List<LeaseSignatureResponse> signatures(UUID revision) { return jdbc.query("SELECT * FROM lease_signatures WHERE revision_id=? ORDER BY signed_at,id",SIGNATURE,revision); }
    public LeaseSignatureResponse sign(UUID lease,UUID revision,LeaseParty party,UUID actor,String name,String email,String hash) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lease_signatures(id,lease_id,revision_id,party,signer_id,typed_name,email,document_hash,method) VALUES (?,?,?,?,?,?,?,?,'ACCOUNT_PASSWORD_CONSENT')",id,lease,revision,party.name(),actor,name,email,hash);
        return jdbc.query("SELECT * FROM lease_signatures WHERE id=?",SIGNATURE,id).getFirst();
    }
    public UUID event(RentalLease lease,UUID revision,UUID actor,LeaseParty party,LeaseEventType type,String message,boolean visible) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lease_events(id,lease_id,revision_id,type,status,actor_id,actor_party,message,tenant_visible) VALUES (?,?,?,?,?,?,?,?,?)",id,lease.id(),revision,type.name(),lease.status().name(),actor,party.name(),message,visible);
        return id;
    }
    public List<LeaseEventResponse> history(UUID lease,boolean tenant,int limit,int offset) {
        return jdbc.query("SELECT * FROM lease_events WHERE lease_id=?"+(tenant?" AND tenant_visible":"")+" ORDER BY created_at,id LIMIT ? OFFSET ?",
                (rs,row)->new LeaseEventResponse(rs.getObject("id",UUID.class),LeaseEventType.valueOf(rs.getString("type")),LeaseStatus.valueOf(rs.getString("status")),rs.getObject("revision_id",UUID.class),LeaseParty.valueOf(rs.getString("actor_party")),rs.getString("message"),instant(rs,"created_at")),lease,limit,offset);
    }
    public void activate(RentalLease lease) {
        changeUnit(lease,"RESERVED","OCCUPIED");
        check(jdbc.update("DELETE FROM application_unit_reservations WHERE application_id=? AND reserved_unit_version=?",lease.applicationId(),lease.unitVersion()));
        check(jdbc.update("UPDATE rental_leases SET status='ACTIVE',unit_version=unit_version+1,activated_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",lease.id(),lease.version()));
    }
    public void complete(RentalLease lease,boolean terminated) {
        changeUnit(lease,lease.status()==LeaseStatus.ACTIVE?"OCCUPIED":"RESERVED","UNAVAILABLE");
        if(lease.status()==LeaseStatus.SIGNED) check(jdbc.update("DELETE FROM application_unit_reservations WHERE application_id=? AND reserved_unit_version=?",lease.applicationId(),lease.unitVersion()));
        check(jdbc.update("UPDATE rental_leases SET status=?,unit_version=unit_version+1,closed_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",terminated?"TERMINATED":"COMPLETED",lease.id(),lease.version()));
    }
    public void releaseUnit(RentalLease lease) {
        changeUnit(lease,"UNAVAILABLE","AVAILABLE");
        check(jdbc.update("UPDATE rental_leases SET unit_version=unit_version+1,unit_released_at=clock_timestamp(),version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=? AND unit_released_at IS NULL",lease.id(),lease.version()));
    }
    private void changeUnit(RentalLease lease,String previous,String next) {
        check(jdbc.update("UPDATE property_units SET availability=?,version=version+1,updated_at=clock_timestamp() WHERE id=? AND property_id=? AND version=? AND availability=?",next,lease.unitId(),lease.propertyId(),lease.unitVersion(),previous));
    }
    public UUID termination(RentalLease lease,UUID actor,LeaseParty party,java.time.LocalDate date,String message,String document,String hash) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lease_terminations(id,lease_id,revision_id,requester_id,requester_party,proposed_end_date,message,document,document_hash) VALUES (?,?,?,?,?,?,?,?,?)",id,lease.id(),lease.currentRevisionId(),actor,party.name(),date,message,document,hash);
        return id;
    }
    public LeaseTermination termination(UUID lease,UUID id) {
        return jdbc.query("SELECT * FROM lease_terminations WHERE lease_id=? AND id=?",(rs,row)->new LeaseTermination(lease,rs.getObject("revision_id",UUID.class),rs.getObject("requester_id",UUID.class),rs.getString("document"),
                new LeaseTerminationResponse(rs.getObject("id",UUID.class),LeaseParty.valueOf(rs.getString("requester_party")),rs.getDate("proposed_end_date").toLocalDate(),rs.getString("message"),rs.getString("document_hash"),LeaseTerminationStatus.valueOf(rs.getString("status")),terminationConsents(rs.getObject("id",UUID.class)),instant(rs,"created_at"))),lease,id)
                .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Termination request not found."));
    }
    public Optional<LeaseTermination> openTermination(UUID lease) {
        return jdbc.queryForList("SELECT id FROM lease_terminations WHERE lease_id=? AND status IN ('PENDING','ACCEPTED')",UUID.class,lease).stream().findFirst().map(id->termination(lease,id));
    }
    public List<LeaseTerminationResponse> terminations(UUID lease,int limit,int offset) {
        return jdbc.queryForList("SELECT id FROM lease_terminations WHERE lease_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",UUID.class,lease,limit,offset).stream().map(id->termination(lease,id).details()).toList();
    }
    private List<LeaseSignatureResponse> terminationConsents(UUID id) { return jdbc.query("SELECT * FROM lease_termination_consents WHERE termination_id=? ORDER BY signed_at,id",SIGNATURE,id); }
    public void terminationConsent(UUID termination,LeaseParty party,UUID actor,String name,String email,String hash) {
        jdbc.update("INSERT INTO lease_termination_consents(id,termination_id,party,signer_id,typed_name,email,document_hash,method) VALUES (?,?,?,?,?,?,?,'ACCOUNT_PASSWORD_CONSENT')",UUID.randomUUID(),termination,party.name(),actor,name,email,hash);
    }
    public void terminationStatus(UUID id,LeaseTerminationStatus status) {
        check(jdbc.update("UPDATE lease_terminations SET status=? WHERE id=? AND status='PENDING'",status.name(),id));
    }
    private static void check(int count) { if(count!=1) throw new ApiException(HttpStatus.CONFLICT,"The lease, reservation, or unit has changed. Reload before continuing."); }
}
