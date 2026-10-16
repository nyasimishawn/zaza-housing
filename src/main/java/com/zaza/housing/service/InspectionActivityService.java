package com.zaza.housing.service;

import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InspectionActivityService {
  private final InspectionRepository inspections;
  private final LeaseActivityService leases;
  private final AuditService audit;
  private final ObjectMapper json;
  private final Clock clock;

  public InspectionActivityService(
      InspectionRepository inspections,
      LeaseActivityService leases,
      AuditService audit,
      ObjectMapper json,
      Clock clock) {
    this.inspections = inspections;
    this.leases = leases;
    this.audit = audit;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> replay(
      AuthenticatedUser actor, UUID lease, UUID request, String action, Object body) {
    var op = inspections.operation(lease, request);
    if (op.isEmpty()) return Optional.empty();
    var row = op.get();
    if (!actor.id().equals(row.get("actor_id"))
        || !action.equals(row.get("action"))
        || !RequestFingerprints.of(json, body).equals(row.get("fingerprint")))
      throw LeaseAccessService.conflict(
          "Request ID belongs to another actor, action, target or payload.");
    return Optional.of((UUID) row.get("target_id"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(
      AuthenticatedUser actor,
      UUID org,
      RentalLease lease,
      UUID request,
      String action,
      Object body,
      UUID target,
      UUID inspection,
      UUID revision,
      String reason,
      List<String> keys,
      boolean visible,
      boolean notify) {
    inspections.operation(
        lease.id(),
        request,
        actor.id(),
        action,
        target,
        RequestFingerprints.of(json, body),
        clock.instant());
    inspections.event(
        lease.id(),
        inspection,
        revision,
        actor.id(),
        org == null ? "TENANT" : "LESSOR",
        action,
        reason,
        keys,
        visible,
        clock.instant());
    audit.record(actor.id(), org, "INSPECTION_" + action, target);
    if (notify)
      leases.record(
          actor,
          org,
          null,
          lease,
          org == null ? LeaseParty.TENANT : LeaseParty.LESSOR,
          LeaseEventType.INSPECTION_UPDATED,
          "A private lease inspection or handover was updated. Open the lease for details.",
          true,
          true);
  }
}
