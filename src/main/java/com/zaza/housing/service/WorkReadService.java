package com.zaza.housing.service;

import com.zaza.housing.dto.ProviderCalendarResponse;
import com.zaza.housing.dto.ProviderJobResponse;
import com.zaza.housing.dto.ServiceQuoteResponse;
import com.zaza.housing.dto.WorkDashboardResponse;
import com.zaza.housing.dto.WorkDisputeResponse;
import com.zaza.housing.dto.WorkEventResponse;
import com.zaza.housing.dto.WorkRequestResponse;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.dto.WorkVisitResponse;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.repository.WorkRequestRepository;
import com.zaza.housing.repository.WorkVisitRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkReadService {
  private final WorkAccessService access;
  private final WorkRequestRepository work;
  private final WorkVisitRepository visits;
  private final PropertyService properties;
  private final ServiceProviderService providers;
  private final Clock clock;

  public WorkReadService(
      WorkAccessService access,
      WorkRequestRepository work,
      WorkVisitRepository visits,
      PropertyService properties,
      ServiceProviderService providers,
      Clock clock) {
    this.access = access;
    this.work = work;
    this.visits = visits;
    this.properties = properties;
    this.providers = providers;
    this.clock = clock;
  }

  public WorkView get(AuthenticatedUser actor, WorkParty party, UUID org, UUID property, UUID id) {
    return response(access.get(actor, party, org, property, id, false));
  }

  public WorkView response(WorkAccess a) {
    var w = a.work();
    if (a.party() == WorkParty.PROVIDER) {
      boolean address = w.status() != WorkStatus.ASSIGNED;
      return new ProviderJobResponse(
          w.id(),
          w.kind(),
          w.category(),
          w.title(),
          w.description(),
          w.priority(),
          w.status(),
          w.version(),
          w.countryCode(),
          w.city(),
          address ? w.address() : null,
          address ? w.unitLabel() : null,
          w.assignmentId(),
          w.quoteId(),
          w.visitId(),
          w.disputeId(),
          w.completionSummary(),
          w.createdAt());
    }
    return new WorkRequestResponse(
        w.id(),
        w.kind(),
        w.leaseId(),
        w.propertyId(),
        w.unitId(),
        w.category(),
        w.title(),
        w.description(),
        w.priority(),
        w.status(),
        w.version(),
        w.providerId(),
        w.assignmentId(),
        w.quoteId(),
        w.visitId(),
        w.disputeId(),
        w.completionSummary(),
        w.completedAt(),
        w.createdAt(),
        w.updatedAt());
  }

  private UUID scope(AuthenticatedUser actor, WorkParty party, UUID org, UUID property) {
    if (party == WorkParty.PROVIDER) {
      providers.authorize(actor, org, false);
      return org;
    }
    if (party == WorkParty.COORDINATOR) {
      properties.getForManagement(actor, org, property);
      return property;
    }
    return actor.id();
  }

  public List<WorkView> list(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      WorkStatus status,
      WorkKind kind,
      WorkPriority priority,
      int limit,
      int offset) {
    UUID scope = scope(actor, party, org, property);
    Pagination.validate(limit, offset);
    return work.list(party, scope, status, kind, priority, limit, offset).stream()
        .map(w -> response(new WorkAccess(w, party, org)))
        .toList();
  }

  public WorkDashboardResponse summary(
      AuthenticatedUser actor, WorkParty party, UUID org, UUID property) {
    return work.summary(party, scope(actor, party, org, property), clock.instant());
  }

  public List<WorkEventResponse> history(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      int limit,
      int offset) {
    var a = access.get(actor, party, org, property, id, false);
    Pagination.validate(limit, offset);
    return work.history(a.work(), party, limit, offset);
  }

  public List<ServiceQuoteResponse> quotes(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      int limit,
      int offset) {
    var a = access.get(actor, party, org, property, id, false);
    Pagination.validate(limit, offset);
    if (party == WorkParty.CUSTOMER && a.work().kind() == WorkKind.MAINTENANCE)
      throw WorkAccessService.forbidden(
          "Maintenance procurement is private to the business and its assigned provider.");
    return visits
        .quotes(id, party == WorkParty.PROVIDER ? a.work().assignmentId() : null, limit, offset)
        .stream()
        .map(
            q ->
                q.status().equals("PENDING") && !q.validUntil().isAfter(clock.instant())
                    ? new ServiceQuoteResponse(
                        q.id(),
                        q.assignmentId(),
                        q.amount(),
                        q.currency(),
                        q.scope(),
                        q.validUntil(),
                        "EXPIRED",
                        q.createdAt())
                    : q)
        .toList();
  }

  public List<WorkVisitResponse> visits(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      int limit,
      int offset) {
    var a = access.get(actor, party, org, property, id, false);
    Pagination.validate(limit, offset);
    return visits.visits(
        id, party == WorkParty.PROVIDER ? a.work().assignmentId() : null, limit, offset);
  }

  public List<WorkDisputeResponse> disputes(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      int limit,
      int offset) {
    access.get(actor, party, org, property, id, false);
    Pagination.validate(limit, offset);
    return work.disputes(id, limit, offset);
  }

  public List<ProviderCalendarResponse> calendar(
      AuthenticatedUser actor, UUID org, Instant from, Instant to) {
    providers.authorize(actor, org, false);
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
      throw WorkAccessService.invalid("Use a calendar window of at most 31 days.");
    return visits.calendar(org, from, to);
  }
}
