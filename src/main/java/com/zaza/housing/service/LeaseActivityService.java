package com.zaza.housing.service;

import com.zaza.housing.enums.ApplicationActorType;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeaseActivityService {
    private final LeaseRepository leases;
    private final RentalApplicationRepository applications;
    private final NotificationService notifications;
    private final AuditService audit;
    public LeaseActivityService(LeaseRepository leases,RentalApplicationRepository applications,NotificationService notifications,AuditService audit) {
        this.leases=leases; this.applications=applications; this.notifications=notifications; this.audit=audit;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(AuthenticatedUser actor,UUID organization,RentalLease previous,RentalLease current,LeaseParty party,LeaseEventType type,String message,boolean visible,boolean notify) {
        UUID event=leases.event(current,current.currentRevisionId(),actor.id(),party,type,message,visible);
        audit.recordSnapshot(actor.id(),organization,"LEASE_"+type.name(),current.id(),previous,current);
        if(notify) notifications.leaseChanged(actor.id(),current.tenantId(),current.applicationId(),current.id(),current.propertyId(),event,type.name(),message,party==LeaseParty.LESSOR);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void application(AuthenticatedUser actor,UUID organization,RentalLease lease,LeaseParty party,boolean close) {
        var previous=applications.review(lease.propertyId(),lease.applicationId(),true).orElseThrow();
        if(previous.status()!=(close?ApplicationStatus.LEASED:ApplicationStatus.APPROVED)) throw LeaseAccessService.conflict("The application is no longer in the expected lease state.");
        applications.transition(previous.id(),previous.version(),close?ApplicationStatus.CLOSED:ApplicationStatus.LEASED);
        var current=applications.review(lease.propertyId(),lease.applicationId(),false).orElseThrow();
        var type=close?ApplicationEventType.LEASE_CLOSED:ApplicationEventType.LEASE_SIGNED;
        applications.event(current,previous.status(),actor.id(),party==LeaseParty.TENANT?ApplicationActorType.APPLICANT:ApplicationActorType.REVIEWER,type,close?"Lease closed; checkout clearance is required.":"Both parties signed the lease.");
        audit.recordSnapshot(actor.id(),organization,"APPLICATION_"+type.name(),previous.id(),previous,current);
    }
}
