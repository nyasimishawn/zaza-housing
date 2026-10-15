package com.zaza.housing.service;

import com.zaza.housing.dto.BillingAccountResponse;
import com.zaza.housing.dto.BillingCommandRequest;
import com.zaza.housing.dto.DecideDepositRequest;
import com.zaza.housing.dto.ProposeDepositRequest;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.BillingRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositService {
  private final BillingAccessService access;
  private final BillingRepository billing;
  private final BillingActivityService activity;
  private final AuthService auth;
  private final Clock clock;

  public DepositService(
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
  public BillingAccountResponse propose(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      ProposeDepositRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    var account = access.account(id);
    if (activity.replay(
        actor, id, request.requestId(), "DEPOSIT_PROPOSED", request, request.version()))
      return summary(id);
    if (!account.status().equals("FINALIZED"))
      throw BillingAccessService.conflict(
          "Finalize move-out billing before reconciling the deposit.");
    var held = summary(id).depositHeld();
    if (held.signum() <= 0 || request.deduction().compareTo(held) > 0)
      throw BillingAccessService.conflict("Deductions must fit within the held deposit.");
    if (billing.hasSettlement(id))
      throw BillingAccessService.conflict("Resolve the existing proposal before creating another.");
    UUID settlement = billing.settlement(id, held, request.deduction(), request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "DEPOSIT_PROPOSED",
        request,
        settlement,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse decide(
      AuthenticatedUser actor, UUID id, UUID settlementId, DecideDepositRequest request) {
    var lease = access.lease(actor, null, null, id, true);
    access.account(id);
    var body = Map.of("settlementId", settlementId, "request", request);
    if (activity.replay(actor, id, request.requestId(), "DEPOSIT_DECIDED", body, request.version()))
      return summary(id);
    var proposal =
        billing
            .settlement(id, settlementId)
            .orElseThrow(() -> BillingAccessService.missing("Deposit proposal not found."));
    if (!proposal.status().equals("PENDING"))
      throw BillingAccessService.conflict("This proposal is already resolved.");
    if (summary(id).depositHeld().compareTo(proposal.heldAtProposal()) != 0)
      throw BillingAccessService.conflict(
          "The deposit balance changed; a new proposal is required.");
    billing.decideSettlement(
        settlementId, actor.id(), request.decision().name(), request.reason().trim());
    activity.record(
        actor,
        null,
        lease,
        request.requestId(),
        "DEPOSIT_DECIDED",
        body,
        settlementId,
        request.reason().trim(),
        false);
    return summary(id);
  }

  @Transactional
  public BillingAccountResponse cancel(
      AuthenticatedUser actor,
      UUID organization,
      UUID property,
      UUID id,
      UUID settlementId,
      BillingCommandRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    var body = Map.of("settlementId", settlementId, "request", request);
    if (activity.replay(
        actor, id, request.requestId(), "DEPOSIT_CANCELLED", body, request.version()))
      return summary(id);
    var proposal =
        billing
            .settlement(id, settlementId)
            .orElseThrow(() -> BillingAccessService.missing("Deposit proposal not found."));
    if (!proposal.status().equals("PENDING"))
      throw BillingAccessService.conflict(
          "Only a pending proposal can be withdrawn. A disputed proposal remains recorded and may"
              + " be replaced.");
    billing.decideSettlement(settlementId, actor.id(), "CANCEL", request.reason().trim());
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "DEPOSIT_CANCELLED",
        body,
        settlementId,
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
      RecordRefundRequest request) {
    var lease = access.lease(actor, organization, property, id, true);
    access.account(id);
    auth.reauthenticate(actor, request.password());
    if (activity.replay(
        actor, id, request.requestId(), "DEPOSIT_REFUNDED", request, request.version()))
      return summary(id);
    if (!billing.acceptedSettlement(id))
      throw BillingAccessService.conflict(
          "The tenant must accept the deposit reconciliation before a refund can be recorded.");
    if (request.amount().compareTo(summary(id).depositHeld()) > 0)
      throw BillingAccessService.conflict("Refund exceeds the refundable held deposit.");
    if (request.receivedOn().isAfter(LocalDate.now(clock))
        || request.receivedOn().isBefore(LocalDate.of(1900, 1, 1)))
      throw BillingAccessService.invalid("Use an actual refund date from 1900 through today.");
    String reference = PaymentService.reference(request.reference());
    billing.reference(lease.ownerOrganizationId(), request.channel(), reference);
    UUID refund = billing.refund(id, null, true, request, reference);
    activity.record(
        actor,
        organization,
        lease,
        request.requestId(),
        "DEPOSIT_REFUNDED",
        request,
        refund,
        request.reason().trim(),
        false);
    return summary(id);
  }

  private BillingAccountResponse summary(UUID id) {
    return billing.summary(id, LocalDate.now(clock));
  }
}
