package com.zaza.housing.service;

import com.zaza.housing.dto.CreateInspectionRequest;
import com.zaza.housing.dto.DisputeInspectionRequest;
import com.zaza.housing.dto.EnableInspectionsRequest;
import com.zaza.housing.dto.InspectionDetails;
import com.zaza.housing.dto.InspectionItem;
import com.zaza.housing.dto.InspectionKey;
import com.zaza.housing.dto.InspectionMeter;
import com.zaza.housing.dto.InspectionResponse;
import com.zaza.housing.dto.LeaseCheckoutResponse;
import com.zaza.housing.dto.ReviewInspectionRequest;
import com.zaza.housing.dto.ReviseInspectionRequest;
import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.InspectionStatus;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.InspectionRevision;
import com.zaza.housing.model.LeaseInspection;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.InspectionEvidenceRepository;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.util.AgreementDocuments;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InspectionService {
  private final InspectionAccessService access;
  private final InspectionRepository inspections;
  private final InspectionEvidenceRepository evidence;
  private final LeaseRepository leases;
  private final InspectionActivityService activity;
  private final InspectionReadService reads;
  private final InspectionGuardService guard;
  private final AuthService auth;
  private final Clock clock;
  private final ObjectMapper json;

  public InspectionService(
      InspectionAccessService access,
      InspectionRepository inspections,
      InspectionEvidenceRepository evidence,
      LeaseRepository leases,
      InspectionActivityService activity,
      InspectionReadService reads,
      InspectionGuardService guard,
      AuthService auth,
      Clock clock,
      ObjectMapper json) {
    this.access = access;
    this.inspections = inspections;
    this.evidence = evidence;
    this.leases = leases;
    this.activity = activity;
    this.reads = reads;
    this.guard = guard;
    this.auth = auth;
    this.clock = clock;
    this.json = json;
  }

  @Transactional
  public LeaseCheckoutResponse enable(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, EnableInspectionsRequest request) {
    var lease = access.lease(actor, org, property, id, true);
    access.manager(actor, org, lease);
    if (activity.replay(actor, id, request.requestId(), "TRACKING_ENABLED", request).isPresent())
      return reads.checkout(actor, org, property, id);
    LeaseAccessService.version(lease, request.leaseVersion());
    LeaseAccessService.state(lease, LeaseStatus.SIGNED, LeaseStatus.ACTIVE);
    if (inspections.policy(id).isPresent())
      throw LeaseAccessService.conflict(
          "Inspection tracking is already enabled and cannot be disabled.");
    inspections.enable(id, actor.id(), lease.status() == LeaseStatus.SIGNED, clock.instant());
    activity.finish(
        actor,
        org,
        lease,
        request.requestId(),
        "TRACKING_ENABLED",
        request,
        id,
        null,
        null,
        "Inspection tracking enabled for this lease.",
        List.of(),
        true,
        true);
    return reads.checkout(actor, org, property, id);
  }

  @Transactional
  public InspectionResponse create(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID leaseId,
      CreateInspectionRequest request) {
    var lease = access.operationalLease(actor, org, property, leaseId, true);
    access.manager(actor, org, lease);
    var replay = activity.replay(actor, leaseId, request.requestId(), "CREATED", request);
    if (replay.isPresent()) return reads.response(access.report(lease, replay.get(), false), false);
    var policy =
        inspections
            .policy(leaseId)
            .orElseThrow(
                () ->
                    LeaseAccessService.conflict(
                        "Enable inspection tracking before creating reports."));
    if (request.kind() == InspectionKind.MOVE_IN && !policy.moveInRequired())
      throw LeaseAccessService.conflict(
          "A new move-in baseline cannot be backfilled after activation.");
    checkPhase(lease, request.kind());
    if (inspections.kind(leaseId, request.kind()).isPresent())
      throw LeaseAccessService.conflict(
          "This lease already has this inspection. Revise its draft or disputed report.");
    UUID id = UUID.randomUUID(), revision = UUID.randomUUID();
    details(lease, request.kind(), id, request.details());
    inspections.create(id, leaseId, request.kind(), revision, clock.instant());
    addRevision(lease, id, request.kind(), revision, 1, request.details());
    activity.finish(
        actor,
        org,
        lease,
        request.requestId(),
        "CREATED",
        request,
        id,
        id,
        revision,
        "Inspection draft created.",
        List.of(),
        false,
        false);
    return reads.response(access.report(lease, id, false), false);
  }

  @Transactional
  public InspectionResponse revise(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID leaseId,
      UUID id,
      ReviseInspectionRequest request) {
    var lease = access.operationalLease(actor, org, property, leaseId, true);
    access.manager(actor, org, lease);
    var report = access.report(lease, id, false);
    var body = body(id, request);
    if (activity.replay(actor, leaseId, request.requestId(), "REVISED", body).isPresent())
      return reads.response(report, false);
    access.version(report, request.version());
    access.editable(report);
    checkPhase(lease, report.kind());
    details(lease, report.kind(), id, request.details());
    var old = inspections.revision(id, report.currentRevisionId()).orElseThrow();
    UUID revision = UUID.randomUUID();
    addRevision(lease, id, report.kind(), revision, old.number() + 1, request.details());
    inspections.revise(id, revision, clock.instant());
    activity.finish(
        actor,
        org,
        lease,
        request.requestId(),
        "REVISED",
        body,
        id,
        id,
        revision,
        "Inspection draft revised.",
        List.of(),
        false,
        false);
    return reads.response(access.report(lease, id, false), false);
  }

  @Transactional
  public InspectionResponse share(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID leaseId,
      UUID id,
      ReviewInspectionRequest request) {
    var lease = access.operationalLease(actor, org, property, leaseId, true);
    access.manager(actor, org, lease);
    auth.reauthenticate(actor, request.password());
    var report = access.report(lease, id, false);
    var body = body(id, request);
    if (activity.replay(actor, leaseId, request.requestId(), "SHARED", body).isPresent())
      return reads.response(report, false);
    access.version(report, request.version());
    access.editable(report);
    checkPhase(lease, report.kind());
    if (report.currentRevisionId().equals(report.sharedRevisionId()))
      throw LeaseAccessService.conflict(
          "Create a revised report to address the tenant's dispute before sharing again.");
    var revision = review(report, report.currentRevisionId(), request);
    details(lease, report.kind(), id, revision.details());
    inspections.share(report, revision, actor.id(), request.reason().strip(), clock.instant());
    activity.finish(
        actor,
        org,
        lease,
        request.requestId(),
        "SHARED",
        body,
        id,
        id,
        revision.id(),
        request.reason().strip(),
        List.of(),
        true,
        true);
    return reads.response(access.report(lease, id, false), false);
  }

  @Transactional
  public InspectionResponse accept(
      AuthenticatedUser actor, UUID leaseId, UUID id, ReviewInspectionRequest request) {
    var lease = access.lease(actor, null, null, leaseId, true);
    auth.reauthenticate(actor, request.password());
    var report = access.report(lease, id, true);
    var body = body(id, request);
    if (activity.replay(actor, leaseId, request.requestId(), "ACCEPTED", body).isPresent())
      return reads.response(report, true);
    access.version(report, request.version());
    pending(report);
    checkPhase(lease, report.kind());
    var revision = review(report, report.sharedRevisionId(), request);
    details(lease, report.kind(), id, revision.details());
    inspections.decision(
        report,
        revision,
        actor.id(),
        true,
        request.vacancyConfirmed(),
        request.reason().strip(),
        List.of(),
        clock.instant());
    activity.finish(
        actor,
        null,
        lease,
        request.requestId(),
        "ACCEPTED",
        body,
        id,
        id,
        revision.id(),
        request.reason().strip(),
        List.of(),
        true,
        true);
    return reads.response(access.report(lease, id, true), true);
  }

  @Transactional
  public InspectionResponse dispute(
      AuthenticatedUser actor, UUID leaseId, UUID id, DisputeInspectionRequest request) {
    var lease = access.lease(actor, null, null, leaseId, true);
    var report = access.report(lease, id, true);
    var body = body(id, request);
    if (activity.replay(actor, leaseId, request.requestId(), "DISPUTED", body).isPresent())
      return reads.response(report, true);
    access.version(report, request.version());
    pending(report);
    if (!report.sharedRevisionId().equals(request.revisionId()))
      throw LeaseAccessService.conflict("Review the currently shared report revision.");
    var revision = inspections.revision(id, report.sharedRevisionId()).orElseThrow();
    var keys =
        revision.details().items().stream()
            .map(InspectionItem::key)
            .collect(java.util.stream.Collectors.toSet());
    if (new HashSet<>(request.itemKeys()).size() != request.itemKeys().size()
        || !keys.containsAll(request.itemKeys()))
      throw WorkAccessService.invalid(
          "Dispute item keys must be unique items in the shared report.");
    inspections.decision(
        report,
        revision,
        actor.id(),
        false,
        false,
        request.reason().strip(),
        request.itemKeys(),
        clock.instant());
    activity.finish(
        actor,
        null,
        lease,
        request.requestId(),
        "DISPUTED",
        body,
        id,
        id,
        revision.id(),
        request.reason().strip(),
        request.itemKeys(),
        true,
        true);
    return reads.response(access.report(lease, id, true), true);
  }

  private InspectionRevision review(
      LeaseInspection report, UUID expected, ReviewInspectionRequest request) {
    if (!Boolean.TRUE.equals(request.consent()))
      throw WorkAccessService.invalid(
          "Confirm that you reviewed the exact condition, meter readings, key counts and evidence"
              + " in this report.");
    if (report.kind() == InspectionKind.MOVE_OUT
        && !Boolean.TRUE.equals(request.vacancyConfirmed()))
      throw WorkAccessService.invalid(
          "Confirm vacancy and possession/key handover for the move-out report.");
    if (!expected.equals(request.revisionId()))
      throw LeaseAccessService.conflict("Review the current report revision.");
    var revision = inspections.revision(report.id(), expected).orElseThrow();
    if (!revision.documentHash().equals(request.documentHash())
        || !AgreementDocuments.hash(revision.document()).equals(revision.documentHash()))
      throw LeaseAccessService.conflict("The reviewed document digest does not match this report.");
    return revision;
  }

  private void pending(LeaseInspection report) {
    if (report.status() != InspectionStatus.AWAITING_TENANT)
      throw LeaseAccessService.conflict(
          "Only the currently shared report can be accepted or disputed.");
  }

  private void checkPhase(RentalLease lease, InspectionKind kind) {
    if (lease.unitReleasedAt() != null)
      throw LeaseAccessService.conflict("Inspections cannot change after unit release.");
    if (kind == InspectionKind.MOVE_IN) LeaseAccessService.state(lease, LeaseStatus.SIGNED);
    else LeaseAccessService.state(lease, LeaseStatus.SIGNED, LeaseStatus.ACTIVE);
  }

  private void details(RentalLease lease, InspectionKind kind, UUID id, InspectionDetails details) {
    if (details.observedAt().isAfter(clock.instant())
        || details.observedAt().isBefore(lease.createdAt()))
      throw WorkAccessService.invalid(
          "The observation time must be between lease creation and now.");
    if (kind == InspectionKind.MOVE_OUT && !guard.withinMoveOutWindow(lease, details.observedAt()))
      throw LeaseAccessService.conflict(
          "Move-out inspection must follow activation and occur within seven days before the"
              + " currently agreed end date or later.");
    unique(details.items().stream().map(InspectionItem::key).toList());
    unique(details.meters().stream().map(InspectionMeter::key).toList());
    unique(details.keys().stream().map(InspectionKey::key).toList());
    unique(details.evidenceIds());
    for (UUID image : details.evidenceIds())
      if (evidence.find(id, image, false).isEmpty())
        throw WorkAccessService.invalid("Every evidence image must belong to this inspection.");
  }

  private void unique(List<?> keys) {
    if (new HashSet<>(keys).size() != keys.size())
      throw WorkAccessService.invalid(
          "Item, meter, key and evidence identifiers must be unique within their lists.");
  }

  private void addRevision(
      RentalLease lease,
      UUID id,
      InspectionKind kind,
      UUID revision,
      int number,
      InspectionDetails details) {
    String currency = leases.revision(lease.id(), lease.currentRevisionId()).terms().currency();
    String document =
        json.writeValueAsString(
            new TreeMap<>(
                Map.of(
                    "inspectionId",
                    id,
                    "leaseId",
                    lease.id(),
                    "kind",
                    kind,
                    "number",
                    number,
                    "currency",
                    currency,
                    "details",
                    details)));
    inspections.revision(
        revision,
        id,
        number,
        details,
        currency,
        document,
        AgreementDocuments.hash(document),
        clock.instant());
  }

  private Map<String, Object> body(UUID id, Object request) {
    return Map.of("inspectionId", id, "request", request);
  }
}
