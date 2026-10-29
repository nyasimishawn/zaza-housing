package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AdministrationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationDashboardService {
  private final AdministrationRepository repository;
  private final AdministrationAccessService access;
  private final AdministrationActivityService activity;
  private final Clock clock;

  public AdministrationDashboardService(
      AdministrationRepository repository,
      AdministrationAccessService access,
      AdministrationActivityService activity,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.activity = activity;
    this.clock = clock;
  }

  public AdministrationDashboardResponse dashboard(AuthenticatedUser a, String token) {
    access.admin(a, token, false);
    return new AdministrationDashboardResponse(repository.counts(), clock.instant());
  }

  public List<Map<String, Object>> audit(
      AuthenticatedUser a, String token, String action, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    if (action != null && !action.matches("[A-Z0-9_]{1,80}"))
      throw AgencyAccessService.bad("Use an exact audit action.");
    return repository.audit(action, l, f);
  }

  public List<Map<String, Object>> history(
      AuthenticatedUser a, String token, String kind, UUID id, int l, int f) {
    access.admin(a, token, false);
    if (!Set.of("USER_ACTION", "LISTING", "SETTING", "KNOWLEDGE").contains(kind))
      throw AgencyAccessService.bad("Use the scoped case workflow for private case history.");
    Pagination.validate(l, f);
    return repository.history(kind, id, true, l, f);
  }

  public List<Map<String, Object>> settings(AuthenticatedUser a, String token) {
    access.admin(a, token, false);
    return repository.settings();
  }

  @Transactional
  public List<Map<String, Object>> change(
      AuthenticatedUser a, String token, String key, OperationalSettingRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    repository.lockSettings();
    var current = repository.setting(key, true);
    if (current == null) throw CaretakerAccessService.missing();
    UUID target = UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (activity.replay(a, r.requestId(), "OPERATIONAL_SETTING_CHANGED", target, r) != null)
      return repository.settings();
    AdministrationAccessService.version(current.get("version"), r.version());
    int min = key.equals("support.firstResponseHours") ? 1 : 24,
        max = key.equals("support.firstResponseHours") ? 72 : 720;
    if (r.value() < min || r.value() > max)
      throw AgencyAccessService.bad("This setting is outside its allowed range.");
    int
        first =
            key.equals("support.firstResponseHours")
                ? r.value()
                : repository.settingValue("support.firstResponseHours"),
        resolution =
            key.equals("support.resolutionHours")
                ? r.value()
                : repository.settingValue("support.resolutionHours");
    if (first > resolution)
      throw AgencyAccessService.bad("Resolution target must follow the first-response target.");
    repository.setting(key, r.value(), a.id(), clock.instant());
    activity.record(
        a,
        r.requestId(),
        "OPERATIONAL_SETTING_CHANGED",
        "SETTING",
        target,
        r,
        r.reason().trim(),
        false);
    return repository.settings();
  }
}
