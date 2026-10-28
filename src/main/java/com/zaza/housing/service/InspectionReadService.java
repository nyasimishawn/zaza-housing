package com.zaza.housing.service;

import com.zaza.housing.dto.InspectionAttestationResponse;
import com.zaza.housing.dto.InspectionComparisonItem;
import com.zaza.housing.dto.InspectionComparisonResponse;
import com.zaza.housing.dto.InspectionEventResponse;
import com.zaza.housing.dto.InspectionItem;
import com.zaza.housing.dto.InspectionResponse;
import com.zaza.housing.dto.InspectionRevisionResponse;
import com.zaza.housing.dto.LeaseCheckoutResponse;
import com.zaza.housing.enums.InspectionKind;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.InspectionPolicy;
import com.zaza.housing.model.InspectionRevision;
import com.zaza.housing.model.LeaseInspection;
import com.zaza.housing.repository.BillingRepository;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InspectionReadService {
  private final InspectionAccessService access;
  private final InspectionRepository inspections;
  private final InspectionGuardService guard;
  private final BillingRepository billing;
  private final WorkRequestRepository work;
  private final AuditService audit;
  private final Clock clock;

  public InspectionReadService(
      InspectionAccessService access,
      InspectionRepository inspections,
      InspectionGuardService guard,
      BillingRepository billing,
      WorkRequestRepository work,
      AuditService audit,
      Clock clock) {
    this.access = access;
    this.inspections = inspections;
    this.guard = guard;
    this.billing = billing;
    this.work = work;
    this.audit = audit;
    this.clock = clock;
  }

  public List<InspectionResponse> list(
      AuthenticatedUser actor, UUID org, UUID property, UUID lease) {
    access.operationalLease(actor, org, property, lease, false);
    return inspections.list(lease, org == null).stream()
        .map(i -> response(i, org == null))
        .toList();
  }

  public InspectionResponse get(
      AuthenticatedUser actor, UUID org, UUID property, UUID lease, UUID id) {
    var l = access.operationalLease(actor, org, property, lease, false);
    return response(access.report(l, id, org == null), org == null);
  }

  public InspectionResponse response(LeaseInspection i, boolean tenant) {
    var revision =
        inspections
            .revision(i.id(), tenant ? i.sharedRevisionId() : i.currentRevisionId())
            .orElseThrow();
    return new InspectionResponse(
        i.id(),
        i.leaseId(),
        i.kind(),
        i.status(),
        i.version(),
        response(revision),
        i.acceptedRevisionId(),
        i.createdAt(),
        i.updatedAt());
  }

  public InspectionRevisionResponse response(InspectionRevision r) {
    return new InspectionRevisionResponse(
        r.id(), r.number(), r.details(), r.currency(), r.documentHash(), r.createdAt(), r.shared());
  }

  public List<InspectionRevisionResponse> revisions(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID lease,
      UUID id,
      int limit,
      int offset) {
    var l = access.operationalLease(actor, org, property, lease, false);
    access.report(l, id, org == null);
    Pagination.validate(limit, offset);
    return inspections.revisions(id, org == null, limit, offset).stream()
        .map(this::response)
        .toList();
  }

  @Transactional
  public InspectionRevision document(
      AuthenticatedUser actor, UUID org, UUID property, UUID lease, UUID id, UUID revisionId) {
    var l = access.operationalLease(actor, org, property, lease, true);
    access.report(l, id, org == null);
    var r =
        inspections
            .revision(id, revisionId)
            .orElseThrow(() -> WorkAccessService.missing("Inspection revision not found."));
    if (org == null && !r.shared())
      throw WorkAccessService.missing("Inspection revision not found.");
    audit.record(actor.id(), org, "INSPECTION_DOCUMENT_READ", revisionId);
    return r;
  }

  public List<InspectionEventResponse> history(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID lease,
      UUID id,
      int limit,
      int offset) {
    var l = access.operationalLease(actor, org, property, lease, false);
    access.report(l, id, org == null);
    Pagination.validate(limit, offset);
    return inspections.history(lease, id, org == null, limit, offset);
  }

  public LeaseCheckoutResponse checkout(AuthenticatedUser actor, UUID org, UUID property, UUID id) {
    var l = access.lease(actor, org, property, id, false);
    var policy = inspections.policy(id);
    boolean moveIn = guard.accepted(id, InspectionKind.MOVE_IN), moveOut = guard.moveOutAccepted(l);
    boolean inspectionReady = policy.isEmpty() || moveOut;
    boolean billingReady =
        billing.find(id).isEmpty() || billing.summary(id, LocalDate.now(clock)).checkoutReady();
    boolean maintenanceReady = !work.blocksCheckout(id);
    boolean closed = l.status() == LeaseStatus.COMPLETED || l.status() == LeaseStatus.TERMINATED;
    var steps = new ArrayList<String>();
    if (!inspectionReady) steps.add("ACCEPT_MOVE_OUT_INSPECTION");
    if (!closed) steps.add("CLOSE_LEASE_AT_AGREED_END");
    if (!billingReady) steps.add("CLEAR_AND_FINALIZE_BILLING");
    if (!maintenanceReady) steps.add("RESOLVE_MAINTENANCE");
    return new LeaseCheckoutResponse(
        policy.isPresent(),
        policy.map(InspectionPolicy::moveInRequired).orElse(false),
        moveIn,
        moveOut,
        inspectionReady,
        billingReady,
        maintenanceReady,
        closed,
        l.unitReleasedAt() != null,
        steps.isEmpty() && l.unitReleasedAt() == null,
        List.copyOf(steps));
  }

  public InspectionComparisonResponse comparison(
      AuthenticatedUser actor, UUID org, UUID property, UUID id) {
    access.operationalLease(actor, org, property, id, false);
    var in =
        inspections
            .kind(id, InspectionKind.MOVE_IN)
            .orElseThrow(() -> WorkAccessService.missing("No move-in baseline exists."));
    var out =
        inspections
            .kind(id, InspectionKind.MOVE_OUT)
            .orElseThrow(() -> WorkAccessService.missing("No move-out inspection exists."));
    if (in.acceptedRevisionId() == null || (org == null && out.sharedRevisionId() == null))
      throw LeaseAccessService.conflict(
          "An accepted baseline and visible move-out report are required.");
    var baseline = inspections.revision(in.id(), in.acceptedRevisionId()).orElseThrow();
    var current =
        inspections
            .revision(out.id(), org == null ? out.sharedRevisionId() : out.currentRevisionId())
            .orElseThrow();
    var before = new TreeMap<String, InspectionItem>();
    var after = new TreeMap<String, InspectionItem>();
    baseline.details().items().forEach(i -> before.put(i.key(), i));
    current.details().items().forEach(i -> after.put(i.key(), i));
    var keys = new TreeSet<String>(before.keySet());
    keys.addAll(after.keySet());
    var items =
        keys.stream()
            .map(
                k ->
                    new InspectionComparisonItem(
                        k,
                        before.get(k),
                        after.get(k),
                        !Objects.equals(before.get(k), after.get(k))))
            .toList();
    return new InspectionComparisonResponse(
        in.id(), baseline.id(), out.id(), current.id(), baseline.currency(), items);
  }

  public List<InspectionAttestationResponse> attestations(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID lease,
      UUID id,
      int limit,
      int offset) {
    var l = access.operationalLease(actor, org, property, lease, false);
    access.report(l, id, org == null);
    Pagination.validate(limit, offset);
    return inspections.attestations(id, limit, offset);
  }
}
