package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgencyActivityService {
  private final AgencyRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final AuditService audit;

  public AgencyActivityService(
      AgencyRepository repository, ObjectMapper json, Clock clock, AuditService audit) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
  }

  public UUID replay(
      AuthenticatedUser actor, UUID org, UUID request, String action, UUID target, Object body) {
    var old = repository.operation(org, request);
    if (old.isEmpty()) return null;
    var op = old.get();
    if (!actor.id().equals(op.get("actor_id"))
        || !action.equals(op.get("action"))
        || (target != null && !target.equals(op.get("target_id")))
        || !RequestFingerprints.of(json, body).equals(op.get("fingerprint")))
      throw AgencyAccessService.conflict(
          "This request ID was used for different content. Reload before continuing.");
    return (UUID) op.get("target_id");
  }

  public void record(
      AuthenticatedUser actor,
      UUID org,
      UUID request,
      String action,
      String kind,
      UUID target,
      Object body,
      String message) {
    repository.operation(
        org,
        actor.id(),
        request,
        action,
        target,
        RequestFingerprints.of(json, body),
        clock.instant());
    repository.event(org, actor.id(), kind, target, action, message, clock.instant());
    audit.record(actor.id(), org, action, target);
  }
}
