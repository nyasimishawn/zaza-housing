package com.zaza.housing.service;

import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.LeaseRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LeaseAccessService {
    private final LeaseRepository leases;
    private final PropertyService properties;
    public LeaseAccessService(LeaseRepository leases,PropertyService properties) { this.leases=leases; this.properties=properties; }
    public RentalLease tenant(AuthenticatedUser actor,UUID id,boolean lock) {
        RentalLease lease=leases.own(actor.id(),id).orElseThrow(LeaseAccessService::notFound);
        if(lock) { properties.lockForApplication(lease.propertyId()); return leases.own(actor.id(),id).orElseThrow(LeaseAccessService::notFound); }
        return lease;
    }
    public RentalLease manager(AuthenticatedUser actor,UUID organization,UUID property,UUID id,boolean lock) {
        if(lock) properties.lockForManagement(actor,organization,property);
        else properties.getForManagement(actor,organization,property);
        return leases.scoped(property,id).orElseThrow(LeaseAccessService::notFound);
    }
    public static void version(RentalLease lease,long expected) { if(lease.version()!=expected) throw conflict("The lease has changed. Reload before continuing."); }
    public static void state(RentalLease lease,LeaseStatus... allowed) {
        for(var status:allowed) if(lease.status()==status) return;
        throw conflict("This action is unavailable while the lease is "+lease.status()+".");
    }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,message); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"Lease not found."); }
}
