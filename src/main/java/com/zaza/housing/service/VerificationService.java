package com.zaza.housing.service;

import com.zaza.housing.dto.CreateVerificationRequest;
import com.zaza.housing.dto.DocumentCommandRequest;
import com.zaza.housing.dto.ReviseVerificationRequest;
import com.zaza.housing.dto.SubmitVerificationRequest;
import com.zaza.housing.dto.VerificationDetails;
import com.zaza.housing.dto.VerificationResponse;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VerificationCase;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.VerificationRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationService {
  private final VerificationAccessService access;
  private final DocumentAccessService owners;
  private final VerificationRepository cases;
  private final DocumentRepository documents;
  private final DocumentActivityService activity;
  private final VerificationReadService reads;
  private final AuthService auth;
  private final Clock clock;

  public VerificationService(
      VerificationAccessService access,
      DocumentAccessService owners,
      VerificationRepository cases,
      DocumentRepository documents,
      DocumentActivityService activity,
      VerificationReadService reads,
      AuthService auth,
      Clock clock) {
    this.access = access;
    this.owners = owners;
    this.cases = cases;
    this.documents = documents;
    this.activity = activity;
    this.reads = reads;
    this.auth = auth;
    this.clock = clock;
  }

  @Transactional
  public VerificationResponse create(
      AuthenticatedUser actor, UUID org, CreateVerificationRequest request) {
    owners.owner(actor, org, true);
    UUID user = org == null ? actor.id() : null;
    String scope = DocumentAccessService.scope(user, org);
    var replay =
        activity.replay(actor, scope, request.requestId(), "VERIFICATION_CREATED", request);
    if (replay.isPresent())
      return reads.response(access.own(actor, org, replay.get(), false), false);
    var active = cases.active(user, org);
    if (active.isPresent()) {
      var old = active.get();
      if (old.status() == VerificationStatus.VERIFIED
          && (!old.validUntil().isAfter(clock.instant()) || !cases.currentBusinessName(old))) {
        var status =
            old.validUntil().isAfter(clock.instant())
                ? VerificationStatus.REVOKED
                : VerificationStatus.EXPIRED;
        cases.resolve(
            old.id(), status, "Previous verification is no longer current.", null, clock.instant());
        activity.verification(
            actor,
            access.find(old.id()),
            UUID.randomUUID(),
            "VERIFICATION_INVALIDATED",
            Map.of("caseId", old.id()),
            "Previous verification is no longer current.",
            true,
            true);
      } else
        throw DocumentAccessService.conflict(
            "A current verification case already exists for this subject.");
    }
    var details = access.details(user, org, request.details());
    UUID id = UUID.randomUUID(), submission = UUID.randomUUID();
    cases.create(id, user, org, submission, actor.id(), clock.instant());
    addSubmission(actor, id, submission, 1, details);
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_CREATED",
        request,
        "A private verification draft was created.",
        false,
        false);
    return reads.response(current, false);
  }

  @Transactional
  public VerificationResponse revise(
      AuthenticatedUser actor, UUID org, UUID id, ReviseVerificationRequest request) {
    var c = access.own(actor, org, id, true);
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), org);
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_REVISED", body)
        .isPresent()) return reads.response(c, false);
    access.version(c, request.version());
    editable(c);
    var details = access.details(c.ownerUserId(), org, request.details());
    var old = cases.submission(id, c.currentSubmissionId());
    if (old.number() >= 20)
      throw DocumentAccessService.conflict("A verification case permits at most 20 submissions.");
    UUID submission = UUID.randomUUID();
    addSubmission(actor, id, submission, old.number() + 1, details);
    cases.current(id, submission, clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_REVISED",
        body,
        "A verification draft was revised.",
        false,
        false);
    return reads.response(current, false);
  }

  @Transactional
  public VerificationResponse submit(
      AuthenticatedUser actor, UUID org, UUID id, SubmitVerificationRequest request) {
    var c = access.own(actor, org, id, true);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), org);
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_SUBMITTED", body)
        .isPresent()) return reads.response(c, false);
    access.version(c, request.version());
    editable(c);
    if (!Boolean.TRUE.equals(request.consent()))
      throw DocumentAccessService.invalid(
          "Consent to independent review of these exact document versions for verification.");
    if (!c.currentSubmissionId().equals(request.submissionId())
        || c.currentSubmissionId().equals(c.sharedSubmissionId()))
      throw DocumentAccessService.conflict("Review a new draft submission before submitting.");
    var submission = cases.submission(id, c.currentSubmissionId());
    access.details(c.ownerUserId(), org, submission.details());
    cases.submit(id, submission.id(), actor.id(), clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_SUBMITTED",
        body,
        "A frozen verification submission is ready for independent review.",
        true,
        true);
    return reads.response(current, false);
  }

  @Transactional
  public VerificationResponse withdraw(
      AuthenticatedUser actor, UUID org, UUID id, DocumentCommandRequest request) {
    var c = access.own(actor, org, id, true);
    var body = Map.of("caseId", id, "request", request);
    String scope = DocumentAccessService.scope(c.ownerUserId(), org);
    if (activity
        .replay(actor, scope, request.requestId(), "VERIFICATION_WITHDRAWN", body)
        .isPresent()) return reads.response(c, false);
    access.version(c, request.version());
    if (Set.of(
            VerificationStatus.REJECTED,
            VerificationStatus.WITHDRAWN,
            VerificationStatus.REVOKED,
            VerificationStatus.EXPIRED)
        .contains(c.status()))
      throw DocumentAccessService.conflict("This verification case is already closed.");
    cases.resolve(
        id, VerificationStatus.WITHDRAWN, request.reason().strip(), null, clock.instant());
    var current = access.find(id);
    activity.verification(
        actor,
        current,
        request.requestId(),
        "VERIFICATION_WITHDRAWN",
        body,
        request.reason().strip(),
        true,
        true);
    return reads.response(current, false);
  }

  private void editable(VerificationCase c) {
    if (c.status() != VerificationStatus.DRAFT
        && c.status() != VerificationStatus.CHANGES_REQUESTED)
      throw DocumentAccessService.conflict(
          "Only a draft or changes-requested verification case can be revised or submitted.");
  }

  private void addSubmission(
      AuthenticatedUser actor, UUID id, UUID submission, int number, VerificationDetails details) {
    cases.submission(submission, id, number, details, actor.id(), clock.instant());
    int position = 1;
    for (UUID version : details.documentVersionIds()) {
      var d = documents.forVersion(version).orElseThrow(DocumentAccessService::missing);
      cases.evidence(id, submission, d.id(), version, position++);
    }
  }
}
