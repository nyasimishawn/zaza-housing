package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.Pagination;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportCaseService {
  private final SupportRepository repository;
  private final SupportAccessService access;
  private final AdministrationRepository administration;
  private final AdministrationAccessService adminAccess;
  private final AdministrationActivityService activity;
  private final ListingRepository listings;
  private final Clock clock;

  public SupportCaseService(
      SupportRepository repository,
      SupportAccessService access,
      AdministrationRepository administration,
      AdministrationAccessService adminAccess,
      AdministrationActivityService activity,
      ListingRepository listings,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.administration = administration;
    this.adminAccess = adminAccess;
    this.activity = activity;
    this.listings = listings;
    this.clock = clock;
  }

  public List<SupportCaseResponse> list(
      AuthenticatedUser a,
      String token,
      boolean admin,
      String status,
      boolean mine,
      boolean overdue,
      int l,
      int f) {
    access.actor(a, token, admin, false);
    Pagination.validate(l, f);
    return repository.cases(admin ? null : a.id(), status, mine, a.id(), overdue, l, f).stream()
        .map(c -> access.redact(a, c, admin))
        .toList();
  }

  public SupportCaseResponse get(AuthenticatedUser a, String token, UUID id, boolean admin) {
    return access.redact(a, access.get(a, token, id, admin, false), admin);
  }

  @Transactional
  public SupportCaseResponse create(AuthenticatedUser a, String token, SupportCaseRequest r) {
    access.actor(a, token, false, true);
    if (!Set.of("SUPPORT", "DISPUTE", "LISTING_REPORT").contains(r.kind())
        || !Set.of("ACCOUNT", "RENTAL", "MAINTENANCE", "PAYMENTS", "SAFETY", "OTHER")
            .contains(r.category()))
      throw AgencyAccessService.bad("Choose a supported case type and category.");
    UUID replay = activity.replay(a, r.requestId(), "SUPPORT_CASE_CREATED", null, r);
    if (replay != null) return get(a, token, replay, false);
    Object snapshot = null;
    if (r.listingId() != null)
      snapshot = listings.publicListing(r.listingId()).orElseThrow(CaretakerAccessService::missing);
    if (r.kind().equals("LISTING_REPORT") && r.listingId() == null)
      throw AgencyAccessService.bad("Choose the public listing being reported.");
    UUID id =
        repository.create(
            a.id(),
            r,
            snapshot,
            administration.settingValue("support.firstResponseHours"),
            administration.settingValue("support.resolutionHours"),
            clock.instant());
    if (r.kind().equals("LISTING_REPORT")) {
      var source = administration.listing(r.listingId(), false);
      administration.requestReview(
          r.listingId(),
          ((Number) source.get("version")).longValue(),
          snapshot,
          "A user submitted a listing report. Review the assigned support case for authorized"
              + " evidence.",
          clock.instant());
    }
    activity.record(
        a,
        r.requestId(),
        "SUPPORT_CASE_CREATED",
        "SUPPORT_CASE",
        id,
        r,
        "Support case submitted.",
        false);
    repository.notify(a.id(), id, "Your support case was received.", clock.instant());
    return get(a, token, id, false);
  }

  public List<Map<String, Object>> messages(
      AuthenticatedUser a, String token, UUID id, boolean admin, int l, int f) {
    var c = access.get(a, token, id, admin, false);
    if (admin) access.assigned(a, c);
    Pagination.validate(l, f);
    return repository.messages(id, admin, l, f);
  }

  public List<Map<String, Object>> history(
      AuthenticatedUser a, String token, UUID id, boolean admin, int l, int f) {
    var c = access.get(a, token, id, admin, false);
    if (admin) access.assigned(a, c);
    Pagination.validate(l, f);
    return administration.history("SUPPORT_CASE", id, admin, l, f);
  }

  @Transactional
  public SupportCaseResponse reply(
      AuthenticatedUser a, String token, UUID id, boolean admin, SupportMessageRequest r) {
    var c = access.get(a, token, id, admin, true);
    if (admin) access.assigned(a, c);
    else if (r.internal())
      throw WorkAccessService.forbidden("Only the assigned operator can write internal notes.");
    String action = r.internal() ? "SUPPORT_INTERNAL_NOTE" : "SUPPORT_REPLY";
    if (activity.replay(a, r.requestId(), action, id, r) != null) return get(a, token, id, admin);
    AdministrationAccessService.version(c.version(), r.version());
    access.open(c);
    repository.message(id, a.id(), r.body().trim(), r.internal(), clock.instant());
    repository.touch(id, admin && !r.internal(), clock.instant());
    if (!admin && c.status().equals("WAITING_CUSTOMER")) {
      var updated = repository.get(id, false);
      repository.state(
          id, "IN_PROGRESS", c.assignedTo(), c.priority(), null, null, null, clock.instant());
    }
    activity.record(
        a,
        r.requestId(),
        action,
        "SUPPORT_CASE",
        id,
        r,
        r.internal() ? "Internal operator note added." : "Shared reply added.",
        r.internal());
    if (!r.internal())
      repository.notify(
          admin ? c.reporterId() : c.assignedTo(),
          id,
          admin
              ? "An operator replied to your support case."
              : "The reporter replied to your assigned case.",
          clock.instant());
    return get(a, token, id, admin);
  }

  @Transactional
  public SupportCaseResponse reporterCommand(
      AuthenticatedUser a, String token, UUID id, String command, SupportCaseCommandRequest r) {
    var c = access.get(a, token, id, false, true);
    String action = "SUPPORT_REPORTER_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, r.requestId(), action, id, r) != null) return c;
    AdministrationAccessService.version(c.version(), r.version());
    if (!c.status().equals("RESOLVED"))
      throw AgencyAccessService.conflict("Resolve the case before accepting or reopening it.");
    if (command.equals("close"))
      repository.state(
          id,
          "CLOSED",
          c.assignedTo(),
          c.priority(),
          c.resolution(),
          c.resolvedAt(),
          clock.instant(),
          clock.instant());
    else if (command.equals("reopen")) {
      if (c.resolvedAt() == null
          || c.resolvedAt().plus(Duration.ofDays(14)).isBefore(clock.instant()))
        throw AgencyAccessService.conflict("The reopening period ended. Create a follow-up case.");
      UUID operator = c.assignedTo();
      if (operator != null && !eligible(operator, c)) operator = null;
      repository.state(
          id,
          operator == null ? "NEW" : "IN_PROGRESS",
          operator,
          c.priority(),
          null,
          null,
          null,
          clock.instant());
      repository.message(id, a.id(), r.message().trim(), false, clock.instant());
    } else throw AgencyAccessService.bad("Unknown reporter action.");
    activity.record(a, r.requestId(), action, "SUPPORT_CASE", id, r, r.message().trim(), false);
    repository.notify(
        c.assignedTo(), id, "The reporter updated a case resolution.", clock.instant());
    return get(a, token, id, false);
  }

  @Transactional
  public SupportCaseResponse adminCommand(
      AuthenticatedUser a, String token, UUID id, String command, AdministrationCommandRequest r) {
    var c = access.get(a, token, id, true, true);
    adminAccess.password(a, r.password());
    if (a.id().equals(c.reporterId()))
      throw WorkAccessService.forbidden("A different operator must handle your own case.");
    String action = "SUPPORT_OPERATOR_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, r.requestId(), action, id, r) != null) return get(a, token, id, true);
    AdministrationAccessService.version(c.version(), r.version());
    access.open(c);
    String status = c.status(), priority = c.priority(), resolution = c.resolution();
    UUID assigned = c.assignedTo();
    Instant resolved = c.resolvedAt();
    if (command.equals("claim")) {
      if (assigned != null && !assigned.equals(a.id()) && eligible(assigned, c))
        throw AgencyAccessService.conflict("Another active operator owns this case.");
      assigned = a.id();
      if (!status.equals("ESCALATED")) status = "IN_PROGRESS";
    } else {
      access.assigned(a, c);
      switch (command) {
        case "release" -> {
          assigned = null;
          if (!status.equals("ESCALATED")) status = "NEW";
        }
        case "assign" -> {
          if (r.assigneeId() == null
              || r.assigneeId().equals(c.reporterId())
              || !repository.operator(r.assigneeId()))
            throw AgencyAccessService.bad("Choose another eligible active operator.");
          if (c.listingId() != null) {
            var source = administration.listing(c.listingId(), false);
            if (administration.related(
                r.assigneeId(),
                (UUID) source.get("owner_organization_id"),
                (UUID) source.get("manager_organization_id")))
              throw WorkAccessService.forbidden("Choose an operator independent of the property.");
          }
          assigned = r.assigneeId();
        }
        case "triage" -> {
          if (r.priority() == null
              || !Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(r.priority()))
            throw AgencyAccessService.bad("Choose a priority.");
          priority = r.priority();
        }
        case "escalate" -> {
          status = "ESCALATED";
          priority = "URGENT";
        }
        case "wait", "resolve" -> {
          if (r.resolution() == null || r.resolution().strip().length() < 20)
            throw AgencyAccessService.bad(
                "Write a clear shared response or resolution of at least 20 characters.");
          repository.message(id, a.id(), r.resolution().strip(), false, clock.instant());
          repository.touch(id, true, clock.instant());
          if (command.equals("wait")) status = "WAITING_CUSTOMER";
          else {
            status = "RESOLVED";
            resolution = r.resolution().strip();
            resolved = clock.instant();
          }
        }
        default -> throw AgencyAccessService.bad("Unknown support action.");
      }
    }
    repository.state(id, status, assigned, priority, resolution, resolved, null, clock.instant());
    activity.record(a, r.requestId(), action, "SUPPORT_CASE", id, r, r.reason().trim(), true);
    repository.notify(c.reporterId(), id, "Your support case status was updated.", clock.instant());
    if (assigned != null && !assigned.equals(a.id()))
      repository.notify(assigned, id, "A support case was assigned to you.", clock.instant());
    return get(a, token, id, true);
  }

  private boolean eligible(UUID user, SupportCaseResponse c) {
    if (!repository.operator(user) || user.equals(c.reporterId())) return false;
    if (c.listingId() == null) return true;
    var p = administration.listing(c.listingId(), false);
    return !administration.related(
        user, (UUID) p.get("owner_organization_id"), (UUID) p.get("manager_organization_id"));
  }

  public List<Map<String, Object>> operators(AuthenticatedUser a, String token, UUID id) {
    var c = access.get(a, token, id, true, false);
    access.assigned(a, c);
    return administration.operators(c.reporterId()).stream()
        .filter(
            o -> {
              if (c.listingId() == null) return true;
              var p = administration.listing(c.listingId(), false);
              return !administration.related(
                  (UUID) o.get("id"),
                  (UUID) p.get("owner_organization_id"),
                  (UUID) p.get("manager_organization_id"));
            })
        .toList();
  }

  public List<Map<String, Object>> notifications(AuthenticatedUser a, String token, int l, int f) {
    access.actor(a, token, false, false);
    Pagination.validate(l, f);
    return repository.notifications(a.id(), l, f);
  }

  @Transactional
  public void read(AuthenticatedUser a, String token, UUID id) {
    access.actor(a, token, false, true);
    repository.read(a.id(), id, clock.instant());
  }
}
