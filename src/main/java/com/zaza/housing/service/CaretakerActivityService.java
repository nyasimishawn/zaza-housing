package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CaretakerActivityService {
  private final CaretakerRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final AuditService audit;

  public CaretakerActivityService(
      CaretakerRepository repository, ObjectMapper json, Clock clock, AuditService audit) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
  }

  public UUID replay(
      AuthenticatedUser a, UUID o, UUID request, String action, UUID target, Object body) {
    var old = repository.operation(o, request);
    if (old.isEmpty()) return null;
    var op = old.get();
    if (!a.id().equals(op.get("actor_id"))
        || !action.equals(op.get("action"))
        || (target != null && !target.equals(op.get("target_id")))
        || !RequestFingerprints.of(json, body).equals(op.get("fingerprint")))
      throw AgencyAccessService.conflict(
          "This request ID was already used with different content.");
    return (UUID) op.get("target_id");
  }

  public void record(
      AuthenticatedUser a,
      UUID o,
      UUID request,
      UUID property,
      String action,
      String kind,
      UUID target,
      Object body,
      String message,
      UUID recipient,
      UUID recipientOrg) {
    repository.operation(
        o, a.id(), request, action, target, RequestFingerprints.of(json, body), clock.instant());
    UUID event =
        repository.event(o, a.id(), property, kind, target, action, message, clock.instant());
    if (recipient != null) repository.notify(recipient, a.id(), recipientOrg, target, event, kind);
    repository.notifyManagers(property, a.id(), target, event, kind);
    audit.record(a.id(), o, action, target);
  }
}
