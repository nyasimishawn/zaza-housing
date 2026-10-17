package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VaultDocument;
import com.zaza.housing.model.VerificationCase;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.NotificationRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentActivityService {
  private final DocumentRepository documents;
  private final AuditService audit;
  private final NotificationRepository notifications;
  private final ObjectMapper json;
  private final Clock clock;

  public DocumentActivityService(
      DocumentRepository documents,
      AuditService audit,
      NotificationRepository notifications,
      ObjectMapper json,
      Clock clock) {
    this.documents = documents;
    this.audit = audit;
    this.notifications = notifications;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> replay(
      AuthenticatedUser actor, String scope, UUID request, String action, Object body) {
    var op = documents.operation(scope, request);
    if (op.isEmpty()) return Optional.empty();
    var row = op.get();
    if (!actor.id().equals(row.get("actor_id"))
        || !action.equals(row.get("action"))
        || !RequestFingerprints.of(json, body).equals(row.get("fingerprint")))
      throw DocumentAccessService.conflict(
          "Request ID belongs to another actor, action, target or payload.");
    return Optional.of((UUID) row.get("target_id"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void document(
      AuthenticatedUser actor,
      VaultDocument d,
      UUID request,
      String action,
      Object body,
      UUID target,
      String reason) {
    String scope = DocumentAccessService.scope(d.ownerUserId(), d.organizationId());
    operation(actor, scope, request, action, body, target);
    UUID event =
        documents.event(d.id(), null, actor.id(), "OWNER", action, reason, false, clock.instant());
    audit.record(actor.id(), d.organizationId(), "VAULT_" + action, d.id());
    for (UUID user : documents.recipients(d.ownerUserId(), d.organizationId()))
      notifications.vault(
          user, actor.id(), event, action, url(d.organizationId(), "documents", d.id()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void verification(
      AuthenticatedUser actor,
      VerificationCase c,
      UUID request,
      String action,
      Object body,
      String reason,
      boolean reviewerVisible,
      boolean notify) {
    operation(
        actor,
        DocumentAccessService.scope(c.ownerUserId(), c.organizationId()),
        request,
        action,
        body,
        c.id());
    UUID event =
        documents.event(
            null,
            c.id(),
            actor.id(),
            "PLATFORM_ADMIN".equals(actor.platformRole()) && !actor.id().equals(c.ownerUserId())
                ? "OPERATOR"
                : "OWNER",
            action,
            reason,
            reviewerVisible,
            clock.instant());
    audit.record(actor.id(), c.organizationId(), "VAULT_" + action, c.id());
    if (notify)
      for (UUID user : documents.recipients(c.ownerUserId(), c.organizationId()))
        notifications.vault(
            user, actor.id(), event, action, url(c.organizationId(), "verifications", c.id()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void operation(
      AuthenticatedUser actor,
      String scope,
      UUID request,
      String action,
      Object body,
      UUID target) {
    documents.operation(
        scope,
        request,
        actor.id(),
        action,
        target,
        RequestFingerprints.of(json, body),
        clock.instant());
  }

  public String url(UUID org, String resource, UUID id) {
    return (org == null ? "/api/v1/me" : "/api/v1/organizations/" + org)
        + "/"
        + resource
        + "/"
        + id;
  }
}
