package com.zaza.housing.service;

import com.zaza.housing.dto.AddChargeRequest;
import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingCommandRequest;
import com.zaza.housing.dto.BillingPreviewResponse;
import com.zaza.housing.dto.CreditInvoiceRequest;
import com.zaza.housing.dto.OpenBillingRequest;
import com.zaza.housing.dto.RentPeriodResponse;
import com.zaza.housing.enums.BillingPolicy;
import com.zaza.housing.enums.InvoiceKind;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.RentalLease;
import com.zaza.housing.repository.BillingRepository;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.util.RentSchedule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
  private final BillingAccessService access;
  private final BillingRepository billing;
  private final LeaseRepository leases;
  private final LeaseService leaseService;
  private final BillingActivityService activity;
  private final Clock clock;

  public BillingService(
      BillingAccessService access,
      BillingRepository billing,
      LeaseRepository leases,
      LeaseService leaseService,
      BillingActivityService activity,
      Clock clock) {
    this.access = access;
    this.billing = billing;
    this.leases = leases;
    this.leaseService = leaseService;
    this.activity = activity;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public BillingPreviewResponse preview(
      AuthenticatedUser actor, UUID organization, UUID property, UUID id) {
    var lease = access.lease(actor, organization, property, id, false);
    return preview(lease);
  }

  private BillingPreviewResponse preview(RentalLease lease) {
    LeaseAccessService.state(
        lease,
        LeaseStatus.SIGNED,
        LeaseStatus.ACTIVE,
        LeaseStatus.COMPLETED,
        LeaseStatus.TERMINATED);
    var revision = leases.revision(lease.id(), lease.currentRevisionId());
    var terms = revision.terms();
    if (Currency.getInstance(terms.currency()).getDefaultFractionDigits() != 2)
      throw BillingAccessService.invalid(
          "Billing currently supports ISO currencies with two fractional digits.");
    var end = leaseService.effectiveEnd(lease, revision);
    try {
      var rent = RentSchedule.calendar(terms, end);
      return new BillingPreviewResponse(
          lease.id(),
          revision.id(),
          terms.currency(),
          BillingPolicy.CALENDAR_ACTUAL_DAYS,
          end,
          terms.deposit(),
          rent.stream().map(RentPeriodResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
          rent);
    } catch (IllegalArgumentException e) {
      throw BillingAccessService.invalid(e.getMessage());
    }
  }

  @Transactional
  public BillingAccountResponse open(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      OpenBillingRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    if (billing.find(id).isPresent()) {
      if (activity.replay(actor, id, request.requestId(), "OPENED", request, -1))
        return summary(id);
      throw BillingAccessService.conflict("Billing is already configured.");
    }
    LeaseAccessService.version(lease, request.leaseVersion());
    if (lease.unitReleasedAt() != null)
      throw BillingAccessService.conflict("Configure billing before releasing the unit.");
    var preview = preview(lease);
    var terms = leases.revision(id, lease.currentRevisionId()).terms();
    billing.open(id, preview.revisionId(), preview.currency(), request.policy());
    for (var row : preview.rent())
      if (row.amount().signum() > 0)
        billing.invoice(
            id,
            InvoiceKind.RENT,
            "Rent " + row.periodStart() + " to " + row.periodEnd() + " (end excluded)",
            row.dueDate(),
            row.periodStart(),
            row.periodEnd(),
            row.amount());
    if (preview.deposit().signum() > 0)
      billing.invoice(
          id,
          InvoiceKind.DEPOSIT,
          "Refundable security deposit",
          terms.startDate(),
          null,
          null,
          preview.deposit());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "OPENED",
        request,
        id,
        "Lease billing opened using calendar-month actual-day proration.",
        true);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse charge(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      AddChargeRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    if (activity.replay(actor, id, request.requestId(), "CHARGE_ADDED", request, request.version()))
      return summary(id);
    if (!access.account(id).status().equals("OPEN"))
      throw BillingAccessService.conflict("Add charges before finalizing checkout billing.");
    var entity =
        billing.invoice(
            id,
            InvoiceKind.CHARGE,
            request.reason().trim(),
            request.dueDate(),
            null,
            null,
            request.amount());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "CHARGE_ADDED",
        request,
        entity,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse credit(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID invoiceId,
      CreditInvoiceRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    String action = "INVOICE_CREDITED";
    // Include the target in the fingerprint so a key cannot credit a different invoice.
    var body = java.util.Map.of("invoiceId", invoiceId, "request", request);
    if (activity.replay(actor, id, request.requestId(), action, body, request.version()))
      return summary(id);
    var invoice =
        billing
            .invoice(id, invoiceId, today())
            .orElseThrow(() -> BillingAccessService.missing("Invoice not found."));
    if (invoice.kind() == InvoiceKind.DEPOSIT)
      throw BillingAccessService.conflict(
          "Unpaid deposit is released only when billing is finalized.");
    if (request.amount().compareTo(invoice.outstanding()) > 0)
      throw BillingAccessService.conflict(
          "Credit cannot exceed the unpaid invoice balance. Reverse allocations first.");
    billing.credit(id, invoiceId, request.amount(), request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        action,
        body,
        invoiceId,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse finalizeBilling(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      BillingCommandRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    if (activity.replay(actor, id, request.requestId(), "FINALIZED", request, request.version()))
      return summary(id);
    LeaseAccessService.state(lease, LeaseStatus.COMPLETED, LeaseStatus.TERMINATED);
    if (!access.account(id).status().equals("OPEN"))
      throw BillingAccessService.conflict("Billing is already finalized.");
    var revision = leases.revision(id, lease.currentRevisionId());
    var end = leaseService.effectiveEnd(lease, revision);
    for (var invoice : billing.scheduledInvoices(id, today())) {
      if (invoice.kind() == InvoiceKind.DEPOSIT && invoice.outstanding().signum() > 0)
        billing.credit(
            id,
            invoice.id(),
            invoice.outstanding(),
            "Unpaid deposit obligation released at checkout.");
      if (invoice.kind() != InvoiceKind.RENT) continue;
      LocalDate until = invoice.periodEnd().isBefore(end) ? invoice.periodEnd() : end;
      BigDecimal target =
          RentSchedule.amount(revision.terms().monthlyRent(), invoice.periodStart(), until);
      BigDecimal required = invoice.amount().subtract(target).subtract(invoice.credited());
      if (required.signum() <= 0) continue;
      BigDecimal excess =
          invoice.paid().subtract(invoice.amount().subtract(invoice.credited()).subtract(required));
      for (var allocation : billing.invoiceAllocations(id, invoice.id())) {
        if (excess.signum() <= 0) break;
        billing.reverse(allocation.id(), "Checkout rent proration adjustment.");
        BigDecimal removed = allocation.amount().min(excess),
            remaining = allocation.amount().subtract(removed);
        if (remaining.signum() > 0)
          billing.allocate(id, allocation.paymentId(), invoice.id(), remaining);
        excess = excess.subtract(removed);
      }
      billing.credit(
          id,
          invoice.id(),
          required,
          "Rent after agreed end date credited using calendar actual days.");
    }
    billing.finalizeAccount(id);
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "FINALIZED",
        request,
        id,
        request.reason().trim(),
        false);
    return summary(id);
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private BillingAccountResponse summary(UUID id) {
    return billing.summary(id, today());
  }
}
