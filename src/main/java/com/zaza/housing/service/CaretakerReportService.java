package com.zaza.housing.service;

import com.zaza.housing.dto.CaretakerReportResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.CaretakerReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CaretakerReportService {
  private final CaretakerAccessService access;
  private final CaretakerReportRepository reports;

  public CaretakerReportService(CaretakerAccessService access, CaretakerReportRepository reports) {
    this.access = access;
    this.reports = reports;
  }

  public CaretakerReportResponse report(
      AuthenticatedUser a, UUID o, UUID property, Instant from, Instant to) {
    var org = access.organization(a, o, false);
    if (from == null
        || to == null
        || !to.isAfter(from)
        || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0)
      throw AgencyAccessService.bad("Choose a positive reporting period of at most 366 days.");
    if (property != null) access.property(a, o, property, "OPERATIONS", false);
    return reports.report(o, a.id(), property, access.manager(org), from, to);
  }
}
