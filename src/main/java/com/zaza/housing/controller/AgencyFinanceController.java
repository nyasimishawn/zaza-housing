package com.zaza.housing.controller;

import com.zaza.housing.dto.AgencyCommissionCommand;
import com.zaza.housing.dto.AgencyCommissionRequest;
import com.zaza.housing.dto.AgencyReportResponse;
import com.zaza.housing.model.AgencyCommission;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.AgencyCommissionService;
import com.zaza.housing.service.AgencyReportService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{org}/agency")
public class AgencyFinanceController {
  private final AgencyCommissionService commissions;
  private final AgencyReportService reports;

  public AgencyFinanceController(AgencyCommissionService commissions, AgencyReportService reports) {
    this.commissions = commissions;
    this.reports = reports;
  }

  @GetMapping("/commissions")
  public List<AgencyCommission> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return commissions.list(a, org, status, limit, offset);
  }

  @GetMapping("/commissions/{id}")
  public AgencyCommission get(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return commissions.get(a, org, id);
  }

  @GetMapping("/commissions/{id}/history")
  public List<AgencyEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return commissions.history(a, org, id, limit, offset);
  }

  @PostMapping("/commissions")
  public AgencyCommission create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyCommissionRequest r) {
    return commissions.create(a, org, r);
  }

  @PostMapping("/commissions/{id}/{command}")
  public AgencyCommission command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AgencyCommissionCommand r) {
    return commissions.command(a, org, id, command, r);
  }

  @GetMapping("/reports")
  public AgencyReportResponse report(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reports.get(a, org, from, to, limit, offset);
  }
}
