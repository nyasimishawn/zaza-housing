package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/operations")
public class AdministrationController {
  private final AdministrationDashboardService dashboard;
  private final AdministrationUserService users;
  private final ListingModerationService listings;

  public AdministrationController(
      AdministrationDashboardService dashboard,
      AdministrationUserService users,
      ListingModerationService listings) {
    this.dashboard = dashboard;
    this.users = users;
    this.listings = listings;
  }

  private String token(String h) {
    return AccountSecurityService.hash(h.substring(7));
  }

  @GetMapping("/dashboard")
  public AdministrationDashboardResponse dashboard(
      @AuthenticationPrincipal AuthenticatedUser a, @RequestHeader("Authorization") String h) {
    return dashboard.dashboard(a, token(h));
  }

  @GetMapping("/users")
  public List<AdminUserResponse> users(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean active,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return users.users(a, token(h), q, active, limit, offset);
  }

  @GetMapping("/account-actions")
  public List<Map<String, Object>> actions(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return users.actions(a, token(h), status, limit, offset);
  }

  @PostMapping("/account-actions")
  public Map<String, Object> propose(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody AccountActionRequest r) {
    return users.propose(a, token(h), r);
  }

  @GetMapping("/account-actions/{id}")
  public Map<String, Object> action(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return users.action(a, token(h), id);
  }

  @PostMapping("/account-actions/{id}/{command}")
  public Map<String, Object> decide(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AdministrationCommandRequest r) {
    return users.decide(a, token(h), id, command, r);
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return dashboard.audit(a, token(h), action, limit, offset);
  }

  @GetMapping("/history/{kind}/{id}")
  public List<Map<String, Object>> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return dashboard.history(a, token(h), kind, id, limit, offset);
  }

  @GetMapping("/settings")
  public List<Map<String, Object>> settings(
      @AuthenticationPrincipal AuthenticatedUser a, @RequestHeader("Authorization") String h) {
    return dashboard.settings(a, token(h));
  }

  @PostMapping("/settings/{key}")
  public List<Map<String, Object>> setting(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable String key,
      @Valid @RequestBody OperationalSettingRequest r) {
    return dashboard.change(a, token(h), key, r);
  }

  @GetMapping("/listing-sources")
  public List<Map<String, Object>> sources(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return listings.sources(a, token(h), q, limit, offset);
  }

  @GetMapping("/listing-reviews")
  public List<Map<String, Object>> listings(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return listings.list(a, token(h), status, limit, offset);
  }

  @PostMapping("/listing-reviews")
  public Map<String, Object> request(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody ListingReviewRequest r) {
    return listings.request(a, token(h), r);
  }

  @GetMapping("/listing-reviews/{id}")
  public Map<String, Object> listing(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return listings.get(a, token(h), id);
  }

  @PostMapping("/listing-reviews/{id}/{command}")
  public Map<String, Object> listingCommand(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AdministrationCommandRequest r) {
    return listings.command(a, token(h), id, command, r);
  }
}
