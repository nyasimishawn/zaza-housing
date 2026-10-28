package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyCommissionCommand;
import com.zaza.housing.dto.AgencyCommissionRequest;
import com.zaza.housing.model.AgencyCommission;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyCommissionRepository;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyCommissionService {
  private final AgencyAccessService access;
  private final AgencyCommissionRepository repository;
  private final AgencyRepository crm;
  private final AgencyActivityService activity;
  private final AuthService auth;
  private final Clock clock;

  public AgencyCommissionService(
      AgencyAccessService access,
      AgencyCommissionRepository repository,
      AgencyRepository crm,
      AgencyActivityService activity,
      AuthService auth,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.crm = crm;
    this.activity = activity;
    this.auth = auth;
    this.clock = clock;
  }

  public AgencyCommission get(AuthenticatedUser a, UUID org, UUID id) {
    var o = access.get(a, org, false, false);
    return repository
        .get(org, a.id(), access.manages(o), id, false)
        .orElseThrow(AgencyAccessService::missing);
  }

  public List<AgencyCommission> list(
      AuthenticatedUser a, UUID org, String status, int limit, int offset) {
    var o = access.get(a, org, false, false);
    Pagination.validate(limit, offset);
    if (status != null
        && !Set.of("SUBMITTED", "APPROVED", "REJECTED", "PAID", "VOID").contains(status))
      throw AgencyAccessService.bad("Unknown commission status.");
    return repository.list(org, a.id(), access.manages(o), status, limit, offset);
  }

  public List<AgencyEvent> history(AuthenticatedUser a, UUID org, UUID id, int limit, int offset) {
    get(a, org, id);
    Pagination.validate(limit, offset);
    return crm.history(org, "COMMISSION", id, limit, offset);
  }

  @Transactional
  public AgencyCommission create(AuthenticatedUser a, UUID org, AgencyCommissionRequest r) {
    var o = access.get(a, org, true, false);
    UUID replay = activity.replay(a, org, r.requestId(), "AGENCY_COMMISSION_SUBMITTED", null, r);
    if (replay != null) return get(a, org, replay);
    var lead = access.lead(a, org, r.leadId(), true);
    access.agent(org, r.agentId());
    if (!access.manages(o) && !r.agentId().equals(a.id()))
      throw WorkAccessService.forbidden("Agents can submit only their own commission claims.");
    if (!lead.stage().equals("WON")
        || lead.leaseId() == null
        || !r.agentId().equals(lead.wonAgentId()))
      throw AgencyAccessService.conflict(
          "Commission must belong to the recorded converting agent on a won lead.");
    String currency =
        repository
            .currency(lead.leaseId())
            .orElseThrow(
                () ->
                    AgencyAccessService.conflict(
                        "The source lease is no longer a signed agreement."));
    if (!r.currency().equalsIgnoreCase(currency))
      throw AgencyAccessService.bad("Use the signed lease currency.");
    UUID id = repository.create(org, a.id(), lead, r, clock.instant());
    activity.record(
        a, org, r.requestId(), "AGENCY_COMMISSION_SUBMITTED", "COMMISSION", id, r, r.agreement());
    return get(a, org, id);
  }

  @Transactional
  public AgencyCommission command(
      AuthenticatedUser a, UUID org, UUID id, String command, AgencyCommissionCommand r) {
    access.get(a, org, true, true);
    var c = repository.get(org, a.id(), true, id, true).orElseThrow(AgencyAccessService::missing);
    auth.reauthenticate(a, r.password());
    String action = "AGENCY_COMMISSION_" + command.toUpperCase(java.util.Locale.ROOT);
    if (activity.replay(a, org, r.requestId(), action, id, r) != null) return get(a, org, id);
    AgencyAccessService.version(c.version(), r.version());
    String status;
    switch (command) {
      case "approve" -> {
        if (!c.status().equals("SUBMITTED"))
          throw AgencyAccessService.conflict("Only a submitted commission can be approved.");
        independent(a, c);
        status = "APPROVED";
      }
      case "pay" -> {
        if (!c.status().equals("APPROVED"))
          throw AgencyAccessService.conflict(
              "Approve the commission before recording a completed payment.");
        independent(a, c);
        if (r.externalReference() == null
            || r.externalReference().strip().length() < 6
            || r.paidAt() == null
            || r.paidAt().isBefore(c.approvedAt())
            || r.paidAt().isAfter(clock.instant()))
          throw AgencyAccessService.bad(
              "Supply the completed transfer reference and a payment time after approval and no"
                  + " later than now.");
        status = "PAID";
      }
      case "reject" -> {
        if (!c.status().equals("SUBMITTED"))
          throw AgencyAccessService.conflict("Only a submitted commission can be rejected.");
        status = "REJECTED";
      }
      case "void" -> {
        if (!Set.of("SUBMITTED", "APPROVED").contains(c.status()))
          throw AgencyAccessService.conflict("Only an unpaid commission can be voided.");
        status = "VOID";
      }
      default -> throw AgencyAccessService.bad("Unknown commission action.");
    }
    repository.state(
        id,
        status,
        a.id(),
        status.equals("PAID") ? r.externalReference().strip() : null,
        status.equals("PAID") ? r.paidAt() : null,
        clock.instant());
    activity.record(a, org, r.requestId(), action, "COMMISSION", id, r, r.message());
    return get(a, org, id);
  }

  private void independent(AuthenticatedUser a, AgencyCommission c) {
    if (a.id().equals(c.agentId()))
      throw WorkAccessService.forbidden(
          "A different agency manager must approve and record payment of this commission.");
  }
}
