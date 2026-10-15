package com.zaza.housing.controller;

import com.zaza.housing.dto.PayoutDestinationRequest;
import com.zaza.housing.dto.PayoutDestinationResponse;
import com.zaza.housing.dto.ProviderPayoutResponse;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceFinanceSummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ProviderDestinationService;
import com.zaza.housing.service.ProviderPayoutService;
import com.zaza.housing.service.ServiceFinanceReadService;
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
@RequestMapping("/api/v1/organizations/{organizationId}/provider-payouts")
public class ProviderPayoutController {
  private final ProviderPayoutService payouts;
  private final ServiceFinanceReadService reads;
  private final ProviderDestinationService destinations;

  public ProviderPayoutController(
      ServiceFinanceReadService reads,
      ProviderDestinationService destinations,
      ProviderPayoutService payouts) {
    this.payouts = payouts;
    this.reads = reads;
    this.destinations = destinations;
  }

  @GetMapping("/requests")
  public List<ProviderPayoutResponse> requests(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return payouts.queue(actor, organizationId, status, limit, offset);
  }

  @GetMapping("/accounts")
  public List<ServiceAccountResponse> accounts(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, null, organizationId, false, limit, offset);
  }

  @GetMapping("/summary")
  public List<ServiceFinanceSummaryResponse> summary(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId) {
    return reads.summary(actor, null, organizationId, false);
  }

  @GetMapping("/destinations")
  public List<PayoutDestinationResponse> destinations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return destinations.list(actor, organizationId, limit, offset);
  }

  @PostMapping("/destinations")
  public PayoutDestinationResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody PayoutDestinationRequest request) {
    return destinations.create(actor, organizationId, request);
  }
}
