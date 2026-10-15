package com.zaza.housing.service;

import com.zaza.housing.dto.PayoutDestinationResponse;
import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.RecordPaymentRequest;
import com.zaza.housing.dto.RequestProviderPayoutRequest;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceFinanceCommandRequest;
import com.zaza.housing.dto.VerifyPaymentRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ServiceAccountBalance;
import com.zaza.housing.model.ServiceFinanceAccess;
import com.zaza.housing.model.ServicePaymentAccount;
import com.zaza.housing.repository.ProviderPayoutRepository;
import com.zaza.housing.repository.ServicePaymentRepository;
import com.zaza.housing.util.Pagination;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderPayoutService {
  private final ProviderPayoutRepository payouts;
  private final ServicePaymentRepository payments;
  private final ServiceFinanceAccessService access;
  private final ServiceFinanceActivityService activity;
  private final ServiceFinanceReadService reads;
  private final ServicePaymentService paymentService;
  private final AuthService auth;

  public ProviderPayoutService(
      ProviderPayoutRepository payouts,
      ServicePaymentRepository payments,
      ServiceFinanceAccessService access,
      ServiceFinanceActivityService activity,
      ServiceFinanceReadService reads,
      ServicePaymentService paymentService,
      AuthService auth) {
    this.payouts = payouts;
    this.payments = payments;
    this.access = access;
    this.activity = activity;
    this.reads = reads;
    this.paymentService = paymentService;
    this.auth = auth;
  }

  public List<ProviderPayoutResponse> queue(
      AuthenticatedUser actor, UUID provider, String status, int limit, int offset) {
    Pagination.validate(limit, offset);
    if (provider == null) access.operatorOnly(actor);
    else access.owner(actor, provider);
    if (status != null
        && !Set.of("REQUESTED", "APPROVED", "REJECTED", "CANCELLED", "FAILED", "PAID")
            .contains(status)) throw WorkAccessService.invalid("Unknown payout status.");
    return payouts.queue(provider, status, limit, offset);
  }

  private ProviderPayoutResponse find(UUID account, UUID id) {
    return payouts
        .find(account, id)
        .orElseThrow(() -> WorkAccessService.missing("Payout request not found."));
  }

  private void state(ProviderPayoutResponse p, String... allowed) {
    if (!Arrays.asList(allowed).contains(p.status()))
      throw WorkAccessService.conflict("This payout is " + p.status() + ".");
  }

  private void eligible(ServiceFinanceAccess a, boolean ownReservation) {
    var b = payments.balance(a.account().id());
    if (ownReservation)
      b =
          new ServiceAccountBalance(
              b.received(), b.refunded(), BigDecimal.ZERO, b.paid(), b.feesEarned());
    String blocker = reads.blocker(a.account(), a.work(), b);
    if (blocker != null)
      throw WorkAccessService.conflict("Payout is unavailable: " + blocker + ".");
  }

  private PayoutDestinationResponse destination(ServicePaymentAccount a, UUID id) {
    var d =
        payouts
            .destination(id)
            .orElseThrow(() -> WorkAccessService.missing("Payout destination not found."));
    if (!a.providerId().equals(d.providerId()))
      throw WorkAccessService.missing("Payout destination not found.");
    if (!d.status().equals("VERIFIED"))
      throw WorkAccessService.conflict(
          "The external beneficiary must be independently verified and remain active.");
    if (!a.currency().equals(d.currency()))
      throw WorkAccessService.conflict("Payout and beneficiary currencies must match.");
    return d;
  }

  @Transactional
  public ServiceAccountResponse request(
      AuthenticatedUser actor, UUID id, RequestProviderPayoutRequest r) {
    var m = activity.begin(actor, id, r.requestId(), "PAYOUT_REQUESTED", r, r.version());
    access.provider(actor, m.access());
    if (m.replay()) return reads.current(actor, id);
    eligible(m.access(), false);
    destination(m.access().account(), r.destinationId());
    BigDecimal amount = m.access().account().amount().subtract(m.access().account().fee());
    UUID payout = payouts.request(id, r.destinationId(), actor.id(), amount);
    activity.finish(
        actor, m.access().account(), r.requestId(), "PAYOUT_REQUESTED", r, payout, amount);
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse cancel(
      AuthenticatedUser actor, UUID id, UUID payout, ServiceFinanceCommandRequest r) {
    var body = Map.of("payoutId", payout, "request", r);
    var m = activity.begin(actor, id, r.requestId(), "PAYOUT_CANCELLED", body, r.version());
    access.provider(actor, m.access());
    if (m.replay()) return reads.current(actor, id);
    var p = find(id, payout);
    state(p, "REQUESTED");
    payouts.decision(payout, actor.id(), "CANCELLED", r.reason(), null, null, null);
    activity.finish(
        actor, m.access().account(), r.requestId(), "PAYOUT_CANCELLED", body, payout, p.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse review(
      AuthenticatedUser actor, UUID id, UUID payout, VerifyPaymentRequest r, String decision) {
    var body = Map.of("payoutId", payout, "request", r);
    String action = "PAYOUT_" + decision;
    var m = activity.begin(actor, id, r.requestId(), action, body, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    var p = find(id, payout);
    switch (decision) {
      case "APPROVED" -> {
        state(p, "REQUESTED");
        eligible(m.access(), true);
        destination(m.access().account(), p.destinationId());
      }
      case "REJECTED" -> state(p, "REQUESTED");
      case "FAILED", "CANCELLED" -> state(p, "APPROVED");
      default -> throw WorkAccessService.invalid("Unknown payout decision.");
    }
    payouts.decision(payout, actor.id(), decision, r.reason(), null, null, null);
    activity.finish(actor, m.access().account(), r.requestId(), action, body, payout, p.amount());
    return reads.current(actor, id);
  }

  @Transactional
  public ServiceAccountResponse paid(
      AuthenticatedUser actor, UUID id, UUID payout, RecordPaymentRequest r) {
    var body = Map.of("payoutId", payout, "request", r);
    var m = activity.begin(actor, id, r.requestId(), "PAYOUT_PAID", body, r.version());
    access.independentOperator(actor, m.access());
    auth.reauthenticate(actor, r.password());
    if (m.replay()) return reads.current(actor, id);
    var p = find(id, payout);
    state(p, "APPROVED");
    if (actor.id().equals(p.approvedBy()))
      throw WorkAccessService.forbidden(
          "A second independent operator must verify the completed external payout.");
    eligible(m.access(), true);
    var d = destination(m.access().account(), p.destinationId());
    if (p.amount().compareTo(r.amount()) != 0 || d.channel() != r.channel())
      throw WorkAccessService.conflict(
          "The independently verified payout amount and channel must match the approved"
              + " instruction.");
    paymentService.date(r.receivedOn());
    if (r.receivedOn()
            .isBefore(
                LocalDate.ofInstant(
                    m.access().work().completedAt().plus(Duration.ofDays(14)), ZoneOffset.UTC))
        || payments.latestReceiptDate(id).filter(date -> r.receivedOn().isBefore(date)).isPresent())
      throw WorkAccessService.invalid(
          "Payout date cannot precede the dispute-period deadline or verified funding.");
    String reference = paymentService.reference(r.reference());
    payments.reference(r.channel(), reference);
    payouts.decision(
        payout, actor.id(), "PAID", r.reason(), r.channel(), reference, r.receivedOn());
    activity.finish(
        actor, m.access().account(), r.requestId(), "PAYOUT_PAID", body, payout, p.amount());
    return reads.current(actor, id);
  }
}
