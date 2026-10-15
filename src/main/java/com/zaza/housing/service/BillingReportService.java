package com.zaza.housing.service;

import com.zaza.housing.dto.BillingCurrencySummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.BillingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingReportService {
  private final BillingRepository billing;
  private final PropertyService properties;
  private final Clock clock;

  public BillingReportService(BillingRepository billing, PropertyService properties, Clock clock) {
    this.billing = billing;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<BillingCurrencySummaryResponse> summary(
      AuthenticatedUser actor, UUID organization, UUID property) {
    if (organization != null) properties.getForManagement(actor, organization, property);
    return billing.report(
        organization == null ? actor.id() : property, organization == null, LocalDate.now(clock));
  }
}
