package com.zaza.housing.service;

import com.zaza.housing.enums.InspectionStatus;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.LeaseInspection;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.InspectionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InspectionAccessService {
  private final LeaseAccessService leases;
  private final InspectionRepository inspections;

  public InspectionAccessService(LeaseAccessService leases, InspectionRepository inspections) {
    this.leases = leases;
    this.inspections = inspections;
  }

  public RentalLease lease(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, boolean lock) {
    var lease =
        org == null
            ? leases.tenant(actor, id, lock)
            : leases.manager(actor, org, property, id, lock);
    LeaseAccessService.state(
        lease,
        LeaseStatus.SIGNED,
        LeaseStatus.ACTIVE,
        LeaseStatus.COMPLETED,
        LeaseStatus.TERMINATED);
    return lease;
  }

  public LeaseInspection report(RentalLease lease, UUID id, boolean tenant) {
    var i =
        inspections
            .find(lease.id(), id)
            .orElseThrow(() -> WorkAccessService.missing("Inspection not found."));
    if (tenant && i.sharedRevisionId() == null)
      throw WorkAccessService.missing("Inspection not found.");
    return i;
  }

  public void manager(AuthenticatedUser actor, UUID org, RentalLease lease) {
    if (org == null || actor.id().equals(lease.tenantId()))
      throw WorkAccessService.forbidden(
          "A property manager distinct from the tenant must prepare and attest the inspection.");
  }

  public void version(LeaseInspection inspection, long version) {
    if (inspection.version() != version)
      throw LeaseAccessService.conflict("The inspection changed. Reload before continuing.");
  }

  public void editable(LeaseInspection inspection) {
    if (inspection.status() != InspectionStatus.DRAFT
        && inspection.status() != InspectionStatus.DISPUTED)
      throw LeaseAccessService.conflict(
          "Only a draft or disputed inspection can be revised. Accepted reports are final.");
  }
}
