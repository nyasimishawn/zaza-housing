package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.model.Property;
import com.zaza.housing.service.LeaseAccessService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PropertyOwnershipRepository {
    private final JdbcTemplate jdbc;
    public PropertyOwnershipRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<String> eligibleName(UUID organization) { return jdbc.query("SELECT name FROM organizations WHERE id=? AND type IN ('LANDLORD','AGENCY')",(r,n)->r.getString(1),organization).stream().findFirst(); }
    public List<PropertyInterestResponse> interests(Property p) {
        var result=new java.util.ArrayList<>(jdbc.query("SELECT c.*,o.name FROM property_coowners c JOIN organizations o ON o.id=c.organization_id WHERE property_id=? ORDER BY organization_id",(r,n)->new PropertyInterestResponse(r.getObject("organization_id",UUID.class),r.getString("name"),r.getBigDecimal("share_percent"),false),p.id()));
        result.addFirst(new PropertyInterestResponse(p.ownerOrganizationId(),eligibleName(p.ownerOrganizationId()).orElseThrow(),new BigDecimal("100.00").subtract(result.stream().map(PropertyInterestResponse::sharePercent).reduce(BigDecimal.ZERO,BigDecimal::add)),true));return List.copyOf(result);
    }
    public void coowner(UUID property,UUID organization,BigDecimal share) {
        if(share.signum()==0) jdbc.update("DELETE FROM property_coowners WHERE property_id=? AND organization_id=?",property,organization);
        else jdbc.update("INSERT INTO property_coowners(property_id,organization_id,share_percent) VALUES (?,?,?) ON CONFLICT(property_id,organization_id) DO UPDATE SET share_percent=EXCLUDED.share_percent",property,organization,share);
    }
    public boolean pending(UUID property) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM property_ownership_proposals WHERE property_id=? AND status='PENDING')",Boolean.class,property)); }
    public UUID create(Property p,UUID actor,OwnershipProposalTerms t,String document,String hash) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO property_ownership_proposals(id,property_id,property_name,from_organization_id,to_organization_id,kind,share_percent,property_version,requester_id,document,document_hash,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",id,p.id(),p.name(),p.ownerOrganizationId(),t.organizationId(),t.kind(),t.sharePercent(),p.version(),actor,document,hash,t.reason().strip());return id;
    }
    public List<OwnershipProposalResponse> list(UUID organization,int limit,int offset) {
        return jdbc.query(proposalSelect()+" WHERE p.from_organization_id=? OR p.to_organization_id=? ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",mapper(),organization,organization,limit,offset);
    }
    private String proposalSelect() {
        return "SELECT p.*,sender.name AS from_organization_name,recipient.name AS to_organization_name FROM property_ownership_proposals p JOIN organizations sender ON sender.id=p.from_organization_id JOIN organizations recipient ON recipient.id=p.to_organization_id";
    }
    public OwnershipProposalResponse find(UUID id) { return jdbc.query(proposalSelect()+" WHERE p.id=?",mapper(),id).stream().findFirst().orElseThrow(()->new com.zaza.housing.exception.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"Ownership proposal not found.")); }
    private RowMapper<OwnershipProposalResponse> mapper() {
        return (r,n)->new OwnershipProposalResponse(r.getObject("id",UUID.class),r.getObject("property_id",UUID.class),r.getString("property_name"),r.getObject("from_organization_id",UUID.class),r.getObject("to_organization_id",UUID.class),r.getString("from_organization_name"),r.getString("to_organization_name"),r.getString("kind"),r.getBigDecimal("share_percent"),r.getLong("property_version"),r.getObject("requester_id",UUID.class),r.getString("status"),r.getString("document"),r.getString("document_hash"),r.getString("reason"),consents(r.getObject("id",UUID.class)),r.getTimestamp("created_at").toInstant());
    }
    private List<LeaseSignatureResponse> consents(UUID id) { return jdbc.query("SELECT * FROM property_ownership_consents WHERE proposal_id=? ORDER BY signed_at,id",(r,n)->new LeaseSignatureResponse(r.getObject("id",UUID.class),LeaseParty.LESSOR,r.getObject("signer_id",UUID.class),r.getString("typed_name"),r.getString("email"),r.getString("document_hash"),r.getString("method"),r.getTimestamp("signed_at").toInstant()),id); }
    public void consent(UUID id,UUID org,UUID actor,String name,String email,String hash) { jdbc.update("INSERT INTO property_ownership_consents(id,proposal_id,organization_id,signer_id,typed_name,email,document_hash) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),id,org,actor,name,email,hash); }
    public void resolve(UUID id,String status) { if(jdbc.update("UPDATE property_ownership_proposals SET status=? WHERE id=? AND status='PENDING'",status,id)!=1) throw LeaseAccessService.conflict("This proposal is already resolved."); }
    public void touch(Property p) { if(jdbc.update("UPDATE properties SET version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",p.id(),p.version())!=1) throw LeaseAccessService.conflict("Property changed. Reload."); }
    public void transfer(Property p,UUID target) {
        if(jdbc.update("UPDATE properties SET owner_organization_id=?,manager_organization_id=NULL,management_mandate_id=NULL,version=version+1,updated_at=clock_timestamp() WHERE id=? AND version=?",target,p.id(),p.version())!=1) throw LeaseAccessService.conflict("Property changed. Reload.");
        jdbc.update("UPDATE rental_listings SET status='WITHDRAWN',version=version+1,updated_at=clock_timestamp() WHERE property_id=? AND status='PUBLISHED'",p.id());
        // Ownership claims stay tied to the old owner and must be reassessed.
        jdbc.update("UPDATE verification_cases SET status='REVOKED',version=version+1,resolution='Owning organization changed' WHERE property_id=? AND kind='PROPERTY_OWNERSHIP' AND status='VERIFIED'",p.id());
    }
    public boolean hasTransferObligations(UUID property) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM rental_leases WHERE property_id=?)
             OR EXISTS(SELECT 1 FROM rental_applications WHERE property_id=? AND status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','INFORMATION_REQUESTED','APPROVED','LEASED'))
             OR EXISTS(SELECT 1 FROM work_requests WHERE property_id=?)
             OR EXISTS(SELECT 1 FROM agency_mandates WHERE property_id=? AND status IN ('SENT','ACTIVE'))
             OR EXISTS(SELECT 1 FROM property_units WHERE property_id=? AND availability IN ('RESERVED','OCCUPIED'))
             OR EXISTS(SELECT 1 FROM verification_cases WHERE property_id=? AND kind='PROPERTY_OWNERSHIP' AND status IN ('DRAFT','SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED'))
            """,Boolean.class,property,property,property,property,property,property));
    }
}
