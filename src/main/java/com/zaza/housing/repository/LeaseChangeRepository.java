package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.model.LeaseChange;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.service.LeaseAccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LeaseChangeRepository {
    private final JdbcTemplate jdbc;
    public LeaseChangeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<LeaseChange> list(UUID lease,boolean accepted) {
        return jdbc.query("SELECT * FROM lease_changes WHERE lease_id=?"+(accepted?" AND status='ACCEPTED'":"")+" ORDER BY effective_date,created_at,id",
            (r,n)->new LeaseChange(lease,r.getObject("revision_id",UUID.class),r.getObject("requester_id",UUID.class),r.getString("document"),
                new LeaseChangeResponse(r.getObject("id",UUID.class),new LeaseChangeTerms(r.getString("kind"),r.getDate("effective_date").toLocalDate(),r.getDate("end_date").toLocalDate(),r.getBigDecimal("monthly_rent"),r.getString("agreement_text")),
                    LeaseParty.valueOf(r.getString("requester_party")),r.getString("status"),r.getString("document_hash"),consents(r.getObject("id",UUID.class)),r.getTimestamp("created_at").toInstant())),lease);
    }
    public LeaseChange find(UUID lease,UUID id) {
        return list(lease,false).stream().filter(c->c.details().id().equals(id)).findFirst()
            .orElseThrow(()->new com.zaza.housing.exception.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"Lease change not found."));
    }
    public boolean pending(UUID lease) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM lease_changes WHERE lease_id=? AND status='PENDING')",Boolean.class,lease)); }
    public UUID create(RentalLease lease, UUID actor, LeaseParty party, LeaseChangeTerms terms,String document,String hash) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lease_changes(id,lease_id,revision_id,kind,effective_date,end_date,monthly_rent,agreement_text,requester_id,requester_party,document,document_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            id,lease.id(),lease.currentRevisionId(),terms.kind(),terms.effectiveDate(),terms.endDate(),terms.monthlyRent(),terms.agreementText(),actor,party.name(),document,hash);
        return id;
    }
    public void resolve(UUID id,String status) {
        if(jdbc.update("UPDATE lease_changes SET status=? WHERE id=? AND status='PENDING'",status,id)!=1) throw LeaseAccessService.conflict("This change is already resolved.");
    }
    public void consent(UUID id,LeaseParty party,UUID actor,String name,String email,String hash) {
        jdbc.update("INSERT INTO lease_change_consents(id,change_id,party,signer_id,typed_name,email,document_hash) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),id,party.name(),actor,name,email,hash);
    }
    private List<LeaseSignatureResponse> consents(UUID id) {
        return jdbc.query("SELECT * FROM lease_change_consents WHERE change_id=? ORDER BY signed_at,id",(r,n)->new LeaseSignatureResponse(r.getObject("id",UUID.class),LeaseParty.valueOf(r.getString("party")),r.getObject("signer_id",UUID.class),r.getString("typed_name"),r.getString("email"),r.getString("document_hash"),r.getString("method"),r.getTimestamp("signed_at").toInstant()),id);
    }
    public void supersede(UUID invoice,UUID change) { jdbc.update("INSERT INTO billing_rent_supersessions(invoice_id,change_id) VALUES (?,?)",invoice,change); }
    public boolean superseded(UUID invoice) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM billing_rent_supersessions WHERE invoice_id=?)",Boolean.class,invoice)); }
}
