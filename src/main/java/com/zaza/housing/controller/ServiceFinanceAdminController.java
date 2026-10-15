package com.zaza.housing.controller;

import com.zaza.housing.dto.PayoutDestinationResponse;
import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.RecordPaymentRequest;
import com.zaza.housing.dto.RecordRefundRequest;
import com.zaza.housing.dto.ReviewPayoutDestinationRequest;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceFinanceSummaryResponse;
import com.zaza.housing.dto.VerifyPaymentRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ProviderDestinationService;
import com.zaza.housing.service.ProviderPayoutService;
import com.zaza.housing.service.ServiceFinanceReadService;
import com.zaza.housing.service.ServicePaymentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/service-finance")
public class ServiceFinanceAdminController {
  private final ServiceFinanceReadService reads;
  private final ServicePaymentService payments;
  private final ProviderPayoutService payouts;
  private final ProviderDestinationService destinations;

  public ServiceFinanceAdminController(
      ServiceFinanceReadService reads,
      ServicePaymentService payments,
      ProviderPayoutService payouts,
      ProviderDestinationService destinations) {
    this.reads = reads;
    this.payments = payments;
    this.payouts = payouts;
    this.destinations = destinations;
  }

  @GetMapping("/payouts")
  public List<ProviderPayoutResponse> requests(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return payouts.queue(actor, null, status, limit, offset);
  }

  @GetMapping("/accounts")
  public List<ServiceAccountResponse> accounts(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, null, null, true, limit, offset);
  }

  @GetMapping("/summary")
  public List<ServiceFinanceSummaryResponse> summary(
      @AuthenticationPrincipal AuthenticatedUser actor) {
    return reads.summary(actor, null, null, true);
  }

  @GetMapping("/providers/{providerId}/destinations")
  public List<PayoutDestinationResponse> destinations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID providerId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return destinations.list(actor, providerId, limit, offset);
  }

  @PostMapping("/destinations/{destinationId}/review")
  public PayoutDestinationResponse review(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID destinationId,
      @Valid @RequestBody ReviewPayoutDestinationRequest request) {
    return destinations.review(actor, destinationId, request);
  }

  @PostMapping("/accounts/{id}/receipts")
  public ServiceAccountResponse receipt(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody RecordPaymentRequest request) {
    return payments.record(actor, id, request);
  }

  @PostMapping("/accounts/{id}/receipts/{receiptId}/void")
  public ServiceAccountResponse voidReceipt(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID receiptId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.voidReceipt(actor, id, receiptId, request);
  }

  @PostMapping("/accounts/{id}/receipts/{receiptId}/refunds")
  public ServiceAccountResponse refund(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID receiptId,
      @Valid @RequestBody RecordRefundRequest request) {
    return payments.refund(actor, id, receiptId, request);
  }

  @PostMapping("/accounts/{id}/payouts/{payoutId}/paid")
  public ServiceAccountResponse paid(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody RecordPaymentRequest request) {
    return payouts.paid(actor, id, payoutId, request);
  }

  @PostMapping("/accounts/{id}/claims/{claimId}/verify")
  public ServiceAccountResponse verify(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.decide(actor, id, claimId, request, true);
  }

  @PostMapping("/accounts/{id}/claims/{claimId}/reject")
  public ServiceAccountResponse reject(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID claimId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payments.decide(actor, id, claimId, request, false);
  }

  @PostMapping("/accounts/{id}/payouts/{payoutId}/approve")
  public ServiceAccountResponse payoutAPPROVED(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payouts.review(actor, id, payoutId, request, "APPROVED");
  }

  @PostMapping("/accounts/{id}/payouts/{payoutId}/reject")
  public ServiceAccountResponse payoutREJECTED(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payouts.review(actor, id, payoutId, request, "REJECTED");
  }

  @PostMapping("/accounts/{id}/payouts/{payoutId}/fail")
  public ServiceAccountResponse payoutFAILED(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payouts.review(actor, id, payoutId, request, "FAILED");
  }

  @PostMapping("/accounts/{id}/payouts/{payoutId}/cancel")
  public ServiceAccountResponse payoutCANCELLED(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody VerifyPaymentRequest request) {
    return payouts.review(actor, id, payoutId, request, "CANCELLED");
  }
}
