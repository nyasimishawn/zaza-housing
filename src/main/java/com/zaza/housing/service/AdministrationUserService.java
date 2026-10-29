package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationUserService {
  private final AdministrationRepository repository;
  private final AccountSecurityRepository accounts;
  private final AdministrationAccessService access;
  private final AdministrationActivityService activity;
  private final Clock clock;

  public AdministrationUserService(
      AdministrationRepository repository,
      AccountSecurityRepository accounts,
      AdministrationAccessService access,
      AdministrationActivityService activity,
      Clock clock) {
    this.repository = repository;
    this.accounts = accounts;
    this.access = access;
    this.activity = activity;
    this.clock = clock;
  }

  public List<AdminUserResponse> users(
      AuthenticatedUser a, String token, String q, Boolean active, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    if (q != null && q.length() > 150) throw AgencyAccessService.bad("Search is too long.");
    return repository.users(q == null ? "" : q.strip(), active, l, f);
  }

  public List<Map<String, Object>> actions(
      AuthenticatedUser a, String token, String status, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    return repository.actions(status, l, f);
  }

  public Map<String, Object> action(AuthenticatedUser a, String token, UUID id) {
    access.admin(a, token, false);
    var value = repository.action(id, false);
    if (value == null) throw CaretakerAccessService.missing();
    return value;
  }

  @Transactional
  public Map<String, Object> propose(AuthenticatedUser a, String token, AccountActionRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    if (!Set.of("RESTRICT", "REACTIVATE").contains(r.action()))
      throw AgencyAccessService.bad("Choose RESTRICT or REACTIVATE.");
    UUID replay = activity.replay(a, r.requestId(), "ACCOUNT_ACTION_REQUESTED", null, r);
    if (replay != null) return action(a, token, replay);
    var target = accounts.account(r.userId(), true);
    if (a.id().equals(r.userId()) || "PLATFORM_ADMIN".equals(target.get("platform_role")))
      throw WorkAccessService.forbidden(
          "Platform operators and your own account require the controlled operator procedure.");
    AdministrationAccessService.version(target.get("security_version"), r.version());
    boolean active = Boolean.TRUE.equals(target.get("active"));
    if (active != r.action().equals("RESTRICT"))
      throw AgencyAccessService.conflict("The account is already in the requested state.");
    UUID id = repository.propose(a.id(), r, clock.instant());
    activity.record(
        a,
        r.requestId(),
        "ACCOUNT_ACTION_REQUESTED",
        "USER_ACTION",
        id,
        r,
        r.reason().trim(),
        false);
    return action(a, token, id);
  }

  @Transactional
  public Map<String, Object> decide(
      AuthenticatedUser a, String token, UUID id, String command, AdministrationCommandRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    var p = repository.action(id, true);
    if (p == null) throw CaretakerAccessService.missing();
    String action = "ACCOUNT_ACTION_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, r.requestId(), action, id, r) != null) return p;
    AdministrationAccessService.version(p.get("version"), r.version());
    if (!"PENDING".equals(p.get("status")))
      throw AgencyAccessService.conflict("This proposal is already closed.");
    UUID proposer = (UUID) p.get("requested_by"), targetId = (UUID) p.get("target_user_id");
    if (command.equals("withdraw")) {
      if (!a.id().equals(proposer))
        throw WorkAccessService.forbidden("Only the proposing operator can withdraw.");
      repository.withdraw(id, r.reason().trim(), clock.instant());
    } else {
      if (!Set.of("approve", "reject").contains(command))
        throw AgencyAccessService.bad("Unknown account action.");
      if (a.id().equals(proposer) || a.id().equals(targetId))
        throw WorkAccessService.forbidden(
            "A different independent operator must review this proposal.");
      var proposerAccount = accounts.account(proposer, false);
      if (!Boolean.TRUE.equals(proposerAccount.get("active"))
          || !"PLATFORM_ADMIN".equals(proposerAccount.get("platform_role")))
        throw AgencyAccessService.conflict("The proposing operator no longer has authority.");
      var target = accounts.account(targetId, true);
      if ("PLATFORM_ADMIN".equals(target.get("platform_role")))
        throw WorkAccessService.forbidden("Use the controlled platform-operator procedure.");
      if (command.equals("approve")) {
        AdministrationAccessService.version(
            target.get("security_version"), ((Number) p.get("target_version")).longValue());
        repository.active(targetId, "REACTIVATE".equals(p.get("action")), clock.instant());
      }
      repository.decide(
          id,
          a.id(),
          command.equals("approve") ? "APPROVED" : "REJECTED",
          r.reason().trim(),
          clock.instant());
    }
    activity.record(a, r.requestId(), action, "USER_ACTION", id, r, r.reason().trim(), false);
    return action(a, token, id);
  }
}
