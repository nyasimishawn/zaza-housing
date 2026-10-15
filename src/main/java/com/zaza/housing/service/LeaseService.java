package com.zaza.housing.service;

import com.zaza.housing.dto.ActivateLeaseRequest;
import com.zaza.housing.dto.CreateLeaseRequest;
import com.zaza.housing.dto.LeaseParties;
import com.zaza.housing.dto.LeaseReasonRequest;
import com.zaza.housing.dto.LeaseResponse;
import com.zaza.housing.dto.LeaseTerms;
import com.zaza.housing.dto.LeaseVersionRequest;
import com.zaza.housing.dto.ReleaseLeaseUnitRequest;
import com.zaza.housing.dto.ReviseLeaseRequest;
import com.zaza.housing.dto.SignLeaseRequest;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.LeaseTerminationStatus;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.UnitAvailability;
import com.zaza.housing.enums.UnitStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.LeaseRevision;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyUnit;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.AuthRepository;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.repository.PropertyRepository;
import com.zaza.housing.repository.RentalApplicationRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaseService {
    private final LeaseRepository leases;
    private final RentalApplicationRepository applications;
    private final PropertyRepository inventory;
    private final AuthRepository users;
    private final PropertyService properties;
    private final LeaseAccessService access;
    private final LeaseReadService reads;
    private final LeaseSignatureService signing;
    private final LeaseActivityService activity;
    private final AuditService audit;
    private final Clock clock;
    private final BillingGuardService billingGuard;
    public LeaseService(LeaseRepository leases,RentalApplicationRepository applications,PropertyRepository inventory,AuthRepository users,
            PropertyService properties,LeaseAccessService access,LeaseReadService reads,LeaseSignatureService signing,LeaseActivityService activity,AuditService audit,Clock clock,BillingGuardService billingGuard) {
        this.leases=leases; this.applications=applications; this.inventory=inventory; this.users=users; this.properties=properties;
        this.access=access; this.reads=reads; this.signing=signing; this.activity=activity; this.audit=audit; this.clock=clock; this.billingGuard=billingGuard;
    }
    @Transactional
    public LeaseResponse create(AuthenticatedUser actor,UUID organization,UUID propertyId,CreateLeaseRequest request) {
        Property property=properties.lockForManagement(actor,organization,propertyId);
        requireActive(property);
        var application=applications.review(propertyId,request.applicationId(),true).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"Application not found."));
        if(application.version()!=request.applicationVersion()||application.status()!=ApplicationStatus.APPROVED) throw LeaseAccessService.conflict("Use the current approved application version.");
        if(leases.holdsApplication(application.id())) throw LeaseAccessService.conflict("This application already has a lease holding its unit.");
        activeTenant(application.applicantId());
        long unitVersion=leases.reservationVersion(application);
        PropertyUnit unit=inventory.unit(propertyId,application.unitId()).orElseThrow();
        if(unit.version()!=unitVersion||unit.availability()!=UnitAvailability.RESERVED||unit.status()!=UnitStatus.ACTIVE) throw LeaseAccessService.conflict("The approved reservation has changed.");
        LeaseTerms terms=normalize(request.terms());
        String ownerName=leases.ownerName(property.ownerOrganizationId());
        var applicant=application.applicant();
        LeaseParties parties=new LeaseParties(AgreementDocuments.line(ownerName),AgreementDocuments.line(applicant.details().legalName()),applicant.email(),applicant.details().phone(),
                AgreementDocuments.line(property.address()),AgreementDocuments.line(property.city()),property.countryCode(),AgreementDocuments.line(unit.label()));
        UUID id=leases.create(application,property,unitVersion);
        RentalLease initial=find(id);
        UUID revision=addRevision(id,1,terms,parties);
        leases.currentRevision(initial,revision,true);
        RentalLease result=find(id);
        activity.record(actor,organization,null,result,LeaseParty.LESSOR,LeaseEventType.CREATED,"Lease draft created.",false,false);
        return reads.response(result,false);
    }
    @Transactional
    public LeaseResponse revise(AuthenticatedUser actor,UUID organization,UUID property,UUID id,ReviseLeaseRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.DRAFT);
        ready(previous);
        var old=leases.revision(id,previous.currentRevisionId());
        UUID revision=addRevision(id,old.number()+1,normalize(request.terms()),old.parties());
        leases.currentRevision(previous,revision,false);
        return recorded(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.REVISED,"Agreement draft revised.",false,false);
    }
    @Transactional
    public LeaseResponse send(AuthenticatedUser actor,UUID organization,UUID property,UUID id,LeaseVersionRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.DRAFT);
        ready(previous); normalize(leases.revision(id,previous.currentRevisionId()).terms());
        leases.share(previous);
        return recorded(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.SENT,"A lease agreement is ready for review and signing.",true,true);
    }
    @Transactional
    public LeaseResponse recall(AuthenticatedUser actor,UUID organization,UUID property,UUID id,LeaseReasonRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.SENT);
        var old=leases.revision(id,previous.currentRevisionId());
        UUID revision=addRevision(id,old.number()+1,old.terms(),old.parties());
        leases.recall(previous,revision);
        return recorded(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.RECALLED,request.message().trim(),true,true);
    }
    @Transactional
    public LeaseResponse cancel(AuthenticatedUser actor,UUID organization,UUID property,UUID id,LeaseReasonRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        return cancel(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.CANCELLED,request);
    }
    @Transactional
    public LeaseResponse decline(AuthenticatedUser actor,UUID id,LeaseReasonRequest request) {
        RentalLease previous=access.tenant(actor,id,true);
        LeaseAccessService.state(previous,LeaseStatus.SENT);
        return cancel(actor,null,previous,LeaseParty.TENANT,LeaseEventType.DECLINED,request);
    }
    private LeaseResponse cancel(AuthenticatedUser actor,UUID organization,RentalLease previous,LeaseParty party,LeaseEventType type,LeaseReasonRequest request) {
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.DRAFT,LeaseStatus.SENT);
        leases.state(previous,LeaseStatus.CANCELLED);
        return recorded(actor,organization,previous,party,type,request.message().trim(),previous.firstSharedAt()!=null,previous.firstSharedAt()!=null);
    }
    @Transactional
    public LeaseResponse sign(AuthenticatedUser actor,UUID organization,UUID property,UUID id,SignLeaseRequest request) {
        boolean tenant=organization==null;
        RentalLease previous=tenant?access.tenant(actor,id,true):access.manager(actor,organization,property,id,true);
        LeaseParty party=tenant?LeaseParty.TENANT:LeaseParty.LESSOR;
        if(!tenant) lessor(actor,organization,previous);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.SENT);
        ready(previous);
        if(!previous.currentRevisionId().equals(request.revisionId())) throw LeaseAccessService.conflict("Review the current agreement revision before signing.");
        var revision=leases.revision(id,previous.currentRevisionId());
        normalize(revision.terms());
        verifyDocument(revision);
        var signatures=leases.signatures(revision.id());
        if(signatures.stream().anyMatch(signature->signature.party()==party||signature.signerId().equals(actor.id()))) throw LeaseAccessService.conflict("This account or party already signed the revision.");
        String name=signing.confirm(actor,party,revision.parties(),revision.documentHash(),request.evidence());
        var proof=leases.sign(id,revision.id(),party,actor.id(),name,actor.email(),revision.documentHash());
        audit.recordSnapshot(actor.id(),organization,"LEASE_SIGNATURE_RECORDED",id,null,proof);
        boolean complete=signatures.size()==1;
        if(complete) leases.state(previous,LeaseStatus.SIGNED); else leases.touch(previous);
        RentalLease current=find(id);
        activity.record(actor,organization,previous,current,party,tenant?LeaseEventType.TENANT_SIGNED:LeaseEventType.LESSOR_SIGNED,complete?"Both parties have signed the agreement.":"The "+party.name().toLowerCase(Locale.ROOT)+" signed the agreement.",true,true);
        if(complete) {
            activity.application(actor,organization,current,party,false);
            activity.record(actor,organization,null,current,party,LeaseEventType.FULLY_SIGNED,"Both parties signed this exact agreement revision.",true,false);
        }
        return reads.response(current,tenant);
    }
    @Transactional
    public LeaseResponse activate(AuthenticatedUser actor,UUID organization,UUID property,UUID id,ActivateLeaseRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.SIGNED);
        if(!Boolean.TRUE.equals(request.possessionConfirmed())) throw invalid("Confirm the move-in handover before activation.");
        ready(previous);
        var revision=leases.revision(id,previous.currentRevisionId());
        LocalDate end=effectiveEnd(previous,revision);
        if(today().isBefore(revision.terms().startDate())||!today().isBefore(end)) throw LeaseAccessService.conflict("Activation must occur during the agreed lease term.");
        var unit=inventory.unit(property,previous.unitId()).orElseThrow();
        billingGuard.activation(id);
        leases.activate(previous);
        auditUnit(actor,organization,previous,unit,"LEASE_UNIT_OCCUPIED");
        return recorded(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.ACTIVATED,"Move-in confirmed and lease activated.",true,true);
    }
    @Transactional
    public LeaseResponse complete(AuthenticatedUser actor,UUID organization,UUID property,UUID id,LeaseReasonRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.ACTIVE,LeaseStatus.SIGNED);
        var revision=leases.revision(id,previous.currentRevisionId());
        var ending=leases.openTermination(id);
        boolean terminated=ending.isPresent()&&ending.get().details().status()==LeaseTerminationStatus.ACCEPTED;
        if(today().isBefore(effectiveEnd(previous,revision))) throw LeaseAccessService.conflict("The agreed end date has not been reached.");
        if(ending.isPresent()&&ending.get().details().status()==LeaseTerminationStatus.PENDING) leases.terminationStatus(ending.get().details().id(),LeaseTerminationStatus.CANCELLED);
        var unit=inventory.unit(property,previous.unitId()).orElseThrow();
        leases.complete(previous,terminated);
        auditUnit(actor,organization,previous,unit,"LEASE_UNIT_CHECKOUT_PENDING");
        var current=find(id);
        activity.application(actor,organization,current,LeaseParty.LESSOR,true);
        return recorded(actor,organization,previous,LeaseParty.LESSOR,terminated?LeaseEventType.TERMINATED:LeaseEventType.COMPLETED,request.message().trim(),true,true);
    }
    @Transactional
    public LeaseResponse releaseUnit(AuthenticatedUser actor,UUID organization,UUID property,UUID id,ReleaseLeaseUnitRequest request) {
        RentalLease previous=access.manager(actor,organization,property,id,true);
        LeaseAccessService.version(previous,request.version()); LeaseAccessService.state(previous,LeaseStatus.COMPLETED,LeaseStatus.TERMINATED);
        if(!Boolean.TRUE.equals(request.vacancyConfirmed())) throw invalid("Confirm that the unit is vacant before releasing it.");
        if(previous.unitReleasedAt()!=null) throw LeaseAccessService.conflict("This unit has already been released.");
        var unit=inventory.unit(property,previous.unitId()).orElseThrow();
        billingGuard.checkout(id);
        leases.releaseUnit(previous);
        auditUnit(actor,organization,previous,unit,"LEASE_UNIT_RELEASED");
        return recorded(actor,organization,previous,LeaseParty.LESSOR,LeaseEventType.UNIT_RELEASED,request.message().trim(),true,true);
    }
    public void lessor(AuthenticatedUser actor,UUID organization,RentalLease lease) {
        if(!lease.ownerOrganizationId().equals(organization)||lease.tenantId().equals(actor.id())) throw new ApiException(HttpStatus.FORBIDDEN,"An owner-organization administrator distinct from the tenant must sign for the lessor.");
    }
    private void ready(RentalLease lease) {
        requireActive(properties.lockForApplication(lease.propertyId())); activeTenant(lease.tenantId());
        var unit=inventory.unit(lease.propertyId(),lease.unitId()).orElseThrow();
        if(unit.status()!=UnitStatus.ACTIVE||unit.availability()!=UnitAvailability.RESERVED||unit.version()!=lease.unitVersion()) throw LeaseAccessService.conflict("The lease reservation or unit has changed.");
        var application=applications.review(lease.propertyId(),lease.applicationId(),true).orElseThrow();
        if(leases.reservationVersion(application)!=lease.unitVersion()) throw LeaseAccessService.conflict("Reservation ownership has changed.");
    }
    public LocalDate effectiveEnd(RentalLease lease,LeaseRevision revision) {
        return leases.openTermination(lease.id()).filter(ending->ending.details().status()==LeaseTerminationStatus.ACCEPTED)
                .map(ending->ending.details().proposedEndDate()).orElse(revision.terms().endDate());
    }
    private void activeTenant(UUID id) { if(users.findById(id).isEmpty()) throw LeaseAccessService.conflict("The tenant account is no longer active."); }
    private void requireActive(Property property) { if(property.status()!=PropertyStatus.ACTIVE) throw LeaseAccessService.conflict("An active property is required for drafting, signing, or activation."); }
    private UUID addRevision(UUID lease,int number,LeaseTerms terms,LeaseParties parties) {
        String document=AgreementDocuments.lease(lease,number,terms,parties);
        return leases.addRevision(lease,number,terms,parties,document,AgreementDocuments.hash(document));
    }
    private LeaseTerms normalize(LeaseTerms terms) {
        if(terms.startDate().isBefore(today())||!terms.endDate().isAfter(terms.startDate())) throw invalid("Use a start date today or later and an end date after the start date.");
        String currency=terms.currency().toUpperCase(Locale.ROOT);
        try { Currency.getInstance(currency); } catch(IllegalArgumentException error) { throw invalid("Use a valid ISO currency code."); }
        return new LeaseTerms(AgreementDocuments.line(terms.title()),terms.startDate(),terms.endDate(),terms.monthlyRent().setScale(2),terms.deposit().setScale(2),currency,terms.rentDueDay(),terms.agreementText().replace("\r\n","\n").replace('\r','\n').strip());
    }
    private LocalDate today() { return LocalDate.now(clock); }
    private void verifyDocument(LeaseRevision revision) { if(!AgreementDocuments.hash(revision.document()).equals(revision.documentHash())) throw LeaseAccessService.conflict("Stored agreement digest failed verification."); }
    private RentalLease find(UUID id) { return leases.find(id).orElseThrow(); }
    private LeaseResponse recorded(AuthenticatedUser actor,UUID organization,RentalLease previous,LeaseParty party,LeaseEventType type,String message,boolean visible,boolean notify) {
        RentalLease current=find(previous.id()); activity.record(actor,organization,previous,current,party,type,message,visible,notify); return reads.response(current,organization==null);
    }
    private void auditUnit(AuthenticatedUser actor,UUID organization,RentalLease lease,PropertyUnit previous,String action) { audit.recordSnapshot(actor.id(),organization,action,previous.id(),previous,inventory.unit(lease.propertyId(),lease.unitId()).orElseThrow()); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
}
