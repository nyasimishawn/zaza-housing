package com.zaza.housing.service;

import com.zaza.housing.dto.SupportCaseResponse;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SupportAccessService {
  private final SupportRepository repository;
  private final AccountSecurityRepository accounts;
  private final AdministrationAccessService administration;
  private final Clock clock;

  public SupportAccessService(
      SupportRepository repository,
      AccountSecurityRepository accounts,
      AdministrationAccessService administration,
      Clock clock) {
    this.repository = repository;
    this.accounts = accounts;
    this.administration = administration;
    this.clock = clock;
  }

  public void actor(AuthenticatedUser a, String token, boolean admin, boolean lock) {
    if (admin) {
      administration.admin(a, token, lock);
      return;
    }
    var account = accounts.account(a.id(), lock);
    if (!Boolean.TRUE.equals(account.get("active"))
        || !accounts.validSession(a.id(), token, clock.instant()))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in again.");
  }

  public SupportCaseResponse get(
      AuthenticatedUser a, String token, UUID id, boolean admin, boolean lock) {
    actor(a, token, admin, lock);
    var value = repository.get(id, false);
    if (value == null) throw CaretakerAccessService.missing();
    if (admin && value.listingId() != null) administration.listing(a, value.listingId(), lock);
    if (lock) value = repository.get(id, true);
    if (!admin && !a.id().equals(value.reporterId())) throw CaretakerAccessService.missing();
    return value;
  }

  public void assigned(AuthenticatedUser a, SupportCaseResponse c) {
    if (a.id().equals(c.reporterId()) || !a.id().equals(c.assignedTo()))
      throw WorkAccessService.forbidden("An assigned independent operator must handle this case.");
  }

  public boolean independent(AuthenticatedUser a, SupportCaseResponse c) {
    if (a.id().equals(c.reporterId())) return false;
    try {
      if (c.listingId() != null) administration.listing(a, c.listingId(), false);
      return true;
    } catch (ApiException denied) {
      return false;
    }
  }

  public SupportCaseResponse redact(AuthenticatedUser a, SupportCaseResponse c, boolean admin) {
    if (!admin || (a.id().equals(c.assignedTo()) && independent(a, c))) return c;
    return new SupportCaseResponse(
        c.id(),
        null,
        c.kind(),
        c.category(),
        null,
        null,
        null,
        c.status(),
        c.priority(),
        c.assignedTo(),
        c.version(),
        c.responseDueAt(),
        c.resolutionDueAt(),
        null,
        null,
        null,
        null,
        c.createdAt(),
        c.updatedAt(),
        true);
  }

  public void open(SupportCaseResponse c) {
    if (java.util.Set.of("RESOLVED", "CLOSED").contains(c.status()))
      throw AgencyAccessService.conflict(
          "This case is closed to new work. Reopen a resolved case before adding evidence or"
              + " replies.");
  }
}
