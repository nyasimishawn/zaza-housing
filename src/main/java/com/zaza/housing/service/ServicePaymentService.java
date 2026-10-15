package com.zaza.housing.service;

import com.zaza.housing.dto.OpenServiceAccountRequest;
import com.zaza.housing.dto.RecordPaymentRequest;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.ReportPaymentRequest;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceFinanceCommandRequest;
import com.zaza.housing.dto.VerifyPaymentRequest;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.ServicePaymentRepository;
import com.zaza.housing.repository.WorkVisitRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicePaymentService {
  private final WorkAccessService workAccess;
  private final WorkVisitRepository visits;
  private final ServicePaymentRepository payments;
  private final ServiceFinanceAccessService access;
  private final ServiceFinanceActivityService activity;
  private final ServiceFinanceReadService reads;
  private final AuthService auth;
  private final Clock clock;
  private final int commissionBps;

  public ServicePaymentService(
      WorkAccessService workAccess,
      WorkVisitRepository visits,
      ServicePaymentRepository payments,
      ServiceFinanceAccessService access,
      ServiceFinanceActivityService activity,
      ServiceFinanceReadService reads,
      AuthService auth,
      Clock clock,
      @Value("${zaza.services.commission-bps:0}") int commissionBps) {
    this.workAccess = workAccess;
    this.visits = visits;
    this.payments = payments;
    this.access = access;
    this.activity = activity;
    this.reads = reads;
    this.auth = auth;
    this.clock = clock;
    if (commissionBps < 0 || commissionBps > 5000)
      throw new IllegalArgumentException(
          "Service commission must be between 0 and 5000 basis points.");
    this.commissionBps = commissionBps;
  }

  @Transactional
  public ServiceAccountResponse open(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID work,
      OpenServiceAccountRequest r) {
    var a = workAccess.get(actor, party, org, property, work, true);
    workAccess.payer(actor, a);
    var replay = activity.replay(actor, work, r.requestId(), "ACCOUNT_OPENED", r);
    if (replay.isPresent()) return reads.current(actor, replay.get());
    if (a.work().version() != r.workVersion())
      throw WorkAccessService.conflict("The work request changed. Reload first.");
    if (a.work().quoteId() == null || a.work().providerId() == null)
      throw WorkAccessService.conflict("An external provider and accepted quote are required.");
    WorkAccessService.state(
        a.work(),
        WorkStatus.ACCEPTED,
        WorkStatus.SCHEDULED,
        WorkStatus.IN_PROGRESS,
        WorkStatus.AWAITING_CONFIRMATION,
        WorkStatus.AWAITING_APPROVAL,
        WorkStatus.COMPLETED);
    var quote = visits.quote(work, a.work().quoteId()).orElseThrow();
    if (!quote.status().equals("ACCEPTED"))
      throw WorkAccessService.conflict("The paying party must accept this quote first.");
    if (payments.quote(quote.id()).isPresent())
      throw WorkAccessService.conflict(
          "This quote already has a financial account. Use its existing account or agree a new"
              + " quote.");
    UUID id = UUID.randomUUID();
    BigDecimal fee =
        quote
            .amount()
            .multiply(BigDecimal.valueOf(commissionBps))
            .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
    payments.create(id, a.work(), quote.amount(), quote.currency(), commissionBps, fee);
    activity.operation(actor, work, r.requestId(), "ACCOUNT_OPENED", r, id);
    // Opening has a work-scoped idempotency key; subsequent commands use the financial account's
    // own version.
    activity.finish(
        actor,
        payments.find(id).orElseThrow(),
        r.requestId(),
        "ACCOUNT_OPENED",
        r,
        id,
        quote.amount());
    return reads.current(actor, id);
  }

  public void date(LocalDate date) {
    if (date.isAfter(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)))
      throw WorkAccessService.invalid("External transfer dates cannot be in the future.");
  }

  public String reference(String value) {
    return value.trim().toUpperCase(Locale.ROOT);
  }

  @Transactional
  public ServiceAccountResponse report(AuthenticatedUser actor, UUID id, ReportPaymentRequest r) {
    var m = activity.begin(actor, id, r.requestId(), "PAYMENT_REPORTED", r, r.version());
    access.payer(m.access());
    if (m.replay()) return reads.current(actor, id);
    date(r.receivedOn());
    UUID claim = payments.claim(id, actor.id(), r, reference(r.reference()));
    activity.finish(
        actor, m.access().account(), r.requestId(), "PAYMENT_REPORTED", r, claim, r.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse record(AuthenticatedUser actor, UUID id, RecordPaymentRequest r) {
    var m = activity.begin(actor, id, r.requestId(), "PAYMENT_VERIFIED", r, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    date(r.receivedOn());
    UUID receipt =
        payments.receipt(
            id,
            null,
            actor.id(),
            r.amount(),
            r.channel(),
            reference(r.reference()),
            r.receivedOn(),
            r.reason());
    activity.finish(
        actor, m.access().account(), r.requestId(), "PAYMENT_VERIFIED", r, receipt, r.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse decide(
      AuthenticatedUser actor, UUID id, UUID claim, VerifyPaymentRequest r, boolean accept) {
    var body = Map.of("claimId", claim, "request", r);
    String action = accept ? "CLAIM_VERIFIED" : "CLAIM_REJECTED";
    var m = activity.begin(actor, id, r.requestId(), action, body, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    var c =
        payments
            .claim(id, claim)
            .orElseThrow(() -> WorkAccessService.missing("Payment claim not found."));
    if (!c.status().equals("PENDING"))
      throw WorkAccessService.conflict("This claim was already reviewed.");
    if (actor.id().equals(payments.claimReporter(claim)))
      throw WorkAccessService.forbidden("The claim reporter cannot verify their own claim.");
    if (accept) {
      date(c.receivedOn());
      payments.receipt(
          id,
          claim,
          actor.id(),
          c.amount(),
          c.channel(),
          c.reference(),
          c.receivedOn(),
          r.reason());
    }
    payments.decideClaim(claim, actor.id(), accept, r.reason());
    activity.finish(
        actor,
        m.access().account(),
        r.requestId(),
        action,
        body,
        claim,
        accept ? c.amount() : null);
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse refund(
      AuthenticatedUser actor, UUID id, UUID receipt, RecordRefundRequest r) {
    var body = Map.of("receiptId", receipt, "request", r);
    var m = activity.begin(actor, id, r.requestId(), "REFUND_RECORDED", body, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    var p =
        payments
            .receipt(id, receipt)
            .orElseThrow(() -> WorkAccessService.missing("Receipt not found."));
    date(r.receivedOn());
    if (r.receivedOn().isBefore(p.receivedOn()))
      throw WorkAccessService.invalid("Refund date cannot precede its receipt.");
    if (p.voided()
        || p.amount().subtract(p.refunded()).compareTo(r.amount()) < 0
        || reads.refundAvailable(m.access().account(), payments.balance(id)).compareTo(r.amount())
            < 0)
      throw WorkAccessService.conflict(
          "The refund exceeds uncommitted funds available on this receipt and account.");
    UUID refund = payments.refund(id, receipt, actor.id(), r, reference(r.reference()));
    activity.finish(
        actor, m.access().account(), r.requestId(), "REFUND_RECORDED", body, refund, r.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse voidReceipt(
      AuthenticatedUser actor, UUID id, UUID receipt, VerifyPaymentRequest r) {
    var body = Map.of("receiptId", receipt, "request", r);
    var m = activity.begin(actor, id, r.requestId(), "RECEIPT_VOIDED", body, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    var p =
        payments
            .receipt(id, receipt)
            .orElseThrow(() -> WorkAccessService.missing("Receipt not found."));
    if (p.voided()
        || p.refunded().signum() > 0
        || reads.refundAvailable(m.access().account(), payments.balance(id)).compareTo(p.amount())
            < 0)
      throw WorkAccessService.conflict("Only an unused, unrefunded receipt can be voided.");
    payments.voidReceipt(receipt, actor.id(), r.reason());
    activity.finish(
        actor, m.access().account(), r.requestId(), "RECEIPT_VOIDED", body, receipt, p.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse close(
      AuthenticatedUser actor, UUID id, ServiceFinanceCommandRequest r) {
    var m = activity.begin(actor, id, r.requestId(), "ACCOUNT_CLOSED", r, r.version());
    access.payer(m.access());
    if (m.replay()) return reads.current(actor, id);
    var b = payments.balance(id);
    if (payments.pendingClaims(id)
        || b.received().subtract(b.refunded()).signum() != 0
        || b.reserved().signum() > 0
        || b.paid().signum() > 0)
      throw WorkAccessService.conflict(
          "Review pending payment claims, refund all uncommitted funds and cancel pending payouts"
              + " before closing this account.");
    payments.close(id);
    activity.finish(actor, m.access().account(), r.requestId(), "ACCOUNT_CLOSED", r, id, null);
    return reads.current(actor, id);
  }
}
