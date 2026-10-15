package com.zaza.housing.service;

import com.zaza.housing.dto.LeaseDashboardResponse;
import com.zaza.housing.dto.LeaseEventResponse;
import com.zaza.housing.dto.LeaseResponse;
import com.zaza.housing.dto.LeaseRevisionResponse;
import com.zaza.housing.dto.LeaseSigningReceipt;
import com.zaza.housing.dto.LeaseTerminationResponse;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.LeaseTerminationStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.LeaseRevision;
import com.zaza.housing.model.LeaseTermination;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.util.Pagination;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LeaseReadService {
    private final LeaseRepository leases;
    private final LeaseAccessService access;
    private final PropertyService properties;
    public LeaseReadService(LeaseRepository leases,LeaseAccessService access,PropertyService properties) { this.leases=leases; this.access=access; this.properties=properties; }
    public LeaseResponse tenant(AuthenticatedUser actor,UUID id) { return response(access.tenant(actor,id,false),true); }
    public LeaseResponse manager(AuthenticatedUser actor,UUID organization,UUID property,UUID id) { return response(access.manager(actor,organization,property,id,false),false); }
    public List<LeaseResponse> list(AuthenticatedUser actor,UUID organization,UUID property,LeaseStatus status,int limit,int offset) {
        boolean tenant=organization==null;
        if(!tenant) properties.getForManagement(actor,organization,property);
        Pagination.validate(limit,offset);
        return leases.list(tenant?actor.id():property,tenant,status,limit,offset).stream().map(lease->response(lease,tenant)).toList();
    }
    public LeaseDashboardResponse summary(AuthenticatedUser actor,UUID organization,UUID property) {
        boolean tenant=organization==null;
        if(!tenant) properties.getForManagement(actor,organization,property);
        UUID scope=tenant?actor.id():property;
        var counts=leases.counts(scope,tenant);
        return new LeaseDashboardResponse(counts,counts.values().stream().mapToLong(Long::longValue).sum(),counts.get(LeaseStatus.SENT),leases.awaitingCheckout(scope,tenant));
    }
    public LeaseResponse response(RentalLease lease,boolean tenant) {
        LeaseRevision revision=leases.revision(lease.id(),tenant?lease.sharedRevisionId():lease.currentRevisionId());
        var termination=leases.openTermination(lease.id()).map(LeaseTermination::details).orElse(null);
        return new LeaseResponse(lease.id(),lease.applicationId(),lease.status(),lease.version(),projection(revision),leases.signatures(revision.id()),termination,
                termination!=null&&termination.status()==LeaseTerminationStatus.ACCEPTED?termination.proposedEndDate():revision.terms().endDate(),
                lease.activatedAt(),lease.closedAt(),lease.unitReleasedAt(),lease.createdAt(),lease.updatedAt());
    }
    public RentalLease authorize(AuthenticatedUser actor,UUID organization,UUID property,UUID id) {
        return organization==null?access.tenant(actor,id,false):access.manager(actor,organization,property,id,false);
    }
    public List<LeaseRevisionResponse> revisions(AuthenticatedUser actor,UUID organization,UUID property,UUID id,int limit,int offset) {
        authorize(actor,organization,property,id); Pagination.validate(limit,offset);
        return leases.revisions(id,organization==null,limit,offset).stream().map(LeaseReadService::projection).toList();
    }
    public LeaseRevision revision(AuthenticatedUser actor,UUID organization,UUID property,UUID id,UUID revisionId) {
        authorize(actor,organization,property,id);
        LeaseRevision revision=leases.revision(id,revisionId);
        if(organization==null&&!revision.shared()) throw new ApiException(HttpStatus.NOT_FOUND,"Agreement revision not found.");
        return revision;
    }
    public List<LeaseEventResponse> history(AuthenticatedUser actor,UUID organization,UUID property,UUID id,int limit,int offset) {
        authorize(actor,organization,property,id); Pagination.validate(limit,offset);
        return leases.history(id,organization==null,limit,offset);
    }
    public LeaseSigningReceipt receipt(AuthenticatedUser actor,UUID organization,UUID property,UUID id,UUID revisionId) {
        LeaseRevision revision=revision(actor,organization,property,id,revisionId);
        var signatures=leases.signatures(revisionId);
        if(signatures.size()!=2) throw LeaseAccessService.conflict("Both parties must sign this revision before an execution receipt is available.");
        return new LeaseSigningReceipt(id,projection(revision),signatures,"SHA-256");
    }
    public List<LeaseTerminationResponse> terminations(AuthenticatedUser actor,UUID organization,UUID property,UUID id,int limit,int offset) {
        authorize(actor,organization,property,id); Pagination.validate(limit,offset);
        return leases.terminations(id,limit,offset);
    }
    public LeaseTermination termination(AuthenticatedUser actor,UUID organization,UUID property,UUID id,UUID terminationId) {
        authorize(actor,organization,property,id); return leases.termination(id,terminationId);
    }
    public static LeaseRevisionResponse projection(LeaseRevision revision) { return new LeaseRevisionResponse(revision.id(),revision.number(),revision.terms(),revision.parties(),revision.documentHash(),revision.createdAt()); }
}
