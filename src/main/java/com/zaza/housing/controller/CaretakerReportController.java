package com.zaza.housing.controller;

import com.zaza.housing.dto.CaretakerReportResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.CaretakerReportService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{org}/caretaker/reports")
public class CaretakerReportController {
  private final CaretakerReportService reports;

  public CaretakerReportController(CaretakerReportService reports) {
    this.reports = reports;
  }

  @GetMapping
  public CaretakerReportResponse report(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    return reports.report(a, org, propertyId, from, to);
  }
}
