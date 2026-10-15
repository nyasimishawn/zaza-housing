package com.zaza.housing.service;

import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceClaimResponse;
import com.zaza.housing.dto.ServiceFinanceEventResponse;
import com.zaza.housing.dto.ServiceFinanceSummaryResponse;
import com.zaza.housing.dto.ServiceReceiptResponse;
import com.zaza.housing.dto.ServiceRefundResponse;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ServiceAccountBalance;
import com.zaza.housing.model.ServicePaymentAccount;
import com.zaza.housing.model.WorkRequest;
import com.zaza.housing.repository.ProviderPayoutRepository;
import com.zaza.housing.repository.ServicePaymentRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import com.zaza.housing.util.Pagination;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ServiceFinanceReadService {
  private final ServicePaymentRepository payments;
  private final ProviderPayoutRepository payouts;
  private final WorkRequestRepository work;
  private final ServiceFinanceAccessService access;
  private final ServiceProviderService providers;
  private final Clock clock;

  public ServiceFinanceReadService(
      ServicePaymentRepository payments,
      ProviderPayoutRepository payouts,
      WorkRequestRepository work,
      ServiceFinanceAccessService access,
      ServiceProviderService providers,
      Clock clock) {
    this.payments = payments;
    this.payouts = payouts;
    this.work = work;
    this.access = access;
    this.providers = providers;
    this.clock = clock;
  }

  public String blocker(ServicePaymentAccount a, WorkRequest w, ServiceAccountBalance b) {
    if (!a.status().equals("OPEN")) return "ACCOUNT_CLOSED";
    if (!a.assignmentId().equals(w.assignmentId()) || !a.quoteId().equals(w.quoteId()))
      return "ASSIGNMENT_CHANGED";
    if (w.status() == WorkStatus.DISPUTED) return "WORK_DISPUTED";
    if (w.status() != WorkStatus.COMPLETED || w.completedAt() == null) return "COMPLETION_REQUIRED";
    if (!clock.instant().isAfter(w.completedAt().plus(Duration.ofDays(14))))
      return "DISPUTE_PERIOD";
    if (b.received().subtract(b.refunded()).compareTo(a.amount()) < 0) return "PAYMENT_DUE";
    if (b.paid().signum() > 0) return "ALREADY_PAID";
    if (b.reserved().signum() > 0) return "PAYOUT_RESERVED";
    if (a.amount().subtract(a.fee()).signum() == 0) return "NO_PROVIDER_AMOUNT";
    return null;
  }

  public BigDecimal refundAvailable(ServicePaymentAccount a, ServiceAccountBalance b) {
    BigDecimal reservedFee = b.reserved().signum() > 0 ? a.fee() : BigDecimal.ZERO;
    return b.received()
        .subtract(b.refunded())
        .subtract(b.paid())
        .subtract(b.feesEarned())
        .subtract(b.reserved())
        .subtract(reservedFee)
        .max(BigDecimal.ZERO);
  }

  public ServiceAccountResponse response(
      ServicePaymentAccount a, WorkRequest w, ServiceAccountBalance b) {
    String blocker = blocker(a, w, b);
    BigDecimal net = b.received().subtract(b.refunded());
    return new ServiceAccountResponse(
        a.id(),
        a.workId(),
        a.assignmentId(),
        a.quoteId(),
        a.providerId(),
        a.payerUserId() != null ? "CUSTOMER" : "OWNER_ORGANIZATION",
        a.currency(),
        a.amount(),
        a.commissionBps(),
        a.fee(),
        a.amount().subtract(a.fee()),
        a.status(),
        a.version(),
        b.received(),
        b.refunded(),
        net,
        a.amount().subtract(net).max(BigDecimal.ZERO),
        b.reserved(),
        b.paid(),
        b.feesEarned(),
        refundAvailable(a, b),
        blocker == null ? a.amount().subtract(a.fee()) : BigDecimal.ZERO,
        w.completedAt() == null ? null : w.completedAt().plus(Duration.ofDays(14)),
        blocker,
        "MANUAL_EXTERNAL_VERIFICATION",
        a.createdAt());
  }

  public ServiceAccountResponse get(AuthenticatedUser actor, UUID id) {
    var a = access.get(actor, id, false);
    return response(a.account(), a.work(), payments.balance(id));
  }

  public ServiceAccountResponse current(AuthenticatedUser actor, UUID id) {
    return get(actor, id);
  }

  public void scope(AuthenticatedUser actor, UUID owner, UUID provider, boolean admin) {
    if (admin) access.operatorOnly(actor);
    else if (provider != null) providers.authorize(actor, provider, false);
    else if (owner != null) access.owner(actor, owner);
  }

  public List<ServiceAccountResponse> list(
      AuthenticatedUser actor, UUID owner, UUID provider, boolean admin, int limit, int offset) {
    Pagination.validate(limit, offset);
    scope(actor, owner, provider, admin);
    var accounts =
        payments.list(
            owner == null && provider == null && !admin ? actor.id() : null,
            owner,
            provider,
            limit,
            offset);
    var balances = payments.balances(accounts.stream().map(ServicePaymentAccount::id).toList());
    var works =
        work.findAll(accounts.stream().map(ServicePaymentAccount::workId).distinct().toList());
    return accounts.stream()
        .map(a -> response(a, works.get(a.workId()), balances.get(a.id())))
        .toList();
  }

  public List<ServiceFinanceSummaryResponse> summary(
      AuthenticatedUser actor, UUID owner, UUID provider, boolean admin) {
    scope(actor, owner, provider, admin);
    return payments.summary(
        owner == null && provider == null && !admin ? actor.id() : null, owner, provider);
  }

  public List<ServiceFinanceEventResponse> history(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.get(actor, id, false);
    return payments.history(id, limit, offset);
  }

  public List<ServiceClaimResponse> claims(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.privatePayments(access.get(actor, id, false));
    return payments.claims(id, limit, offset);
  }

  public List<ServiceReceiptResponse> receipts(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.privatePayments(access.get(actor, id, false));
    return payments.receipts(id, limit, offset);
  }

  public List<ServiceRefundResponse> refunds(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.privatePayments(access.get(actor, id, false));
    return payments.refunds(id, limit, offset);
  }

  public List<ProviderPayoutResponse> payouts(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.get(actor, id, false);
    return payouts.list(id, limit, offset);
  }
}
