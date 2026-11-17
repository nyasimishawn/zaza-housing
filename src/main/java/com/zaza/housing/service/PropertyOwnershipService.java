package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.AgreementDocuments;
import com.zaza.housing.util.Pagination;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyOwnershipService {
    private final PropertyOwnershipRepository ownership;
    private final PropertyService properties;
    private final OrganizationRepository memberships;
    private final OrganizationService organizations;
    private final AuthService auth;
    private final AuditService audit;
    public PropertyOwnershipService(PropertyOwnershipRepository ownership,PropertyService properties,OrganizationRepository memberships,OrganizationService organizations,AuthService auth,AuditService audit) {
        this.ownership=ownership;this.properties=properties;this.memberships=memberships;this.organizations=organizations;this.auth=auth;this.audit=audit;
    }
    public List<PropertyInterestResponse> interests(AuthenticatedUser actor,UUID org,UUID id) { return ownership.interests(properties.getForManagement(actor,org,id)); }
    public List<OwnershipProposalResponse> inbox(AuthenticatedUser actor,UUID org,int limit,int offset) { manager(actor,org);Pagination.validate(limit,offset);return ownership.list(org,limit,offset); }
    @Transactional
    public LeaseTerminationPreviewResponse preview(AuthenticatedUser actor,UUID org,UUID id,OwnershipPreviewRequest request) {
        var p=owner(actor,org,id,request.version());validate(p,request.terms());String document=proposal(p,request.terms());
        return new LeaseTerminationPreviewResponse(document,AgreementDocuments.hash(document));
    }
    @Transactional
    public OwnershipProposalResponse create(AuthenticatedUser actor,UUID org,UUID id,CreateOwnershipProposalRequest request) {
        var p=owner(actor,org,id,request.version());
        if(ownership.pending(id)) throw LeaseAccessService.conflict("Resolve the existing ownership proposal first.");
        validate(p,request.terms());String document=proposal(p,request.terms()),hash=AgreementDocuments.hash(document);
        String name=confirm(actor,hash,request.evidence());
        UUID proposal=ownership.create(p,actor.id(),request.terms(),document,hash);
        ownership.consent(proposal,org,actor.id(),name,actor.email(),hash);
        var result=ownership.find(proposal);audit.recordSnapshot(actor.id(),org,"OWNERSHIP_PROPOSED",proposal,null,result);return result;
    }
    @Transactional
    public OwnershipProposalResponse decide(AuthenticatedUser actor,UUID org,UUID proposalId,ConfirmLeaseTerminationRequest request) {
        var initial=ownership.find(proposalId);visible(initial,org);
        java.util.stream.Stream.of(initial.fromOrganizationId(),initial.toOrganizationId()).sorted().forEach(memberships::lock);
        manager(actor,org);
        if(!initial.toOrganizationId().equals(org)||initial.requesterId().equals(actor.id())) throw forbidden("A separate recipient administrator must accept.");
        var property=properties.lockForApplication(initial.propertyId());
        var p=ownership.find(proposalId);pending(p);
        if(request.version()!=property.version()||property.version()!=p.propertyVersion()||!property.ownerOrganizationId().equals(p.fromOrganizationId())) throw LeaseAccessService.conflict("The property changed. Cancel this proposal and review a fresh one.");
        // A revoked initiating administrator cannot leave a transferable authorization behind.
        if(memberships.member(p.fromOrganizationId(),p.requesterId()).filter(m->m.role()!=MembershipRole.MEMBER).isEmpty()) throw LeaseAccessService.conflict("The requesting administrator no longer has authority. Reissue the proposal.");
        var terms=new OwnershipProposalTerms(p.kind(),p.toOrganizationId(),p.sharePercent(),p.reason());validate(property,terms);
        if(!AgreementDocuments.hash(p.document()).equals(p.documentHash())) throw LeaseAccessService.conflict("Ownership evidence digest differs.");
        String name=confirm(actor,p.documentHash(),request.evidence());ownership.consent(proposalId,org,actor.id(),name,actor.email(),p.documentHash());
        if(p.kind().equals("TRANSFER")) ownership.transfer(property,org);else { ownership.coowner(property.id(),org,p.sharePercent());ownership.touch(property); }
        ownership.resolve(proposalId,"ACCEPTED");var result=ownership.find(proposalId);
        audit.recordSnapshot(actor.id(),org,"OWNERSHIP_ACCEPTED",proposalId,p,result);return result;
    }
    @Transactional
    public OwnershipProposalResponse resolve(AuthenticatedUser actor,UUID org,UUID proposalId,LeaseReasonRequest request,boolean cancel) {
        memberships.lock(org);manager(actor,org);var initial=ownership.find(proposalId);visible(initial,org);
        properties.lockForApplication(initial.propertyId());var p=ownership.find(proposalId);pending(p);
        if(!(cancel?p.fromOrganizationId():p.toOrganizationId()).equals(org)) throw forbidden(cancel?"Only the current owner can cancel.":"Only the recipient can reject.");
        ownership.resolve(proposalId,cancel?"CANCELLED":"REJECTED");var result=ownership.find(proposalId);
        audit.recordSnapshot(actor.id(),org,cancel?"OWNERSHIP_CANCELLED":"OWNERSHIP_REJECTED",proposalId,p,java.util.Map.of("proposal",result,"decisionReason",request.message().trim()));return result;
    }
    private Property owner(AuthenticatedUser actor,UUID org,UUID id,long version) {
        var p=properties.lockForManagement(actor,org,id);if(!p.ownerOrganizationId().equals(org)) throw forbidden("Only the controlling owner can propose ownership changes.");
        if(p.version()!=version) throw LeaseAccessService.conflict("Property changed. Reload before proposing.");return p;
    }
    private void validate(Property p,OwnershipProposalTerms t) {
        if(t.organizationId().equals(p.ownerOrganizationId())||ownership.eligibleName(t.organizationId()).isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a separate landlord or agency organization.");
        if(p.status()==com.zaza.housing.enums.PropertyStatus.ARCHIVED) throw LeaseAccessService.conflict("Restore the property before changing ownership.");
        var interests=ownership.interests(p);
        if(t.kind().equals("TRANSFER")) {
            if(t.sharePercent().compareTo(new BigDecimal("100"))!=0) throw new ApiException(HttpStatus.BAD_REQUEST,"A controlling-owner transfer must be 100%.");
            if(interests.size()>1) throw LeaseAccessService.conflict("Each co-owner must consent to releasing its interest before a full transfer.");
            if(ownership.hasTransferObligations(p.id())) throw LeaseAccessService.conflict("Transfer currently supports properties without tenancy/service history, open applications, occupied units or active mandates. Occupied transfers need a separate lease-novation workflow.");
        } else {
            var others=interests.stream().filter(i->!i.controllingOwner()&&!i.organizationId().equals(t.organizationId())).map(PropertyInterestResponse::sharePercent).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(others.add(t.sharePercent()).compareTo(new BigDecimal("100"))>=0) throw new ApiException(HttpStatus.BAD_REQUEST,"The controlling owner must retain a positive share; total shares cannot exceed 100%.");
            if(t.sharePercent().signum()==0&&interests.stream().noneMatch(i->i.organizationId().equals(t.organizationId()))) throw new ApiException(HttpStatus.BAD_REQUEST,"This organization has no interest to release.");
        }
    }
    private String proposal(Property p,OwnershipProposalTerms t) {
        return "Property ownership "+t.kind()+" proposal\nProperty: "+p.id()+"\nName: "+p.name()+"\nReviewed property version: "+p.version()
            +"\nControlling owner: "+p.ownerOrganizationId()+"\nRecipient: "+t.organizationId()+" ("+ownership.eligibleName(t.organizationId()).orElseThrow()+")"
            +"\nRecipient interest after acceptance: "+t.sharePercent().setScale(2).toPlainString()+"%\nReason: "+t.reason().strip()
            +"\nCo-owner interests do not grant tenant-data access, operational roles or automatic income splitting.\nA full transfer clears agency delegation, withdraws public offers and requires new ownership verification.\nThis is an application ownership record, not a land-registry transfer.\n";
    }
    private String confirm(AuthenticatedUser actor,String hash,LeaseConsent evidence) {
        if(!Boolean.TRUE.equals(evidence.consent())||!hash.equals(evidence.documentHash())) throw LeaseAccessService.conflict("Review the exact ownership proposal and explicitly consent.");
        auth.reauthenticate(actor,evidence.password());String name=AgreementDocuments.line(evidence.typedName());
        if(!name.equalsIgnoreCase(AgreementDocuments.line(auth.me(actor).displayName()))) throw new ApiException(HttpStatus.BAD_REQUEST,"Typed name must match your account display name.");return name;
    }
    private void manager(AuthenticatedUser actor,UUID org) { if(organizations.get(actor,org).role()==MembershipRole.MEMBER) throw forbidden("An organization administrator is required."); }
    private void visible(OwnershipProposalResponse p,UUID org) { if(!p.fromOrganizationId().equals(org)&&!p.toOrganizationId().equals(org)) throw new ApiException(HttpStatus.NOT_FOUND,"Ownership proposal not found."); }
    private void pending(OwnershipProposalResponse p) { if(!p.status().equals("PENDING")) throw LeaseAccessService.conflict("The ownership proposal is already resolved."); }
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN,message); }
}
