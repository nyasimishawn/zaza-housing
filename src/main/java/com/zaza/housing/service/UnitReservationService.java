package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.PropertyUnit;
import com.zaza.housing.model.RentalApplication;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.repository.PropertyRepository;
import com.zaza.housing.repository.RentalApplicationRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitReservationService {
    private final RentalApplicationRepository applications;
    private final PropertyRepository properties;
    private final AuditService audit;
    private final LeaseRepository leases;
    public UnitReservationService(RentalApplicationRepository applications, PropertyRepository properties, AuditService audit, LeaseRepository leases) {
        this.applications = applications; this.properties = properties; this.audit = audit; this.leases=leases;
    }
    // The application service authorizes the actor and holds the property's lock before entering here.
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID actor, UUID organization, RentalApplication application) {
        PropertyUnit previous = unit(application);
        applications.reserve(application, application.unitVersion());
        audit.recordSnapshot(actor, organization, "UNIT_RESERVED_FOR_APPLICATION", previous.id(), previous, unit(application));
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID actor, UUID organization, RentalApplication application) {
        if(leases.holdsApplication(application.id())) throw new ApiException(HttpStatus.CONFLICT,"Resolve the lease before releasing this application's reservation.");
        PropertyUnit previous = unit(application);
        applications.release(application);
        audit.recordSnapshot(actor, organization, "UNIT_APPLICATION_RESERVATION_RELEASED", previous.id(), previous, unit(application));
    }
    private PropertyUnit unit(RentalApplication application) {
        return properties.unit(application.propertyId(), application.unitId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unit not found."));
    }
}
