package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyReportResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyReportRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgencyReportService {
  private final AgencyAccessService access;
  private final AgencyReportRepository repository;
  private final Clock clock;

  public AgencyReportService(
      AgencyAccessService access, AgencyReportRepository repository, Clock clock) {
    this.access = access;
    this.repository = repository;
    this.clock = clock;
  }

  public AgencyReportResponse get(
      AuthenticatedUser a, UUID org, LocalDate from, LocalDate to, int limit, int offset) {
    var o = access.get(a, org, false, false);
    Pagination.validate(limit, offset);
    if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366)
      throw AgencyAccessService.bad("Choose a date range of at most 367 days.");
    return repository.report(
        org, a.id(), access.manages(o), from, to, clock.instant(), limit, offset);
  }
}
