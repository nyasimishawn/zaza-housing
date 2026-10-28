package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyMandateCommand;
import com.zaza.housing.dto.AgencyMandateRequest;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.model.AgencyMandate;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyMandateRepository;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyMandateService {
  private final AgencyMandateRepository repository;
  private final OrganizationService organizations;
  private final OrganizationRepository memberships;
  private final PropertyService properties;
  private final AgencyActivityService activity;
  private final AgencyRepository crm;
  private final AuthService auth;
  private final Clock clock;

  public AgencyMandateService(
      AgencyMandateRepository repository,
      OrganizationService organizations,
      OrganizationRepository memberships,
      PropertyService properties,
      AgencyActivityService activity,
      AgencyRepository crm,
      AuthService auth,
      Clock clock) {
    this.repository = repository;
    this.organizations = organizations;
    this.memberships = memberships;
    this.properties = properties;
    this.activity = activity;
    this.crm = crm;
    this.auth = auth;
    this.clock = clock;
  }

  private void access(AuthenticatedUser a, UUID org, boolean write) {
    if (write) memberships.lock(org);
    var o = organizations.get(a, org);
    if (o.type() == OrganizationType.SERVICE_PROVIDER || o.role() == MembershipRole.MEMBER)
      throw WorkAccessService.forbidden(
          "Owner or administrator membership is required for mandates.");
  }

  public List<AgencyMandate> list(AuthenticatedUser a, UUID o, int l, int f) {
    access(a, o, false);
    Pagination.validate(l, f);
    return repository.list(o, l, f);
  }

  public AgencyMandate get(AuthenticatedUser a, UUID o, UUID id) {
    access(a, o, false);
    return repository.get(o, id, false).orElseThrow(AgencyAccessService::missing);
  }

  public List<Map<String, Object>> agencies(AuthenticatedUser a, UUID o, String q, int l, int f) {
    access(a, o, false);
    Pagination.validate(l, f);
    if (q == null || q.strip().length() < 2 || q.length() > 150)
      throw AgencyAccessService.bad("Search by at least two characters of the agency name.");
    return repository.agencies(o, q.strip(), l, f);
  }

  public List<AgencyPropertyResponse> properties(AuthenticatedUser a, UUID o, int l, int f) {
    access(a, o, false);
    Pagination.validate(l, f);
    return repository.properties(o, l, f);
  }

  public List<com.zaza.housing.model.AgencyEvent> history(
      AuthenticatedUser a, UUID o, UUID id, int l, int f) {
    var m = get(a, o, id);
    Pagination.validate(l, f);
    return java.util.stream.Stream.concat(
            crm.history(m.ownerId(), "MANDATE", id, 100, 0).stream(),
            crm.history(m.agencyId(), "MANDATE", id, 100, 0).stream())
        .sorted(
            java.util.Comparator.comparing(com.zaza.housing.model.AgencyEvent::createdAt)
                .reversed())
        .skip(f)
        .limit(l)
        .toList();
  }

  @Transactional
  public AgencyMandate create(AuthenticatedUser a, UUID o, AgencyMandateRequest r) {
    access(a, o, true);
    auth.reauthenticate(a, r.password());
    UUID replay = activity.replay(a, o, r.requestId(), "AGENCY_MANDATE_SENT", null, r);
    if (replay != null) return get(a, o, replay);
    var p = properties.lockForApplication(r.propertyId());
    if (!p.ownerOrganizationId().equals(o)) throw AgencyAccessService.missing();
    AgencyAccessService.version(p.version(), r.propertyVersion());
    if (p.status() != PropertyStatus.ACTIVE
        || o.equals(r.agencyId())
        || !repository.isAgency(r.agencyId())
        || !repository.replaceable(p.id()))
      throw AgencyAccessService.conflict(
          "Choose an active owned property without a current managing agency.");
    if (!r.endsAt().isAfter(r.startsAt())
        || !r.endsAt().isAfter(clock.instant())
        || r.endsAt().isAfter(clock.instant().plusSeconds(5L * 366 * 86400)))
      throw AgencyAccessService.bad("Choose a valid mandate period ending within five years.");
    UUID id = repository.create(o, a.id(), p, r, clock.instant());
    activity.record(
        a,
        o,
        r.requestId(),
        "AGENCY_MANDATE_SENT",
        "MANDATE",
        id,
        r,
        "Owner offered frozen responsibilities and fee terms.");
    return get(a, o, id);
  }

  @Transactional
  public AgencyMandate command(
      AuthenticatedUser a, UUID o, UUID id, String command, AgencyMandateCommand r) {
    access(a, o, true);
    var initial = get(a, o, id);
    var p = properties.lockForApplication(initial.propertyId());
    var m = repository.get(o, id, true).orElseThrow(AgencyAccessService::missing);
    auth.reauthenticate(a, r.password());
    String action = "AGENCY_MANDATE_" + command.toUpperCase(java.util.Locale.ROOT);
    if (activity.replay(a, o, r.requestId(), action, id, r) != null) return get(a, o, id);
    AgencyAccessService.version(m.version(), r.version());
    String next;
    switch (command) {
      case "accept" -> {
        if (!o.equals(m.agencyId()) || !m.status().equals("SENT") || a.id().equals(m.requestedBy()))
          throw WorkAccessService.forbidden(
              "A separate agency representative must accept the offered mandate.");
        if (!p.ownerOrganizationId().equals(m.ownerId())
            || p.version() != m.propertyVersion()
            || p.status() != PropertyStatus.ACTIVE
            || !repository.replaceable(p.id())
            || !m.endsAt().isAfter(clock.instant()))
          throw AgencyAccessService.conflict(
              "The property or mandate changed. Request a new owner offer.");
        next = "ACTIVE";
        repository.state(id, next, a.id(), clock.instant());
        repository.delegate(m);
      }
      case "decline" -> {
        if (!o.equals(m.agencyId()) || !m.status().equals("SENT"))
          throw AgencyAccessService.conflict(
              "Only the receiving agency can decline a pending mandate.");
        next = "DECLINED";
        repository.state(id, next, null, clock.instant());
      }
      case "cancel" -> {
        if (!o.equals(m.ownerId()) || !m.status().equals("SENT"))
          throw AgencyAccessService.conflict("Only the owner can cancel a pending mandate.");
        next = "CANCELLED";
        repository.state(id, next, null, clock.instant());
      }
      case "revoke" -> {
        if (!m.status().equals("ACTIVE"))
          throw AgencyAccessService.conflict("Only an accepted mandate can be ended.");
        next = "REVOKED";
        repository.state(id, next, null, clock.instant());
        repository.revoke(m);
      }
      default -> throw AgencyAccessService.bad("Unknown mandate action.");
    }
    activity.record(a, o, r.requestId(), action, "MANDATE", id, r, r.message());
    return get(a, o, id);
  }
}
