package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.BillingAccount;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.BillingRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BillingAccessService {
  private final LeaseAccessService leases;
  private final BillingRepository billing;

  public BillingAccessService(LeaseAccessService leases, BillingRepository billing) {
    this.leases = leases;
    this.billing = billing;
  }

  public RentalLease lease(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id, boolean write) {
    RentalLease lease =
        organization == null
            ? leases.tenant(actor, id, write)
            : leases.manager(actor, organization, property, id, write);
    if (write
        && organization != null
        && (!organization.equals(lease.ownerOrganizationId())
            || actor.id().equals(lease.tenantId())))
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "Only an owner-organization administrator distinct from the tenant may manage finances.");
    return lease;
  }

  public BillingAccount account(UUID lease) {
    return billing
        .find(lease)
        .orElseThrow(
            () ->
                new ApiException(
                    HttpStatus.NOT_FOUND, "Billing is not configured for this lease."));
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException missing(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }
}
