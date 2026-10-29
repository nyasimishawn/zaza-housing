package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AdministrationRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdministrationActivityService {
  private final AdministrationRepository repository;
  private final ObjectMapper json;
  private final Clock clock;
  private final AuditService audit;

  public AdministrationActivityService(
      AdministrationRepository repository, ObjectMapper json, Clock clock, AuditService audit) {
    this.repository = repository;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
  }

  @SuppressWarnings("unchecked")
  private String fingerprint(Object body) {
    Map<String, Object> fields =
        new TreeMap<>(json.readValue(json.writeValueAsString(body), Map.class));
    for (String k : List.of("password", "code", "recoveryCode")) fields.remove(k);
    return RequestFingerprints.of(json, fields);
  }

  public UUID replay(AuthenticatedUser a, UUID request, String action, UUID target, Object body) {
    var old = repository.operation(request);
    if (old == null) return null;
    if (!a.id().equals(old.get("actor_id"))
        || !action.equals(old.get("action"))
        || (target != null && !target.equals(old.get("target_id")))
        || !fingerprint(body).equals(old.get("fingerprint")))
      throw AgencyAccessService.conflict(
          "This request ID was used for different content. Reload before continuing.");
    return (UUID) old.get("target_id");
  }

  public void record(
      AuthenticatedUser a,
      UUID request,
      String action,
      String kind,
      UUID target,
      Object body,
      String reason,
      boolean internal) {
    repository.operation(request, a.id(), action, target, fingerprint(body), clock.instant());
    repository.event(a.id(), kind, target, action, reason, internal, clock.instant());
    audit.record(a.id(), null, action, target);
  }
}
