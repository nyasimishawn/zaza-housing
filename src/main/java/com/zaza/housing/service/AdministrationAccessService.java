package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AccountSecurityRepository;
import com.zaza.housing.repository.AdministrationRepository;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdministrationAccessService {
  private final AccountSecurityService security;
  private final AccountSecurityRepository accounts;
  private final AdministrationRepository repository;
  private final AuthService auth;

  public AdministrationAccessService(
      AccountSecurityService security,
      AccountSecurityRepository accounts,
      AdministrationRepository repository,
      AuthService auth) {
    this.security = security;
    this.accounts = accounts;
    this.repository = repository;
    this.auth = auth;
  }

  public void admin(AuthenticatedUser a, String token, boolean lock) {
    if (lock) accounts.account(a.id(), true);
    security.requireAdmin(a, token);
  }

  public void password(AuthenticatedUser a, String password) {
    auth.reauthenticate(a, password);
  }

  public Map<String, Object> listing(AuthenticatedUser a, UUID id, boolean lock) {
    var p = repository.listing(id, false);
    if (p == null) throw CaretakerAccessService.missing();
    UUID owner = (UUID) p.get("owner_organization_id"),
        manager = (UUID) p.get("manager_organization_id");
    if (lock) {
      repository.lockOrganizations(owner, manager);
      p = repository.listing(id, true);
      if (p == null) throw CaretakerAccessService.missing();
      owner = (UUID) p.get("owner_organization_id");
      manager = (UUID) p.get("manager_organization_id");
    }
    if (repository.related(a.id(), owner, manager))
      throw WorkAccessService.forbidden("An independent operator must handle this property.");
    return p;
  }

  public static void version(Object actual, long expected) {
    if (((Number) actual).longValue() != expected)
      throw AgencyAccessService.conflict(
          "This record changed. Reload and review the current version.");
  }
}
