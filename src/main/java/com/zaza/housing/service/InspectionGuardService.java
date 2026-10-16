package com.zaza.housing.service;

import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.InspectionStatus;
import com.zaza.housing.enums.LeaseTerminationStatus;
import com.zaza.housing.model.InspectionPolicy;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.repository.LeaseRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InspectionGuardService {
  private final InspectionRepository inspections;
  private final LeaseRepository leases;

  public InspectionGuardService(InspectionRepository inspections, LeaseRepository leases) {
    this.inspections = inspections;
    this.leases = leases;
  }

  public void activation(UUID lease) {
    if (accepted(lease, InspectionKind.MOVE_OUT))
      throw LeaseAccessService.conflict("A completed move-out handover cannot be activated again.");
    if (inspections.policy(lease).filter(InspectionPolicy::moveInRequired).isPresent()
        && !accepted(lease, InspectionKind.MOVE_IN))
      throw LeaseAccessService.conflict(
          "The tenant must accept the move-in inspection before activation.");
  }

  public void checkout(RentalLease lease) {
    if (inspections.policy(lease.id()).isPresent() && !moveOutAccepted(lease))
      throw LeaseAccessService.conflict(
          "Share and resolve the move-out inspection, including tenant vacancy and handover"
              + " confirmation, before completing checkout.");
  }

  public boolean accepted(UUID lease, InspectionKind kind) {
    return inspections
        .kind(lease, kind)
        .filter(i -> i.status() == InspectionStatus.ACCEPTED)
        .isPresent();
  }

  public boolean moveOutAccepted(RentalLease lease) {
    return inspections
        .kind(lease.id(), InspectionKind.MOVE_OUT)
        .filter(i -> i.status() == InspectionStatus.ACCEPTED)
        .map(i -> inspections.revision(i.id(), i.acceptedRevisionId()).orElseThrow())
        .filter(r -> withinMoveOutWindow(lease, r.details().observedAt()))
        .isPresent();
  }

  public boolean withinMoveOutWindow(RentalLease lease, Instant observed) {
    var revision = leases.revision(lease.id(), lease.currentRevisionId());
    var end =
        leases
            .openTermination(lease.id())
            .filter(t -> t.details().status() == LeaseTerminationStatus.ACCEPTED)
            .map(t -> t.details().proposedEndDate())
            .orElse(revision.terms().endDate());
    return (lease.activatedAt() == null || !observed.isBefore(lease.activatedAt()))
        && !observed.atZone(ZoneOffset.UTC).toLocalDate().isBefore(end.minusDays(7));
  }
}
