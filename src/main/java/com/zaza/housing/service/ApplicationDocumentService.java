package com.zaza.housing.service;

import com.zaza.housing.dto.ApplicationDocumentResponse;
import com.zaza.housing.dto.RevokeDocumentGrantRequest;
import com.zaza.housing.dto.ShareApplicationDocumentRequest;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.DocumentGrant;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.RentalApplication;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.NotificationRepository;
import com.zaza.housing.repository.RentalApplicationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationDocumentService {
  private final DocumentRepository documents;
  private final RentalApplicationRepository applications;
  private final PropertyService properties;
  private final DocumentAccessService access;
  private final DocumentReadService reads;
  private final DocumentActivityService activity;
  private final NotificationRepository notifications;
  private final AuditService audit;
  private final Clock clock;

  public ApplicationDocumentService(
      DocumentRepository documents,
      RentalApplicationRepository applications,
      PropertyService properties,
      DocumentAccessService access,
      DocumentReadService reads,
      DocumentActivityService activity,
      NotificationRepository notifications,
      AuditService audit,
      Clock clock) {
    this.documents = documents;
    this.applications = applications;
    this.properties = properties;
    this.access = access;
    this.reads = reads;
    this.activity = activity;
    this.notifications = notifications;
    this.audit = audit;
    this.clock = clock;
  }

  private RentalApplication application(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, boolean lock) {
    if (org == null) {
      var initial =
          applications.own(actor.id(), id, false).orElseThrow(DocumentAccessService::missing);
      if (lock) properties.lockForApplication(initial.propertyId());
      return applications.own(actor.id(), id, lock).orElseThrow(DocumentAccessService::missing);
    }
    if (lock) properties.lockForManagement(actor, org, property);
    else properties.getForManagement(actor, org, property);
    return applications.review(property, id, lock).orElseThrow(DocumentAccessService::missing);
  }

  private boolean reviewable(RentalApplication a) {
    return Set.of(
            ApplicationStatus.SUBMITTED,
            ApplicationStatus.UNDER_REVIEW,
            ApplicationStatus.INFORMATION_REQUESTED,
            ApplicationStatus.APPROVED,
            ApplicationStatus.LEASED)
        .contains(a.status());
  }

  @Transactional
  public ApplicationDocumentResponse share(
      AuthenticatedUser actor, UUID applicationId, ShareApplicationDocumentRequest request) {
    var app = application(actor, null, null, applicationId, true);
    var d = access.own(actor, null, request.documentId(), true);
    var body = Map.of("applicationId", applicationId, "request", request);
    String scope = DocumentAccessService.scope(actor.id(), null);
    var replay =
        activity.replay(actor, scope, request.requestId(), "APPLICATION_DOCUMENT_SHARED", body);
    if (replay.isPresent())
      return response(
          app,
          documents.grant(applicationId, replay.get()).orElseThrow(DocumentAccessService::missing));
    if (!Boolean.TRUE.equals(request.consent()))
      throw DocumentAccessService.invalid(
          "Confirm sharing this exact document version with the property's current application"
              + " reviewers.");
    if (app.version() != request.applicationVersion() || !reviewable(app))
      throw DocumentAccessService.conflict(
          "Use a current submitted application that is still open for review.");
    access.active(d);
    access.version(d, request.documentVersionId());
    if (!request.expiresAt().isAfter(clock.instant())
        || request.expiresAt().isAfter(clock.instant().plus(Duration.ofDays(30)))
        || request.expiresAt().isAfter(d.retainUntil()))
      throw DocumentAccessService.invalid(
          "Sharing must expire in the next 30 days and before the document retention deadline.");
    if (documents.grantCount(applicationId) >= 100)
      throw DocumentAccessService.conflict(
          "An application can record at most 100 document grants.");
    var previous = documents.activeGrant(applicationId, request.documentVersionId());
    if (previous.isPresent()) {
      if (previous.get().expiresAt().isAfter(clock.instant()))
        throw DocumentAccessService.conflict(
            "An active grant already shares this version. Revoke it before granting new consent.");
      documents.revoke(previous.get().id(), clock.instant());
    }
    UUID grant =
        documents.grant(
            d.id(),
            request.documentVersionId(),
            applicationId,
            request.expiresAt(),
            actor.id(),
            clock.instant());
    activity.document(
        actor,
        d,
        request.requestId(),
        "APPLICATION_DOCUMENT_SHARED",
        body,
        grant,
        "A document version was explicitly shared for application screening.");
    UUID event =
        documents.event(
            d.id(),
            null,
            actor.id(),
            "OWNER",
            "APPLICATION_SCREENING_SHARED",
            "A private application document was shared.",
            false,
            clock.instant());
    documents
        .applicationReviewers(app.propertyId())
        .forEach(
            m ->
                notifications.vault(
                    m.userId(),
                    actor.id(),
                    event,
                    "APPLICATION_DOCUMENT_SHARED",
                    "/api/v1/organizations/"
                        + m.organizationId()
                        + "/properties/"
                        + app.propertyId()
                        + "/applications/"
                        + app.id()
                        + "/documents"));
    return response(app, documents.grant(applicationId, grant).orElseThrow());
  }

  @Transactional
  public ApplicationDocumentResponse revoke(
      AuthenticatedUser actor,
      UUID applicationId,
      UUID grantId,
      RevokeDocumentGrantRequest request) {
    var app = application(actor, null, null, applicationId, true);
    access.owner(actor, null, true);
    var grant = documents.grant(applicationId, grantId).orElseThrow(DocumentAccessService::missing);
    var d = access.own(actor, null, grant.documentId(), false);
    var body = Map.of("applicationId", applicationId, "grantId", grantId, "request", request);
    String scope = DocumentAccessService.scope(actor.id(), null);
    if (activity
        .replay(actor, scope, request.requestId(), "APPLICATION_DOCUMENT_REVOKED", body)
        .isPresent()) return response(app, grant);
    if (grant.version() != request.version() || grant.revokedAt() != null)
      throw DocumentAccessService.conflict("Use the current active document grant.");
    documents.revoke(grantId, clock.instant());
    activity.document(
        actor,
        d,
        request.requestId(),
        "APPLICATION_DOCUMENT_REVOKED",
        body,
        grantId,
        request.reason().strip());
    return response(app, documents.grant(applicationId, grantId).orElseThrow());
  }

  public List<ApplicationDocumentResponse> list(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, int limit, int offset) {
    var app = application(actor, org, property, id, false);
    Pagination.validate(limit, offset);
    if (org != null && !reviewable(app)) throw DocumentAccessService.missing();
    return documents.grants(id, limit, offset).stream()
        .filter(g -> org == null || available(app, g))
        .map(g -> response(app, g))
        .toList();
  }

  @Transactional
  public ImageContent content(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, UUID grantId) {
    var app = application(actor, org, property, id, true);
    var grant = documents.grant(id, grantId).orElseThrow(DocumentAccessService::missing);
    // Property -> personal-vault is the only cross-domain lock order.
    access.lockSubject(app.applicantId(), null);
    grant = documents.grant(id, grantId).orElseThrow(DocumentAccessService::missing);
    if (!available(app, grant)) throw DocumentAccessService.missing();
    var d = access.find(grant.documentId());
    var v = access.version(d, grant.documentVersionId());
    var result = reads.content(v);
    audit.record(actor.id(), org, "VAULT_APPLICATION_DOCUMENT_READ", grantId);
    return result;
  }

  private boolean available(RentalApplication app, DocumentGrant grant) {
    return reviewable(app)
        && grant.revokedAt() == null
        && grant.expiresAt().isAfter(clock.instant())
        && access.find(grant.documentId()).status() == DocumentStatus.ACTIVE;
  }

  private ApplicationDocumentResponse response(RentalApplication app, DocumentGrant g) {
    var d = access.find(g.documentId());
    return new ApplicationDocumentResponse(
        g.id(),
        d.id(),
        d.category(),
        d.title(),
        reads.response(access.version(d, g.documentVersionId())),
        g.version(),
        "APPLICATION_SCREENING",
        g.expiresAt(),
        g.revokedAt() != null,
        available(app, g),
        g.createdAt());
  }
}
