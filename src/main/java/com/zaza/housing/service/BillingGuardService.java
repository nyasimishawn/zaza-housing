package com.zaza.housing.service;

import com.zaza.housing.repository.BillingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BillingGuardService {
  private final BillingRepository billing;
  private final Clock clock;

  public BillingGuardService(BillingRepository billing, Clock clock) {
    this.billing = billing;
    this.clock = clock;
  }

  public void activation(UUID lease) {
    if (billing.find(lease).isEmpty()) return;
    if (billing.moveInOutstanding(lease, LocalDate.now(clock)))
      throw BillingAccessService.conflict(
          "Verify and allocate the deposit and rent due through today before move-in.");
  }

  public void checkout(UUID lease) {
    if (billing.find(lease).isEmpty()) return;
    if (!billing.summary(lease, LocalDate.now(clock)).checkoutReady())
      throw BillingAccessService.conflict(
          "Finalize billing, resolve payment reports, clear invoices, and reconcile/refund all"
              + " remaining funds before releasing the unit.");
  }
}
