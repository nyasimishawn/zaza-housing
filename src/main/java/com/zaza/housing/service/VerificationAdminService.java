package com.zaza.housing.service;

import com.zaza.housing.dto.DecideVerificationRequest;
import com.zaza.housing.dto.VerificationCommandRequest;
import com.zaza.housing.dto.VerificationQueueResponse;
import com.zaza.housing.enums.VerificationDecision;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.VerificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationAdminService {
  private final VerificationAccessService access;
  private final DocumentAccessService owners;
  private final VerificationRepository cases;
  private final DocumentRepository documents;
  private final VerificationReadService reads;
  private final DocumentActivityService activity;
  private final AuthService auth;
  private final Clock clock;

  public VerificationAdminService(
      VerificationAccessService access,
      DocumentAccessService owners,
      VerificationRepository cases,
      DocumentRepository documents,
      VerificationReadService reads,
      DocumentActivityService activity,
      AuthService auth,
      Clock clock) {
    this.access = access;
    this.owners = owners;
    this.cases = cases;
    this.documents = documents;
    this.reads = reads;
    this.activity = activity;
    this.auth = auth;
    this.clock = clock;
  }

  @Transactional
  public VerificationQueueResponse claim(
      AuthenticatedUser actor, UUID id, VerificationCommandRequest request) {
    var c = access.operator(actor, id, true, false);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), c.organizationId());
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_ASSIGNED", body)
        .isPresent()) return reads.queue(c, actor);
    access.version(c, request.version());
    if (c.status() != VerificationStatus.SUBMITTED)
      throw DocumentAccessService.conflict(
          "Only a submitted unassigned verification case can be claimed.");
    cases.claim(id, actor.id(), clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_ASSIGNED",
        body,
        request.reason().strip(),
        true,
        true);
    return reads.queue(current, actor);
  }

  @Transactional
  public VerificationQueueResponse release(
      AuthenticatedUser actor, UUID id, VerificationCommandRequest request) {
    access.operator(actor);
    var initial = access.find(id);
    owners.lockSubject(initial.ownerUserId(), initial.organizationId());
    var c = access.find(id);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), c.organizationId());
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_RELEASED", body)
        .isPresent()) return reads.queue(c, actor);
    access.version(c, request.version());
    if (c.status() != VerificationStatus.UNDER_REVIEW || !actor.id().equals(c.assignedTo()))
      throw DocumentAccessService.missing();
    // A reviewer who becomes related to the subject may still relinquish access.
    cases.resolve(
        id, VerificationStatus.SUBMITTED, request.reason().strip(), null, clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_RELEASED",
        body,
        request.reason().strip(),
        true,
        true);
    return reads.queue(current, actor);
  }

  @Transactional
  public VerificationQueueResponse decide(
      AuthenticatedUser actor, UUID id, DecideVerificationRequest request) {
    var c = access.operator(actor, id, true, false);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), c.organizationId());
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_DECIDED", body)
        .isPresent()) return reads.queue(c, actor);
    access.version(c, request.version());
    if (c.status() != VerificationStatus.UNDER_REVIEW || !actor.id().equals(c.assignedTo()))
      throw DocumentAccessService.missing();
    VerificationStatus status =
        switch (request.decision()) {
          case VERIFY -> VerificationStatus.VERIFIED;
          case REQUEST_CHANGES -> VerificationStatus.CHANGES_REQUESTED;
          case REJECT -> VerificationStatus.REJECTED;
        };
    if (request.decision() == VerificationDecision.VERIFY) {
      if (!Boolean.TRUE.equals(request.checksConfirmed())
          || request.validUntil() == null
          || !request.validUntil().isAfter(clock.instant())
          || request.validUntil().isAfter(clock.instant().plus(Duration.ofDays(365))))
        throw DocumentAccessService.invalid(
            "Confirm completed manual checks and set validity in the next 365 days.");
      if (!cases.currentBusinessName(c))
        throw DocumentAccessService.conflict(
            "The business name changed. Request a corrected submission before verifying.");
      for (UUID version :
          cases.submission(id, c.sharedSubmissionId()).details().documentVersionIds()) {
        var d = documents.forVersion(version).orElseThrow(DocumentAccessService::missing);
        owners.active(d);
        if (d.retainUntil().isBefore(request.validUntil()))
          throw DocumentAccessService.conflict(
              "Evidence retention must cover the full verification validity period.");
      }
    } else if (request.validUntil() != null)
      throw DocumentAccessService.invalid(
          "Only an approved verification may set a validity deadline.");
    cases.resolve(id, status, request.reason().strip(), request.validUntil(), clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_DECIDED",
        body,
        request.reason().strip(),
        true,
        true);
    return reads.queue(current, actor);
  }

  @Transactional
  public VerificationQueueResponse revoke(
      AuthenticatedUser actor, UUID id, VerificationCommandRequest request) {
    var c = access.operator(actor, id, true, false);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), c.organizationId());
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_REVOKED", body)
        .isPresent()) return reads.queue(c, actor);
    access.version(c, request.version());
    if (c.status() != VerificationStatus.VERIFIED)
      throw DocumentAccessService.conflict("Only a previously verified case can be revoked.");
    cases.resolve(id, VerificationStatus.REVOKED, request.reason().strip(), null, clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_REVOKED",
        body,
        request.reason().strip(),
        true,
        true);
    return reads.queue(current, actor);
  }
}
