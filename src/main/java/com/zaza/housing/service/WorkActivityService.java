package com.zaza.housing.service;

import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.repository.WorkRequestRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkActivityService {
  private final WorkRequestRepository work;
  private final AuditService audit;
  private final NotificationService notifications;
  private final ObjectMapper json;

  public WorkActivityService(
      WorkRequestRepository work,
      AuditService audit,
      NotificationService notifications,
      ObjectMapper json) {
    this.work = work;
    this.audit = audit;
    this.notifications = notifications;
    this.json = json;
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>();
      map.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
      return sorted;
    }
    if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
    return value;
  }

  private String fingerprint(Object request) {
    return AgreementDocuments.hash(
        json.writeValueAsString(canonical(json.convertValue(request, Object.class))));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> replay(
      AuthenticatedUser actor, UUID scope, UUID request, String action, Object body) {
    var old = work.operation(scope, request);
    if (old.isEmpty()) return Optional.empty();
    var record = old.get();
    if (!actor.id().equals(record.get("actor_id"))
        || !action.equals(record.get("action"))
        || !fingerprint(body).equals(record.get("fingerprint")))
      throw WorkAccessService.conflict(
          "Request ID was already used for another action, actor or payload.");
    return Optional.of((UUID) record.get("work_id"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean replay(
      AuthenticatedUser actor,
      WorkAccess access,
      UUID request,
      String action,
      Object body,
      long expected) {
    if (replay(actor, access.work().id(), request, action, body).isPresent()) return true;
    if (access.work().version() != expected)
      throw WorkAccessService.conflict("The request changed. Reload before continuing.");
    return false;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      AuthenticatedUser actor,
      WorkAccess access,
      UUID scope,
      UUID request,
      String action,
      Object body,
      String message,
      WorkVisibility visibility,
      boolean initial) {
    work.operation(scope, request, actor.id(), action, fingerprint(body), access.work().id());
    if (!initial) work.touch(access.work().id());
    var current = work.find(access.work().id()).orElseThrow();
    UUID event = work.event(current, actor.id(), access.party(), action, visibility, message);
    audit.recordSnapshot(
        actor.id(),
        access.organizationId(),
        "WORK_" + action,
        current.id(),
        initial ? null : access.work(),
        current);
    notifications.workChanged(
        actor.id(), current, access.work().providerId(), event, action, message, visibility);
  }
}
