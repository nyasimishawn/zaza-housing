package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyAgentResponse;
import com.zaza.housing.dto.AgencyAssignmentRequest;
import com.zaza.housing.dto.AgencyDashboardResponse;
import com.zaza.housing.dto.AgencyEnquiryResponse;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.dto.AgencyWorkflowChoice;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.model.AgencyClient;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AgencyLead;
import com.zaza.housing.model.AgencyPropertyAssignment;
import com.zaza.housing.model.AgencyTask;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.repository.ListingRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyWorkspaceService {
  private final AgencyAccessService access;
  private final AgencyRepository repository;
  private final AgencyActivityService activity;
  private final AgencyClientService clients;
  private final ListingRepository listings;
  private final Clock clock;

  public AgencyWorkspaceService(
      AgencyAccessService access,
      AgencyRepository repository,
      AgencyActivityService activity,
      AgencyClientService clients,
      ListingRepository listings,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.clients = clients;
    this.listings = listings;
    this.clock = clock;
  }

  private void page(AuthenticatedUser a, UUID o, int limit, int offset) {
    access.get(a, o, false, false);
    Pagination.validate(limit, offset);
  }

  public List<AgencyEnquiryResponse> enquiries(
      AuthenticatedUser a, UUID org, int limit, int offset) {
    page(a, org, limit, offset);
    access.manager(access.get(a, org, false, true));
    return repository.enquiries(org, limit, offset);
  }

  public AgencyDashboardResponse dashboard(AuthenticatedUser a, UUID o) {
    var org = access.get(a, o, false, false);
    return repository.dashboard(o, a.id(), access.manages(org), clock.instant());
  }

  public List<AgencyPropertyResponse> properties(AuthenticatedUser a, UUID o, int l, int f) {
    page(a, o, l, f);
    return repository.properties(o, a.id(), l, f);
  }

  public List<AgencyAgentResponse> agents(AuthenticatedUser a, UUID o, int l, int f) {
    page(a, o, l, f);
    return repository.agents(o, l, f);
  }

  public List<AgencyPropertyAssignment> assignments(AuthenticatedUser a, UUID o, int l, int f) {
    page(a, o, l, f);
    return repository.assignments(o, a.id(), l, f);
  }

  public List<AgencyClient> clients(AuthenticatedUser a, UUID o, String q, int l, int f) {
    page(a, o, l, f);
    query(q);
    return repository.clients(o, a.id(), q, l, f);
  }

  public List<AgencyLead> leads(
      AuthenticatedUser a, UUID o, String stage, boolean mine, String q, int l, int f) {
    page(a, o, l, f);
    query(q);
    if (stage != null
        && !Set.of("NEW", "QUALIFIED", "VIEWING", "APPLICATION", "WON", "LOST").contains(stage))
      throw AgencyAccessService.bad("Unknown stage.");
    return repository.leads(o, a.id(), stage, mine, q, l, f);
  }

  public List<AgencyTask> tasks(
      AuthenticatedUser a, UUID o, UUID lead, String status, String due, int l, int f) {
    page(a, o, l, f);
    if (status != null && !Set.of("OPEN", "DONE", "CANCELLED").contains(status)
        || due != null && !Set.of("OVERDUE", "UPCOMING").contains(due))
      throw AgencyAccessService.bad("Unknown task filter.");
    return repository.tasks(o, a.id(), lead, status, due, clock.instant(), l, f);
  }

  public List<AgencyWorkflowChoice> workflow(
      AuthenticatedUser a, UUID o, UUID id, String kind, int limit, int offset) {
    page(a, o, limit, offset);
    if (kind != null && !Set.of("VIEWING", "APPLICATION", "LEASE").contains(kind))
      throw AgencyAccessService.bad("Unknown workflow kind.");
    var lead = access.lead(a, o, id, false);
    access.property(a, o, lead.propertyId(), null, false);
    return repository.workflow(lead, clients.get(a, o, lead.clientId()).customerUserId()).stream()
        .filter(v -> kind == null || kind.equals(v.kind()))
        .skip(offset)
        .limit(limit)
        .toList();
  }

  public List<PublicListingResponse> matches(
      AuthenticatedUser a, UUID o, UUID client, int l, int f) {
    page(a, o, l, f);
    return listings.agencyMatches(o, a.id(), clients.get(a, o, client).details(), l, f);
  }

  public List<AgencyEvent> history(
      AuthenticatedUser a, UUID o, String kind, UUID id, int l, int f) {
    page(a, o, l, f);
    switch (kind) {
      case "CLIENT" -> clients.get(a, o, id);
      case "LEAD" -> access.lead(a, o, id, false);
      case "TASK" ->
          repository.task(o, a.id(), id, false).orElseThrow(AgencyAccessService::missing);
      default -> throw AgencyAccessService.bad("Unknown record kind.");
    }
    return repository.history(o, kind, id, l, f);
  }

  @Transactional
  public AgencyPropertyAssignment assign(AuthenticatedUser a, UUID o, AgencyAssignmentRequest r) {
    access.get(a, o, true, true);
    access.agent(o, r.agentId());
    access.property(a, o, r.propertyId(), null, true);
    if (activity.replay(a, o, r.requestId(), "AGENCY_PROPERTY_ASSIGNED", null, r) != null)
      return repository
          .assignment(o, r.propertyId(), r.agentId())
          .orElseThrow(AgencyAccessService::missing);
    var old = repository.assignment(o, r.propertyId(), r.agentId());
    if (old.isPresent()) AgencyAccessService.version(old.get().version(), r.version());
    else if (r.version() != null)
      throw AgencyAccessService.conflict("This assignment does not exist.");
    var result = repository.assign(o, r.propertyId(), r.agentId(), r.active(), clock.instant());
    activity.record(
        a, o, r.requestId(), "AGENCY_PROPERTY_ASSIGNED", "ASSIGNMENT", result.id(), r, r.message());
    return result;
  }

  private void query(String q) {
    if (q != null && q.length() > 150)
      throw AgencyAccessService.bad("Search must be at most 150 characters.");
  }
}
