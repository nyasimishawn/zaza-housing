package com.zaza.housing.service;

import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.dto.VerificationEvidenceResponse;
import com.zaza.housing.dto.VerificationQueueResponse;
import com.zaza.housing.dto.VerificationResponse;
import com.zaza.housing.dto.VerificationSubmissionResponse;
import com.zaza.housing.dto.VerificationSummaryResponse;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.VerificationCase;
import com.zaza.housing.model.VerificationSubmission;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.VerificationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationReadService {
  private final VerificationAccessService access;
  private final DocumentAccessService owners;
  private final VerificationRepository cases;
  private final DocumentRepository documents;
  private final DocumentReadService reads;
  private final AuditService audit;
  private final Clock clock;

  public VerificationReadService(
      VerificationAccessService access,
      DocumentAccessService owners,
      VerificationRepository cases,
      DocumentRepository documents,
      DocumentReadService reads,
      AuditService audit,
      Clock clock) {
    this.access = access;
    this.owners = owners;
    this.cases = cases;
    this.documents = documents;
    this.reads = reads;
    this.audit = audit;
    this.clock = clock;
  }

  public VerificationResponse get(AuthenticatedUser actor, UUID org, UUID id) {
    return response(access.own(actor, org, id, false), false);
  }

  public List<VerificationResponse> list(AuthenticatedUser actor, UUID org, int limit, int offset) {
    return list(actor, org, null, limit, offset);
  }

  public List<VerificationResponse> list(
      AuthenticatedUser actor, UUID org, UUID property, int limit, int offset) {
    owners.owner(actor, org, false);
    Pagination.validate(limit, offset);
    if (property != null) access.property(org, property, false);
    return (property == null
            ? cases.list(actor.id(), org, limit, offset)
            : cases.propertyCases(org, property, limit, offset))
        .stream().map(c -> response(c, false)).toList();
  }

  public VerificationResponse response(VerificationCase c, boolean reviewer) {
    var submission =
        cases.submission(c.id(), reviewer ? c.sharedSubmissionId() : c.currentSubmissionId());
    return new VerificationResponse(
        c.id(),
        c.kind(),
        access.effective(c),
        c.version(),
        response(submission),
        c.validUntil(),
        c.resolution(),
        c.assignedTo() != null,
        c.createdAt(),
        c.updatedAt(),
        c.property());
  }

  public VerificationSubmissionResponse response(VerificationSubmission s) {
    return new VerificationSubmissionResponse(
        s.id(), s.number(), s.details(), s.submitted(), s.createdAt());
  }

  public List<VerificationSubmissionResponse> submissions(
      AuthenticatedUser actor, UUID org, UUID id, int limit, int offset) {
    access.own(actor, org, id, false);
    Pagination.validate(limit, offset);
    return cases.submissions(id, limit, offset).stream().map(this::response).toList();
  }

  public List<DocumentEventResponse> history(
      AuthenticatedUser actor, UUID org, UUID id, int limit, int offset) {
    access.own(actor, org, id, false);
    Pagination.validate(limit, offset);
    return documents.history(null, id, false, limit, offset);
  }

  public VerificationSummaryResponse summary(AuthenticatedUser actor, UUID org) {
    owners.owner(actor, org, false);
    var current = cases.active(org == null ? actor.id() : null, org);
    return current
        .filter(access::verified)
        .map(
            c ->
                new VerificationSummaryResponse(
                    "VERIFIED", "MANUAL_DOCUMENT_REVIEW", c.validUntil()))
        .orElse(new VerificationSummaryResponse("NOT_VERIFIED", null, null));
  }

  public List<VerificationQueueResponse> queue(
      AuthenticatedUser actor, VerificationStatus status, boolean mine, int limit, int offset) {
    access.operator(actor);
    Pagination.validate(limit, offset);
    return cases.queue(status, mine, actor.id(), clock.instant(), limit, offset).stream()
        .map(c -> queue(c, actor))
        .toList();
  }

  public VerificationQueueResponse queue(VerificationCase c, AuthenticatedUser actor) {
    return new VerificationQueueResponse(
        c.id(),
        c.kind(),
        access.effective(c),
        c.version(),
        actor.id().equals(c.assignedTo()),
        c.submittedAt(),
        c.updatedAt());
  }

  @Transactional
  public VerificationResponse review(AuthenticatedUser actor, UUID id) {
    var c = access.operator(actor, id, true, true);
    audit.record(actor.id(), null, "VAULT_VERIFICATION_EVIDENCE_READ", id);
    return response(c, true);
  }

  @Transactional
  public List<VerificationEvidenceResponse> evidence(AuthenticatedUser actor, UUID id) {
    var c = access.operator(actor, id, true, true);
    var s = cases.submission(id, c.sharedSubmissionId());
    audit.record(actor.id(), null, "VAULT_VERIFICATION_EVIDENCE_READ", id);
    return s.details().documentVersionIds().stream()
        .map(
            v -> {
              var d = documents.forVersion(v).orElseThrow(DocumentAccessService::missing);
              return new VerificationEvidenceResponse(
                  d.id(), d.category(), d.title(), reads.response(owners.version(d, v)));
            })
        .toList();
  }

  @Transactional
  public ImageContent content(AuthenticatedUser actor, UUID id, UUID version) {
    var c = access.operator(actor, id, true, true);
    if (!cases.evidenceContains(id, c.sharedSubmissionId(), version))
      throw DocumentAccessService.missing();
    var d = documents.forVersion(version).orElseThrow(DocumentAccessService::missing);
    owners.active(d);
    var result = reads.content(owners.version(d, version));
    audit.record(actor.id(), null, "VAULT_VERIFICATION_DOCUMENT_READ", version);
    return result;
  }

  public List<DocumentEventResponse> reviewHistory(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    access.operator(actor, id, false, true);
    Pagination.validate(limit, offset);
    return documents.history(null, id, true, limit, offset);
  }
}
