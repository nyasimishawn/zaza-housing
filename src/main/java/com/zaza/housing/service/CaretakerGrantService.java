package com.zaza.housing.service;

import com.zaza.housing.dto.CaretakerGrantCommand;
import com.zaza.housing.dto.CaretakerInvitationRequest;
import com.zaza.housing.dto.CaretakerMemberResponse;
import com.zaza.housing.enums.CaretakerCapability;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.CaretakerGrant;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaretakerGrantService {
  private final CaretakerAccessService access;
  private final CaretakerRepository repository;
  private final OrganizationRepository memberships;
  private final CaretakerActivityService activity;
  private final AuthService auth;
  private final Clock clock;

  public CaretakerGrantService(
      CaretakerAccessService access,
      CaretakerRepository repository,
      OrganizationRepository memberships,
      CaretakerActivityService activity,
      AuthService auth,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.memberships = memberships;
    this.activity = activity;
    this.auth = auth;
    this.clock = clock;
  }

  public List<CaretakerGrant> list(AuthenticatedUser a, UUID o, UUID p, int l, int f) {
    access.organization(a, o, false);
    Pagination.validate(l, f);
    if (p != null) access.property(a, o, p, "OPERATIONS", false);
    return repository.grants(o, a.id(), p, l, f);
  }

  public CaretakerGrant get(AuthenticatedUser a, UUID o, UUID id) {
    return access.grant(a, o, id, false);
  }

  public List<CaretakerEvent> history(AuthenticatedUser a, UUID o, UUID id, int l, int f) {
    get(a, o, id);
    Pagination.validate(l, f);
    return repository.history("GRANT", id, l, f);
  }

  @Transactional
  public CaretakerGrant invite(AuthenticatedUser a, UUID o, CaretakerInvitationRequest r) {
    access.requireManager(access.organization(a, o, true));
    auth.reauthenticate(a, r.password());
    if (!Boolean.TRUE.equals(r.consent()))
      throw AgencyAccessService.bad("Confirm the caretaker responsibilities.");
    var p = access.property(a, o, r.propertyId(), "OPERATIONS", true);
    access.active(p);
    UUID replay = activity.replay(a, o, r.requestId(), "CARETAKER_INVITED", null, r);
    if (replay != null) return get(a, o, replay);
    AgencyAccessService.version(p.version(), r.propertyVersion());
    if (!r.capabilities().contains(CaretakerCapability.OPERATIONS))
      throw AgencyAccessService.bad("Operations access is required.");
    if (!repository.future(r.expiresAt())
        || r.expiresAt().isAfter(clock.instant().plus(730, ChronoUnit.DAYS)))
      throw AgencyAccessService.bad("Assignment expiry must be within two years.");
    var member =
        memberships
            .member(o, r.userId())
            .filter(m -> m.role() == MembershipRole.MEMBER)
            .orElseThrow(
                () ->
                    AgencyAccessService.bad(
                        "Choose a current member; managers already have operations access."));
    if (!repository.activeMember(o, r.userId()))
      throw AgencyAccessService.bad("Choose an active team member.");
    for (UUID id : repository.expired(o, p.id(), r.userId())) {
      repository.grantStatus(id, "EXPIRED", clock.instant());
      repository.event(
          o,
          a.id(),
          p.id(),
          "GRANT",
          id,
          "CARETAKER_EXPIRED",
          "Assignment expired.",
          clock.instant());
    }
    if (repository.openGrant(o, p.id(), r.userId()))
      throw AgencyAccessService.conflict(
          "An open assignment already exists. Revoke it before changing responsibilities.");
    UUID id = UUID.randomUUID();
    repository.invite(
        id,
        o,
        a,
        p,
        new CaretakerMemberResponse(member.userId(), member.displayName(), member.role().name()),
        r,
        clock.instant());
    activity.record(
        a,
        o,
        r.requestId(),
        p.id(),
        "CARETAKER_INVITED",
        "GRANT",
        id,
        r,
        "Caretaker responsibilities offered.",
        member.userId(),
        o);
    return get(a, o, id);
  }

  @Transactional
  public CaretakerGrant command(
      AuthenticatedUser a, UUID o, UUID id, String command, CaretakerGrantCommand r) {
    var org = access.organization(a, o, true);
    var g = access.grant(a, o, id, true);
    auth.reauthenticate(a, r.password());
    if (!Boolean.TRUE.equals(r.consent()))
      throw AgencyAccessService.bad("Confirm the assignment decision.");
    String action = "CARETAKER_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, o, r.requestId(), action, id, r) != null) return get(a, o, id);
    AgencyAccessService.version(g.version(), r.version());
    if (!Set.of("INVITED", "ACTIVE").contains(g.status()))
      throw AgencyAccessService.conflict("This assignment is closed.");
    switch (command) {
      case "accept" -> {
        if (!g.organizationId().equals(o)
            || !a.id().equals(g.userId())
            || !g.status().equals("INVITED"))
          throw WorkAccessService.forbidden(
              "Only the invited caretaker can accept this invitation.");
        var p = access.propertyForInvitation(a, o, g.propertyId());
        access.active(p);
        if (p.version() != g.propertyVersion())
          throw AgencyAccessService.conflict(
              "The offered property changed. Ask for a new invitation.");
        if (!repository.future(g.expiresAt()))
          throw AgencyAccessService.conflict("This invitation expired.");
        repository.grantStatus(id, "ACTIVE", clock.instant());
      }
      case "decline" -> {
        if (!g.organizationId().equals(o)
            || !a.id().equals(g.userId())
            || !g.status().equals("INVITED"))
          throw WorkAccessService.forbidden("Only the invited caretaker can decline.");
        repository.grantStatus(id, "DECLINED", clock.instant());
      }
      case "revoke" -> {
        access.requireManager(org);
        repository.grantStatus(id, "REVOKED", clock.instant());
      }
      default -> throw AgencyAccessService.bad("Unknown caretaker decision.");
    }
    activity.record(
        a,
        o,
        r.requestId(),
        g.propertyId(),
        action,
        "GRANT",
        id,
        r,
        r.message(),
        g.userId(),
        g.organizationId());
    return get(a, o, id);
  }
}
