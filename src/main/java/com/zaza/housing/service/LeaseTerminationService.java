package com.zaza.housing.service;

import com.zaza.housing.dto.ConfirmLeaseTerminationRequest;
import com.zaza.housing.dto.CreateLeaseTerminationRequest;
import com.zaza.housing.dto.LeaseReasonRequest;
import com.zaza.housing.dto.LeaseResponse;
import com.zaza.housing.dto.LeaseTerminationPreviewRequest;
import com.zaza.housing.dto.LeaseTerminationPreviewResponse;
import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.LeaseTerminationStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.LeaseTermination;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaseTerminationService {
    private final LeaseRepository leases;
    private final LeaseAccessService access;
    private final LeaseService workflow;
    private final LeaseSignatureService signing;
    private final LeaseActivityService activity;
    private final LeaseReadService reads;
    private final Clock clock;
    public LeaseTerminationService(LeaseRepository leases,LeaseAccessService access,LeaseService workflow,LeaseSignatureService signing,LeaseActivityService activity,LeaseReadService reads,Clock clock) {
        this.leases=leases; this.access=access; this.workflow=workflow; this.signing=signing; this.activity=activity; this.reads=reads; this.clock=clock;
    }
    @Transactional
    public LeaseTerminationPreviewResponse preview(AuthenticatedUser actor,UUID organization,UUID property,UUID id,LeaseTerminationPreviewRequest request) {
        RentalLease lease=authorized(actor,organization,property,id,request.version());
        String document=proposal(lease,party(organization),request.proposedEndDate(),request.message());
        return new LeaseTerminationPreviewResponse(document,AgreementDocuments.hash(document));
    }
    @Transactional
    public LeaseResponse request(AuthenticatedUser actor,UUID organization,UUID property,UUID id,CreateLeaseTerminationRequest request) {
        RentalLease previous=authorized(actor,organization,property,id,request.version());
        if(leases.openTermination(id).isPresent()) throw LeaseAccessService.conflict("Resolve the existing termination request first.");
        LeaseParty party=party(organization);
        var revision=leases.revision(id,previous.currentRevisionId());
        String document=proposal(previous,party,request.proposedEndDate(),request.message());
        String hash=AgreementDocuments.hash(document);
        String name=signing.confirm(actor,party,revision.parties(),hash,request.evidence());
        UUID ending=leases.termination(previous,actor.id(),party,request.proposedEndDate(),request.message().trim(),document,hash);
        leases.terminationConsent(ending,party,actor.id(),name,actor.email(),hash);
        leases.touch(previous);
        return recorded(actor,organization,previous,LeaseEventType.TERMINATION_REQUESTED,"An early termination proposal requires the other party's consent.");
    }
    @Transactional
    public LeaseResponse accept(AuthenticatedUser actor,UUID organization,UUID property,UUID id,UUID endingId,ConfirmLeaseTerminationRequest request) {
        RentalLease previous=authorized(actor,organization,property,id,request.version());
        var ending=pending(id,endingId);
        LeaseParty party=party(organization);
        if(ending.details().requestedBy()==party||ending.requesterId().equals(actor.id())) throw new ApiException(HttpStatus.FORBIDDEN,"Only the other agreement party can accept this request.");
        if(!ending.revisionId().equals(previous.currentRevisionId())) throw LeaseAccessService.conflict("The termination request refers to another agreement revision.");
        if(ending.details().proposedEndDate().isBefore(LocalDate.now(clock))) throw LeaseAccessService.conflict("The proposed date has passed. Create a new request.");
        if(!AgreementDocuments.hash(ending.document()).equals(ending.details().documentHash())) throw LeaseAccessService.conflict("Termination digest failed verification.");
        var revision=leases.revision(id,previous.currentRevisionId());
        String name=signing.confirm(actor,party,revision.parties(),ending.details().documentHash(),request.evidence());
        leases.terminationConsent(endingId,party,actor.id(),name,actor.email(),ending.details().documentHash());
        leases.terminationStatus(endingId,LeaseTerminationStatus.ACCEPTED);
        leases.touch(previous);
        return recorded(actor,organization,previous,LeaseEventType.TERMINATION_ACCEPTED,"Both parties agreed the proposed end date. The lease still requires closure and checkout.");
    }
    @Transactional
    public LeaseResponse resolve(AuthenticatedUser actor,UUID organization,UUID property,UUID id,UUID endingId,LeaseReasonRequest request,boolean cancel) {
        RentalLease previous=authorized(actor,organization,property,id,request.version());
        var ending=pending(id,endingId);
        LeaseParty party=party(organization);
        if(cancel?!ending.requesterId().equals(actor.id()):ending.details().requestedBy()==party) throw new ApiException(HttpStatus.FORBIDDEN,cancel?"Only the requester can cancel this proposal.":"Only the other party can reject this proposal.");
        leases.terminationStatus(endingId,cancel?LeaseTerminationStatus.CANCELLED:LeaseTerminationStatus.REJECTED);
        leases.touch(previous);
        return recorded(actor,organization,previous,cancel?LeaseEventType.TERMINATION_CANCELLED:LeaseEventType.TERMINATION_REJECTED,request.message().trim());
    }
    private RentalLease authorized(AuthenticatedUser actor,UUID organization,UUID property,UUID id,long version) {
        RentalLease lease=organization==null?access.tenant(actor,id,true):access.manager(actor,organization,property,id,true);
        if(organization!=null) workflow.lessor(actor,organization,lease);
        LeaseAccessService.version(lease,version); LeaseAccessService.state(lease,LeaseStatus.SIGNED,LeaseStatus.ACTIVE);
        return lease;
    }
    private String proposal(RentalLease lease,LeaseParty party,LocalDate date,String message) {
        var revision=leases.revision(lease.id(),lease.currentRevisionId());
        if(date.isBefore(LocalDate.now(clock))||!date.isBefore(revision.terms().endDate())) throw new ApiException(HttpStatus.BAD_REQUEST,"Propose an end date today or later, before the original end date.");
        return AgreementDocuments.termination(lease.id(),revision.id(),revision.documentHash(),party,date,message);
    }
    private LeaseTermination pending(UUID lease,UUID endingId) {
        var ending=leases.termination(lease,endingId);
        if(ending.details().status()!=LeaseTerminationStatus.PENDING) throw LeaseAccessService.conflict("This termination request is already resolved.");
        return ending;
    }
    private LeaseResponse recorded(AuthenticatedUser actor,UUID organization,RentalLease previous,LeaseEventType type,String message) {
        var current=leases.find(previous.id()).orElseThrow();
        activity.record(actor,organization,previous,current,party(organization),type,message,true,true);
        return reads.response(current,organization==null);
    }
    private static LeaseParty party(UUID organization) { return organization==null?LeaseParty.TENANT:LeaseParty.LESSOR; }
}
