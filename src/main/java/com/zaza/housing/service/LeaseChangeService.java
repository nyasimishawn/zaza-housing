package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.AgreementDocuments;
import com.zaza.housing.util.RentSchedule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaseChangeService {
    private final LeaseChangeRepository changes;
    private final LeaseRepository leases;
    private final LeaseAccessService access;
    private final LeaseReadService reads;
    private final LeaseService workflow;
    private final LeaseSignatureService signing;
    private final LeaseActivityService activity;
    private final LeaseScheduleService schedule;
    private final BillingRepository billing;
    private final AuditService audit;
    private final Clock clock;
    private final PropertyService properties;
    private final InspectionGuardService inspectionGuard;
    public LeaseChangeService(LeaseChangeRepository changes,LeaseRepository leases,LeaseAccessService access,LeaseReadService reads,
        LeaseService workflow,LeaseSignatureService signing,LeaseActivityService activity,LeaseScheduleService schedule,BillingRepository billing,AuditService audit,Clock clock,PropertyService properties,InspectionGuardService inspectionGuard) {
        this.changes=changes;this.leases=leases;this.access=access;this.reads=reads;this.workflow=workflow;this.signing=signing;this.activity=activity;this.schedule=schedule;this.billing=billing;this.audit=audit;this.clock=clock;this.properties=properties;this.inspectionGuard=inspectionGuard;
    }
    public List<LeaseChangeResponse> list(AuthenticatedUser actor,UUID org,UUID property,UUID lease) {
        reads.authorize(actor,org,property,lease);return changes.list(lease,false).stream().map(LeaseChange::details).toList();
    }
    public LeaseChange document(AuthenticatedUser actor,UUID org,UUID property,UUID lease,UUID change) {
        reads.authorize(actor,org,property,lease);return changes.find(lease,change);
    }
    @Transactional
    public LeaseTerminationPreviewResponse preview(AuthenticatedUser actor,UUID org,UUID property,UUID id,LeaseChangePreviewRequest request) {
        var lease=authorize(actor,org,property,id,request.version());
        String document=proposal(lease,party(org),normalize(lease,request.terms()));
        return new LeaseTerminationPreviewResponse(document,AgreementDocuments.hash(document));
    }
    @Transactional
    public LeaseChangeResponse create(AuthenticatedUser actor,UUID org,UUID property,UUID id,CreateLeaseChangeRequest request) {
        var lease=authorize(actor,org,property,id,request.version());
        if(changes.pending(id)) throw LeaseAccessService.conflict("Resolve the existing lease change first.");
        var terms=normalize(lease,request.terms());
        String document=proposal(lease,party(org),terms),hash=AgreementDocuments.hash(document);
        String name=signing.confirm(actor,party(org),leases.revision(id,lease.currentRevisionId()).parties(),hash,request.evidence());
        UUID change=changes.create(lease,actor.id(),party(org),terms,document,hash);
        changes.consent(change,party(org),actor.id(),name,actor.email(),hash);
        record(actor,org,lease,LeaseEventType.CHANGE_REQUESTED,"A "+terms.kind().toLowerCase(java.util.Locale.ROOT)+" requires the other party's review and consent.");
        return changes.find(id,change).details();
    }
    @Transactional
    public LeaseChangeResponse accept(AuthenticatedUser actor,UUID org,UUID property,UUID id,UUID changeId,ConfirmLeaseTerminationRequest request) {
        var lease=authorize(actor,org,property,id,request.version());
        var change=pending(id,changeId);
        if(change.details().requestedBy()==party(org)||change.requesterId().equals(actor.id())) throw forbidden("Only the other agreement party can accept.");
        normalize(lease,change.details().terms());
        if(!change.revisionId().equals(lease.currentRevisionId())||!AgreementDocuments.hash(change.document()).equals(change.details().documentHash()))
            throw LeaseAccessService.conflict("The agreement or change digest differs. Reload and review.");
        String name=signing.confirm(actor,party(org),leases.revision(id,lease.currentRevisionId()).parties(),change.details().documentHash(),request.evidence());
        changes.consent(changeId,party(org),actor.id(),name,actor.email(),change.details().documentHash());
        changes.resolve(changeId,"ACCEPTED");
        reconcileBilling(actor,org,lease,change);
        record(actor,org,lease,LeaseEventType.CHANGE_ACCEPTED,"Both parties accepted the "+change.details().terms().kind().toLowerCase(java.util.Locale.ROOT)+". Original execution evidence is retained.");
        return changes.find(id,changeId).details();
    }
    @Transactional
    public LeaseChangeResponse resolve(AuthenticatedUser actor,UUID org,UUID property,UUID id,UUID changeId,LeaseReasonRequest request,boolean cancel) {
        var lease=authorize(actor,org,property,id,request.version());var change=pending(id,changeId);
        if(cancel?change.details().requestedBy()!=party(org):change.details().requestedBy()==party(org)) throw forbidden(cancel?"Only the requesting agreement party can cancel.":"Only the other party can reject.");
        changes.resolve(changeId,cancel?"CANCELLED":"REJECTED");
        record(actor,org,lease,cancel?LeaseEventType.CHANGE_CANCELLED:LeaseEventType.CHANGE_REJECTED,request.message().trim());
        return changes.find(id,changeId).details();
    }
    private RentalLease authorize(AuthenticatedUser actor,UUID org,UUID property,UUID id,long version) {
        var lease=org==null?access.tenant(actor,id,true):access.manager(actor,org,property,id,true);
        if(org!=null) workflow.lessor(actor,org,lease);
        LeaseAccessService.version(lease,version);LeaseAccessService.state(lease,LeaseStatus.SIGNED,LeaseStatus.ACTIVE);
        return lease;
    }
    private LeaseChangeTerms normalize(RentalLease lease,LeaseChangeTerms t) {
        if(properties.lockForApplication(lease.propertyId()).status()!=PropertyStatus.ACTIVE) throw LeaseAccessService.conflict("An active property is required to renew or amend a lease.");
        if(inspectionGuard.accepted(lease.id(),InspectionKind.MOVE_OUT)) throw LeaseAccessService.conflict("A completed move-out handover cannot be renewed or amended. Complete checkout before arranging a new tenancy.");
        var revision=leases.revision(lease.id(),lease.currentRevisionId());
        var end=workflow.effectiveEnd(lease,revision);
        if(leases.openTermination(lease.id()).isPresent()) throw LeaseAccessService.conflict("Resolve the termination workflow before changing the agreement.");
        if(!end.isAfter(LocalDate.now(clock))) throw LeaseAccessService.conflict("Renew or amend before the lease term expires.");
        if(!t.effectiveDate().isAfter(LocalDate.now(clock))||t.effectiveDate().isBefore(revision.terms().startDate())) throw invalid("Changes must take effect after today and within or after the original term.");
        var last=changes.list(lease.id(),true);
        if(last.stream().anyMatch(c->!t.effectiveDate().isAfter(c.details().terms().effectiveDate()))) throw invalid("A new change must follow every already accepted change's effective date.");
        if(t.kind().equals("RENEWAL")) {
            if(!t.effectiveDate().equals(end)||!t.endDate().isAfter(end)) throw invalid("A renewal starts at the current agreed end and extends it.");
        } else if(!t.endDate().equals(end)||!t.effectiveDate().isBefore(end)) throw invalid("An amendment keeps the agreed end date. Use renewal to extend or termination to shorten it.");
        if(ChronoUnit.MONTHS.between(revision.terms().startDate().withDayOfMonth(1),t.endDate().withDayOfMonth(1))>600) throw invalid("The total agreement term cannot exceed 600 calendar months.");
        return new LeaseChangeTerms(t.kind(),t.effectiveDate(),t.endDate(),t.monthlyRent().setScale(2),t.agreementText().replace("\r\n","\n").replace('\r','\n').strip());
    }
    private String proposal(RentalLease lease,LeaseParty party,LeaseChangeTerms t) {
        var revision=leases.revision(lease.id(),lease.currentRevisionId());
        String prior=changes.list(lease.id(),true).stream().map(c->c.details().id()+" "+c.details().documentHash()).collect(java.util.stream.Collectors.joining("\n"));
        return "Lease "+t.kind()+" proposal\nLease: "+lease.id()+"\nOriginal revision: "+revision.id()+"\nOriginal SHA-256: "+revision.documentHash()
            +"\nPrior accepted addenda:\n"+prior+"\nRequested by: "+party+"\nEffective date: "+t.effectiveDate()+"\nEnd (excluded): "+t.endDate()
            +"\nMonthly rent: "+revision.terms().currency()+" "+t.monthlyRent().toPlainString()+"\nOriginal deposit, currency and due day remain unchanged.\n"
            +"Existing payment allocations against replaced future rent return to unallocated funds; no transfer or refund is executed.\n\nAgreed changes\n"+t.agreementText()+"\n";
    }
    private void reconcileBilling(AuthenticatedUser actor,UUID org,RentalLease lease,LeaseChange change) {
        var account=billing.find(lease.id());if(account.isEmpty()) return;
        if(!account.get().status().equals("OPEN")) throw LeaseAccessService.conflict("Finalized billing cannot be changed.");
        var terms=leases.revision(lease.id(),lease.currentRevisionId()).terms();
        var start=change.details().terms().effectiveDate();
        var rates=schedule.rates(lease.id(),terms.monthlyRent());
        for(var invoice:billing.scheduledInvoices(lease.id(),LocalDate.now(clock))) {
            if(invoice.kind()!=InvoiceKind.RENT||!invoice.periodEnd().isAfter(start)||changes.superseded(invoice.id())) continue;
            if(invoice.credited().signum()>0) throw LeaseAccessService.conflict("A future rent invoice has custom credits. Resolve billing adjustments before accepting this change.");
            BigDecimal retain=invoice.periodStart().isBefore(start)?RentSchedule.amount(rates.floorEntry(invoice.periodStart()).getValue(),invoice.periodStart(),start):BigDecimal.ZERO;
            BigDecimal credit=invoice.amount().subtract(retain);
            if(credit.signum()<0) throw LeaseAccessService.conflict("The existing invoice does not match the agreed rent schedule.");
            BigDecimal excess=invoice.paid().subtract(retain);
            for(var allocation:billing.invoiceAllocations(lease.id(),invoice.id())) {
                if(excess.signum()<=0) break;
                billing.reverse(allocation.id(),"Accepted lease change: future rent schedule replaced.");
                BigDecimal removed=allocation.amount().min(excess),remaining=allocation.amount().subtract(removed);
                if(remaining.signum()>0) billing.allocate(lease.id(),allocation.paymentId(),invoice.id(),remaining);
                excess=excess.subtract(removed);
            }
            if(credit.signum()>0) billing.credit(lease.id(),invoice.id(),credit,"Accepted lease change "+change.details().id()+"; replaced future rent.");
            changes.supersede(invoice.id(),change.details().id());
        }
        for(var period:schedule.calendar(lease.id(),terms,start,change.details().terms().endDate())) if(period.amount().signum()>0)
            billing.invoice(lease.id(),InvoiceKind.RENT,"Rent under addendum "+change.details().id(),period.dueDate(),period.periodStart(),period.periodEnd(),period.amount());
        billing.touch(lease.id());billing.event(lease.id(),actor.id(),"LEASE_CHANGE_ACCEPTED",change.details().id(),"Future rent replaced; prior invoices and payment evidence retained.");
        audit.recordChange(actor.id(),org,"BILLING_LEASE_CHANGE",change.details().id(),null,"Accepted addendum rebuilt future rent; released allocations remain unallocated.");
    }
    private LeaseChange pending(UUID lease,UUID change) { var result=changes.find(lease,change);if(!result.details().status().equals("PENDING")) throw LeaseAccessService.conflict("This proposal is already resolved.");return result; }
    private void record(AuthenticatedUser actor,UUID org,RentalLease previous,LeaseEventType type,String message) {
        leases.touch(previous);activity.record(actor,org,previous,leases.find(previous.id()).orElseThrow(),party(org),type,message,true,true);
    }
    private static LeaseParty party(UUID org) { return org==null?LeaseParty.TENANT:LeaseParty.LESSOR; }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN,message); }
}
