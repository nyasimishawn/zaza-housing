package com.zaza.housing.service;

import com.zaza.housing.dto.AllocatePaymentRequest;
import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingCommandRequest;
import com.zaza.housing.dto.PaymentResponse;
import com.zaza.housing.dto.RecordPaymentRequest;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.ReportPaymentRequest;
import com.zaza.housing.dto.VerifyPaymentRequest;
import com.zaza.housing.enums.InvoiceKind;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.BillingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final BillingAccessService access;
  private final BillingRepository billing;
  private final BillingActivityService activity;
  private final AuthService auth;
  private final Clock clock;

  public PaymentService(
      BillingAccessService access,
      BillingRepository billing,
      BillingActivityService activity,
      AuthService auth,
      Clock clock) {
    this.access = access;
    this.billing = billing;
    this.activity = activity;
    this.auth = auth;
    this.clock = clock;
  }

  @Transactional
  public BillingAccountResponse report(
      AuthenticatedUser actor, UUID id, ReportPaymentRequest request) {
    var lease = access.lease(actor, null, null, id, true);
    access.account(id);
    if (activity.replay(
        actor, id, request.requestId(), "PAYMENT_REPORTED", request, request.version()))
      return summary(id);
    if (lease.unitReleasedAt() != null)
      throw BillingAccessService.conflict(
          "This checkout is closed; contact the owner to reconcile further payments.");
    date(request.receivedOn());
    UUID claim = billing.claim(id, actor.id(), request, reference(request.reference()));
    activity.record(
        actor,
        null,
        lease,
        request.requestId(),
        "PAYMENT_REPORTED",
        request,
        claim,
        "A payment was reported and awaits independent owner verification.",
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse verify(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID claimId,
      VerifyPaymentRequest request,
      boolean accepted) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    auth.reauthenticate(actor, request.password());
    String action = accepted ? "PAYMENT_VERIFIED" : "PAYMENT_REJECTED";
    var body = Map.of("claimId", claimId, "request", request);
    if (activity.replay(actor, id, request.requestId(), action, body, request.version()))
      return summary(id);
    var claim =
        billing
            .claim(id, claimId)
            .orElseThrow(() -> BillingAccessService.missing("Payment report not found."));
    if (!claim.status().equals("PENDING"))
      throw BillingAccessService.conflict("This report has already been resolved.");
    billing.decideClaim(claimId, actor.id(), accepted, request.reason().trim());
    UUID entity = claimId;
    if (accepted) {
      billing.reference(lease.ownerOrganizationId(), claim.channel(), claim.reference());
      entity =
          billing.receipt(
              id,
              claimId,
              actor.id(),
              claim.channel(),
              claim.reference(),
              claim.receivedOn(),
              claim.amount(),
              request.reason().trim());
    }
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        action,
        body,
        entity,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse record(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      RecordPaymentRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    auth.reauthenticate(actor, request.password());
    if (activity.replay(
        actor, id, request.requestId(), "PAYMENT_RECORDED", request, request.version()))
      return summary(id);
    if (lease.unitReleasedAt() != null)
      throw BillingAccessService.conflict("This checkout is closed; receipts cannot be added.");
    date(request.receivedOn());
    String ref = reference(request.reference());
    billing.reference(lease.ownerOrganizationId(), request.channel(), ref);
    UUID payment =
        billing.receipt(
            id,
            null,
            actor.id(),
            request.channel(),
            ref,
            request.receivedOn(),
            request.amount(),
            request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "PAYMENT_RECORDED",
        request,
        payment,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse allocate(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID paymentId,
      AllocatePaymentRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    var body = Map.of("paymentId", paymentId, "request", request);
    if (activity.replay(
        actor, id, request.requestId(), "PAYMENT_ALLOCATED", body, request.version()))
      return summary(id);
    var payment = payment(id, paymentId);
    var invoice =
        billing
            .invoice(id, request.invoiceId(), today())
            .orElseThrow(() -> BillingAccessService.missing("Invoice not found."));
    if (payment.voided()
        || request.amount().compareTo(payment.available()) > 0
        || request.amount().compareTo(invoice.outstanding()) > 0)
      throw BillingAccessService.conflict(
          "Allocation exceeds available payment funds or the invoice balance.");
    if (invoice.kind() == InvoiceKind.DEPOSIT && !access.account(id).status().equals("OPEN"))
      throw BillingAccessService.conflict(
          "Deposit collection is closed after billing finalization.");
    UUID allocation = billing.allocate(id, paymentId, invoice.id(), request.amount());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "PAYMENT_ALLOCATED",
        body,
        allocation,
        "Verified funds allocated to an invoice.",
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse reverse(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID allocationId,
      BillingCommandRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    var body = Map.of("allocationId", allocationId, "request", request);
    if (activity.replay(
        actor, id, request.requestId(), "ALLOCATION_REVERSED", body, request.version()))
      return summary(id);
    var allocation =
        billing
            .allocation(id, allocationId)
            .orElseThrow(() -> BillingAccessService.missing("Allocation not found."));
    if (allocation.reversed())
      throw BillingAccessService.conflict("This allocation is already reversed.");
    var invoice = billing.invoice(id, allocation.invoiceId(), today()).orElseThrow();
    if (invoice.kind() == InvoiceKind.DEPOSIT && !access.account(id).status().equals("OPEN"))
      throw BillingAccessService.conflict(
          "Deposit allocations are frozen at checkout finalization.");
    if (lease.unitReleasedAt() != null)
      throw BillingAccessService.conflict("A closed checkout cannot reopen invoice debt.");
    billing.reverse(allocationId, request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "ALLOCATION_REVERSED",
        body,
        allocationId,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse voidPayment(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID paymentId,
      VerifyPaymentRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("paymentId", paymentId, "request", request);
    if (activity.replay(actor, id, request.requestId(), "PAYMENT_VOIDED", body, request.version()))
      return summary(id);
    var payment = payment(id, paymentId);
    if (payment.voided() || payment.allocated().signum() != 0 || payment.refunded().signum() != 0)
      throw BillingAccessService.conflict(
          "Only an unused, unrefunded receipt can be voided. Reverse allocations first.");
    billing.voidPayment(paymentId, request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "PAYMENT_VOIDED",
        body,
        paymentId,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse refund(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID paymentId,
      RecordRefundRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("paymentId", paymentId, "request", request);
    if (activity.replay(
        actor, id, request.requestId(), "PAYMENT_REFUNDED", body, request.version()))
      return summary(id);
    var payment = payment(id, paymentId);
    date(request.receivedOn());
    if (request.receivedOn().isBefore(payment.receivedOn()))
      throw BillingAccessService.invalid("A refund cannot predate the original payment.");
    if (payment.voided() || request.amount().compareTo(payment.available()) > 0)
      throw BillingAccessService.conflict("Refund cannot exceed unallocated verified funds.");
    String ref = reference(request.reference());
    billing.reference(lease.ownerOrganizationId(), request.channel(), ref);
    UUID refund = billing.refund(id, paymentId, false, request, ref);
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "PAYMENT_REFUNDED",
        body,
        refund,
        request.reason().trim(),
        false);
    return summary(id);
  }

  private PaymentResponse payment(UUID lease, UUID id) {
    return billing
        .payment(lease, id)
        .orElseThrow(() -> BillingAccessService.missing("Payment receipt not found."));
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private BillingAccountResponse summary(UUID id) {
    return billing.summary(id, today());
  }

  public static String reference(String reference) {
    return reference.toUpperCase(Locale.ROOT);
  }

  private void date(LocalDate date) {
    if (date.isAfter(today()) || date.isBefore(LocalDate.of(1900, 1, 1)))
      throw BillingAccessService.invalid("Use an actual received date from 1900 through today.");
  }
}
