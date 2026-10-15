package com.zaza.housing.controller;

import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.ReportPaymentRequest;
import com.zaza.housing.dto.RequestProviderPayoutRequest;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceClaimResponse;
import com.zaza.housing.dto.ServiceFinanceCommandRequest;
import com.zaza.housing.dto.ServiceFinanceEventResponse;
import com.zaza.housing.dto.ServiceFinanceSummaryResponse;
import com.zaza.housing.dto.ServiceReceiptResponse;
import com.zaza.housing.dto.ServiceRefundResponse;
import com.zaza.housing.model.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServicePaymentController {
  private static final String BASE = "/api/v1/service-payments";
  private final ServiceFinanceReadService reads;
  private final ServicePaymentService payments;
  private final ProviderPayoutService payouts;

  public ServicePaymentController(
      ServiceFinanceReadService reads,
      ServicePaymentService payments,
      ProviderPayoutService payouts) {
    this.reads = reads;
    this.payments = payments;
    this.payouts = payouts;
  }

  @GetMapping("/api/v1/me/service-payments")
  public List<ServiceAccountResponse> mine(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, null, null, false, limit, offset);
  }

  @GetMapping("/api/v1/me/service-payments/summary")
  public List<ServiceFinanceSummaryResponse> mySummary(
      @AuthenticationPrincipal AuthenticatedUser actor) {
    return reads.summary(actor, null, null, false);
  }

  @GetMapping("/api/v1/organizations/{organizationId}/service-payments")
  public List<ServiceAccountResponse> owner(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, organizationId, null, false, limit, offset);
  }

  @GetMapping("/api/v1/organizations/{organizationId}/service-payments/summary")
  public List<ServiceFinanceSummaryResponse> ownerSummary(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId) {
    return reads.summary(actor, organizationId, null, false);
  }

  @GetMapping(BASE + "/{id}")
  public ServiceAccountResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.get(actor, id);
  }

  @PostMapping(BASE + "/{id}/claims")
  public ServiceAccountResponse report(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReportPaymentRequest request) {
    return payments.report(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/close")
  public ServiceAccountResponse close(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ServiceFinanceCommandRequest request) {
    return payments.close(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/payouts")
  public ServiceAccountResponse request(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody RequestProviderPayoutRequest request) {
    return payouts.request(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/payouts/{payoutId}/cancel")
  public ServiceAccountResponse cancel(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID payoutId,
      @Valid @RequestBody ServiceFinanceCommandRequest request) {
    return payouts.cancel(actor, id, payoutId, request);
  }

  @GetMapping(BASE + "/{id}/history")
  public List<ServiceFinanceEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, id, limit, offset);
  }

  @GetMapping(BASE + "/{id}/claims")
  public List<ServiceClaimResponse> claims(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.claims(actor, id, limit, offset);
  }

  @GetMapping(BASE + "/{id}/receipts")
  public List<ServiceReceiptResponse> receipts(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.receipts(actor, id, limit, offset);
  }

  @GetMapping(BASE + "/{id}/refunds")
  public List<ServiceRefundResponse> refunds(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.refunds(actor, id, limit, offset);
  }

  @GetMapping(BASE + "/{id}/payouts")
  public List<ProviderPayoutResponse> payouts(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.payouts(actor, id, limit, offset);
  }
}
